package org.teslatempo.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.teslatempo.network.RelayCredentials
import org.teslatempo.network.validateRelayUrl
import java.util.concurrent.TimeUnit

class DiagnosticGateway(credentials: RelayCredentials, local: Boolean = false) {
    private val base=validateRelayUrl(credentials.url,local)
    private val token=credentials.token.also { require(it.isNotEmpty() && it.all { c -> c.code in 33..126 }) }
    private val http=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
    fun send(report: DiagnosticReport): Boolean {
        require(report.consent && report.events.isNotEmpty() && report.events.size<=100 && report.events.all(DiagnosticLog::valid))
        val raw=Gson().toJson(report);require(raw.toByteArray().size<=65536)
        val request=Request.Builder().url(base.newBuilder().addPathSegments("v1/diagnostics/events").build())
            .header("Authorization","Bearer $token").post(raw.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use {
            if(!it.isSuccessful)return false
            val bytes=it.body?.byteStream()?.readNBytes(4097) ?: return false
            if(bytes.size>4096)return false
            val response=JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            response.get("report_id").asString==report.report_id && response.get("accepted").asInt==report.events.size
        }
    }
}
