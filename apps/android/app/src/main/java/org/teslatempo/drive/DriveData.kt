package org.teslatempo.drive

import java.security.MessageDigest
import java.util.Locale

class DriveFailure(val userMessage: String, val recoverableData: Boolean = false) : Exception(userMessage)
fun normalizedVin(value: String): String = value.trim().uppercase(Locale.ROOT).also {
    if (!it.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) throw DriveFailure("Saisissez les 17 caractères du VIN, sans I, O ni Q.")
}
fun advertisementName(vin: String): String = "S" + MessageDigest.getInstance("SHA-1")
    .digest(normalizedVin(vin).toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) } + "C"
fun milesToKm(value: Double?) = value?.takeIf { it.isFinite() && it in 0.0..1500.0 }?.times(1.609344)
fun fresh(at: Long?, now: Long, maxAge: Long): Boolean = at != null && now >= at && now - at <= maxAge
// No toString containing a destination, coordinates or VIN.
class DriveDestination(val name: String, val latitude: Double, val longitude: Double, val at: Long) {
    fun wazeLink(): String = "https://waze.com/ul?ll=${latitude},${longitude}&navigate=yes&utm_source=teslatempo"
    companion object {
        fun from(name: String?, lat: Double?, lon: Double?, at: Long): DriveDestination? {
            if (name.isNullOrBlank() || name.length > 512 || lat == null || lon == null ||
                !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
            return DriveDestination(name, lat, lon, at)
        }
    }
}
data class DriveState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Session BLE arrêtée",
    val battery: Int? = null, val rangeKm: Double? = null, val chargeAt: Long? = null,
    val destination: DriveDestination? = null,
    val overlay: Boolean = false,
) {
    fun cockpit(now: Long, showRange: Boolean = true): CockpitText = CockpitText(
        if (fresh(chargeAt, now, 60_000)) battery?.let { "$it %" } ?: "— %" else "— %",
        if (!showRange) "" else if (fresh(chargeAt, now, 60_000)) rangeKm?.let { "%.0f km".format(Locale.FRANCE, it) } ?: "— km" else "— km",
    )
}

data class CockpitText(val battery: String, val range: String)

/** Bounded length-prefixed Tesla BLE frames, including fragmented/coalesced notifications. */
class BleFrames {
    private var buffer = byteArrayOf()
    @Synchronized fun append(bytes: ByteArray): List<ByteArray> {
        if (buffer.size + bytes.size > 8192) throw DriveFailure("Réponse Bluetooth trop volumineuse.")
        buffer += bytes
        val output = mutableListOf<ByteArray>()
        while (buffer.size >= 2) {
            val size = ((buffer[0].toInt() and 255) shl 8) or (buffer[1].toInt() and 255)
            if (size !in 1..4096) throw DriveFailure("Trame Bluetooth invalide.")
            if (buffer.size < size + 2) break
            output += buffer.copyOfRange(2, size + 2)
            buffer = buffer.copyOfRange(size + 2, buffer.size)
        }
        return output
    }
    companion object {
        fun encode(bytes: ByteArray): ByteArray {
            require(bytes.size in 1..4096)
            return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
        }
    }
}
