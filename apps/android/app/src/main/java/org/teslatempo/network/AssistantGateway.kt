package org.teslatempo.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

data class AssistantModel(val id: String, val name: String)
data class AssistantConnection(val connected: Boolean, val enabled: Boolean, val account: String?)

class AssistantGateway(credentials: RelayCredentials, allowLocalHttp: Boolean = false, client: OkHttpClient = OkHttpClient()) {
    private val base = validateRelayUrl(credentials.url, allowLocalHttp)
    private val token = credentials.token.trim().also {
        if (it.isEmpty() || it.any { c -> c.code !in 33..126 }) throw RelayException("Jeton du relais invalide.")
    }
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(85, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    private fun call(path: String, method: String = "GET", payload: JsonObject? = null): JsonObject {
        try {
            val request = Request.Builder().url(base.newBuilder().addPathSegments("v1/chatgpt/$path").build())
                .header("Authorization", "Bearer $token")
                .method(method, payload?.toString()?.toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw RelayException(when (response.code) {
                    401, 403 -> "Accès au relais refusé. Vérifiez les réglages."
                    404 -> "Mettez à jour le relais pour activer l’assistant."
                    409 -> "Connexion ou autorisation ChatGPT nécessaire sur le relais."
                    422 -> "Modèle ou demande refusé. Actualisez les modèles et vérifiez le résumé."
                    429 -> "Limite d’utilisation atteinte ou requête déjà en cours. Patientez avant de réessayer."
                    in 300..399 -> "Redirection refusée. Vérifiez l’adresse du relais."
                    else -> "Réponse ChatGPT indisponible ou interrompue. Aucun résultat complet reçu."
                })
                val raw = response.body?.byteStream()?.readNBytes(131073) ?: throw IllegalStateException()
                if (raw.size > 131072) throw IllegalStateException()
                JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
            }
        } catch (e: RelayException) { throw e
        } catch (_: Exception) { throw RelayException("Connexion interrompue ou réponse non reconnue. Aucun résultat complet reçu.") }
    }
    fun status(): AssistantConnection {
        try {
            val r = call("status")
            check(!r.get("commands_enabled").asBoolean)
            return AssistantConnection(r.get("connected").asBoolean, r.get("plan_enabled").asBoolean,
                r.get("account")?.takeUnless { it.isJsonNull }?.asString)
        } catch (e: RelayException) { throw e
        } catch (_: Exception) { throw RelayException("Statut de l’assistant non reconnu.") }
    }
    fun models(): List<AssistantModel> {
        try {
            return call("models").getAsJsonArray("models").take(100).map {
                AssistantModel(it.asJsonObject.get("id").asString, it.asJsonObject.get("name").asString)
            }
        } catch (e: RelayException) { throw e
        } catch (_: Exception) { throw RelayException("Catalogue de modèles non reconnu.") }
    }
    fun explain(model: String, summary: String, account: String): String {
        require(summary.length in 1..6000)
        try {
            val r = call("explain", "POST", JsonObject().apply {
                addProperty("account", account); addProperty("model", model); addProperty("summary", summary); addProperty("consent", true)
            })
            check(r.get("completed").asBoolean && !r.get("vehicle_commands_enabled").asBoolean)
            return r.get("text").asString.also { check(it.isNotBlank() && it.length <= 16000) }
        } catch (e: RelayException) { throw e
        } catch (_: Exception) { throw RelayException("Réponse ChatGPT incomplète ou non reconnue.") }
    }
    fun disconnect(): Boolean = call("connection", "DELETE").get("remote_revocation_confirmed").asBoolean
}
