package org.teslatempo.tempo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslatempo.planner.TempoColor
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Separate public client: never attach a Tesla/relay token to these requests. */
class TempoGateway(
    private val base: HttpUrl = "$TEMPO_SOURCE/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun get(path: String): JsonObject {
        val request = Request.Builder().url(base.resolve(path)!!).header("Accept", "application/json")
            .header("User-Agent", "TeslaTempo/0.3 (personal Android client)").build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Service Tempo indisponible (HTTP ${response.code})." }
            val bytes = requireNotNull(response.body).byteStream().readNBytes(262145)
            require(bytes.size <= 262144)
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }
    }
    fun fetch(now: Instant): TempoUpdate {
        val today = now.atZone(PARIS).toLocalDate()
        val colors = mutableListOf<ObservedColor>()
        val errors = mutableListOf<String>()
        var tariff: PublishedTariff? = null
        fun attempt(label: String, action: () -> Unit) {
            try { action() } catch (_: Exception) { errors += "$label : données indisponibles ou non reconnues ; dernier cache conservé." }
        }
        attempt("Aujourd’hui") {
            val response = get("api/today")
            colors += parseColor(response, today, now)
            // A color and a tariff are independently useful if only one is malformed.
            attempt("Tarifs") { tariff = parseTariff(response.getAsJsonObject("tarifs"), today, now) }
        }
        attempt("Demain") { colors += parseColor(get("api/tomorrow"), today.plusDays(1), now) }
        attempt("Hier") {
            val history = get("api/history?days=2")
            require(history.get("status").asString == "ok")
            val rows = history.getAsJsonArray("history").filter { it.asJsonObject.get("date").asString == today.minusDays(1).toString() }
            require(rows.size == 1)
            val color = parseName(rows.single().asJsonObject.get("couleur_reelle").asString)
            colors += ObservedColor(today.minusDays(1).toString(), color, now.toString())
        }
        return TempoUpdate(colors, tariff, errors)
    }
    companion object {
        fun parseColor(row: JsonObject, expected: LocalDate, now: Instant): ObservedColor {
            require(row.get("date").asString == expected.toString()) { "Date Tempo incohérente." }
            val code = row.get("code").asString.toInt()
            val status = row.get("status").asString
            val color = if (status == "unavailable" && code == 0) null else {
                require(status == "ok" && code in 1..3)
                val parsed = parseName(row.get("couleur").asString)
                require(parsed == TempoColor.entries[code - 1])
                parsed
            }
            return ObservedColor(expected.toString(), color, now.toString())
        }
        fun parseTariff(row: JsonObject, today: LocalDate, now: Instant): PublishedTariff {
            require(row.get("unite").asString == "EUR/kWh TTC")
            require(row.get("heures_pleines").asString == "06:00-22:00" && row.get("heures_creuses").asString == "22:00-06:00")
            val effect = row.get("date_effet").asString
            require(LocalDate.parse(effect) <= today)
            val rates = listOf("BLEU", "BLANC", "ROUGE").flatMap { color ->
                val prices = row.getAsJsonObject("couleurs").getAsJsonObject(color)
                listOf("hc", "hp").map { prices.get(it).asDouble }
            }
            return PublishedTariff(effect, rates, now.toString()).also { it.validate() }
        }
        private fun parseName(name: String) = when (name) {
            "BLEU" -> TempoColor.BLUE; "BLANC" -> TempoColor.WHITE; "ROUGE" -> TempoColor.RED
            else -> throw IllegalArgumentException("Couleur inconnue")
        }
    }
}
