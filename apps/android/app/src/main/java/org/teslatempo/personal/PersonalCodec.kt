package org.teslatempo.personal

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.teslatempo.tempo.TempoCache

/** Schema envelope; validate on read because Gson does not call Kotlin constructors. */
object PersonalCodec {
    private val gson = Gson()
    fun encode(value: Any): String = "{\"schema\":1,\"data\":" + gson.toJson(value) + "}"
    private fun <T> decode(raw: String, type: Class<T>): T {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("schema").asInt == 1) { "Version de données locale non prise en charge." }
        return requireNotNull(gson.fromJson(root.get("data"), type))
    }
    fun draft(raw: String) = decode(raw, PersonalSettings::class.java)
    fun settings(raw: String) = decode(raw, PersonalSettings::class.java).validate()
    fun tempo(raw: String) = decode(raw, TempoCache::class.java).validate()
}
