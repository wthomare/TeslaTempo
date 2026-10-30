package org.teslatempo.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslatempo.domain.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class RelayException(message: String) : IOException(message)

// Deliberately not a data class: toString must never expose the token.
class RelayCredentials(val url: String, val token: String)

fun validateRelayUrl(raw: String, allowLocalHttp: Boolean): HttpUrl {
    val url = raw.trim().toHttpUrlOrNull()
        ?: throw RelayException("Saisissez une URL HTTPS valide.")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || url.encodedPath != "/") {
        throw RelayException("Utilisez l’adresse racine du relais, sans identifiants, chemin ou paramètres.")
    }
    val localDebug = allowLocalHttp && url.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    if (!url.isHttps && !localDebug) throw RelayException("HTTPS est obligatoire pour ce relais.")
    return url
}

class RelayGateway(
    credentials: RelayCredentials,
    allowLocalHttp: Boolean = false,
    client: OkHttpClient = OkHttpClient(),
) : VehicleGateway {
    private val base = validateRelayUrl(credentials.url, allowLocalHttp)
    private val token = credentials.token.trim().also {
        if (it.isEmpty() || it.any { c -> c.code !in 33..126 }) throw RelayException("Le jeton du relais est vide ou invalide.")
    }
    // A redirect must never carry a user's token to another destination.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    private fun get(vararg segments: String, method: String = "GET"): JsonObject {
        val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        try {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, null).build()).execute().use { response ->
                if (!response.isSuccessful) throw RelayException(when (response.code) {
                    401, 403 -> "Accès refusé. Vérifiez le jeton du relais."
                    404 -> "Véhicule ou endpoint introuvable. Actualisez la liste."
                    409 -> "Connexion Tesla nécessaire sur le relais."
                    429 -> "Trop de requêtes. Patientez une minute avant de réessayer."
                    in 300..399 -> "Redirection refusée. Configurez directement l’URL HTTPS finale."
                    else -> "Le relais est indisponible (HTTP ${response.code})."
                })
                val body = response.body ?: throw RelayException("Réponse du relais vide.")
                // Bound input before parsing, including responses without Content-Length.
                val bytes = body.byteStream().readNBytes(1_048_577)
                if (bytes.size > 1_048_576) throw RelayException("Réponse du relais trop volumineuse.")
                return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            }
        } catch (e: RelayException) { throw e
        } catch (_: IOException) { throw RelayException("Connexion impossible. Vérifiez l’adresse, le réseau et le certificat HTTPS.")
        } catch (_: RuntimeException) { throw RelayException("Réponse du relais non reconnue.") }
    }

    fun observations(vehicleId: String): ObservationHistory {
        try {
            val data = get("v1", "vehicles", vehicleId, "charge-observations")
            check(data.get("coverage").asString == "snapshots_only")
            check(data.get("total_energy_kwh").isJsonNull && data.get("total_cost_euros").isJsonNull)
            val rows = data.getAsJsonArray("samples")
            check(rows.size() <= 300)
            val samples = rows.map {
                val row = it.asJsonObject
                val at = java.time.Instant.parse(row.get("sampled_at").asString)
                fun optional(key: String, max: Double): Double? = row.get(key)?.takeUnless { v -> v.isJsonNull }?.asDouble?.also { v -> check(v.isFinite() && v in 0.0..max) }
                ChargeObservation(at, row.get("state").asString, optional("battery_percent", 100.0),
                    optional("session_counter_kwh", 500.0), optional("current_amps", 1000.0))
            }
            return ObservationHistory(data.get("simulated").asBoolean, samples)
        } catch (e: RelayException) { throw e
        } catch (_: Exception) { throw RelayException("Relevés non reconnus. Aucune valeur manquante n’a été remplacée par zéro.") }
    }
    fun clearObservations(vehicleId: String) {
        try { check(get("v1", "vehicles", vehicleId, "charge-observations", method = "DELETE").get("cleared").asBoolean) }
        catch (e: RelayException) { throw e }
        catch (_: Exception) { throw RelayException("Suppression non confirmée par le relais.") }
    }

    override fun load(selectedId: String?): Dashboard {
        try {
            val status = get("v1", "status")
            val simulated = status.get("simulated").asBoolean
            val mode = status.get("mode").asString
            if (mode !in setOf("mock", "tesla") || simulated != (mode == "mock")) throw IllegalStateException()
            val list = get("v1", "vehicles")
            if (list.get("simulated").asBoolean != simulated) throw IllegalStateException()
            val vehicles = list.getAsJsonArray("vehicles").map { element ->
                val row = element.asJsonObject
                Vehicle(row.get("id").asString, row.text("name") ?: "Véhicule", row.text("state") ?: "unknown")
            }
            val selected = vehicles.find { it.id == selectedId } ?: vehicles.firstOrNull()
            val charge = selected?.let {
                val response = get("v1", "vehicles", it.id, "charge-state")
                if (response.get("simulated").asBoolean != simulated) throw IllegalStateException()
                response.get("charge_state")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { row ->
                    ChargeState(row.number("battery_level", 0..100), row.number("charge_limit_soc", 0..100),
                        row.number("charge_current_request", 0..1000), row.text("charging_state"))
                }
            }
            return Dashboard(simulated, vehicles, selected, charge)
        } catch (e: RelayException) { throw e
        } catch (_: RuntimeException) { throw RelayException("Réponse du relais non reconnue.") }
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.number(key: String, range: IntRange): Int? = get(key)?.takeUnless { it.isJsonNull }?.let {
    val value = it.asString.toIntOrNull() ?: throw IllegalStateException()
    if (value !in range) throw IllegalStateException()
    value
}

data class ChargeObservation(val at: java.time.Instant, val state: String, val battery: Double?, val sessionCounter: Double?, val current: Double?)
data class ObservationHistory(val simulated: Boolean, val samples: List<ChargeObservation>)
