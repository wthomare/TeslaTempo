package org.teslatempo.tempo

import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.teslatempo.personal.PersonalCodec
import org.teslatempo.planner.TempoColor
import java.net.InetAddress
import java.time.*

class TempoGatewayTest {
    private val now = Instant.parse("2026-10-29T10:00:00Z")
    private val day = LocalDate.of(2026, 10, 29)
    private val tariff = """{"date_effet":"2026-08-01","unite":"EUR/kWh TTC","heures_pleines":"06:00-22:00","heures_creuses":"22:00-06:00","couleurs":{"BLEU":{"hc":0.1356,"hp":0.1654},"BLANC":{"hc":0.1536,"hp":0.1921},"ROUGE":{"hc":0.1615,"hp":0.7295}}}"""
    private val today = """{"status":"ok","date":"2026-10-29","code":1,"couleur":"BLEU","tarifs":$tariff}"""
    private val tomorrow = """{"status":"unavailable","date":"2026-10-30","code":0}"""
    private val history = """{"status":"ok","history":[{"date":"2026-10-28","couleur_reelle":"ROUGE"},{"date":"2026-10-30","couleur_reelle":"BLEU"}]}"""
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun MockWebServer.gateway() = TempoGateway(url("/").newBuilder().host("127.0.0.1").build())
    private fun MockWebServer.json(value: String) = enqueue(MockResponse().setBody(value))

    @Test fun `live contract parses six prices dates and unknown tomorrow without predicting`() {
        server().use { s ->
            s.json(today); s.json(tomorrow); s.json(history)
            val result = s.gateway().fetch(now)
            assertTrue(result.errors.isEmpty())
            assertEquals(3, result.colors.size)
            assertNull(result.colors.first { it.date == "2026-10-30" }.color)
            assertEquals(6, result.tariff!!.rates.size)
            assertEquals(.7295, result.tariff.rates.last(), 0.0)
            for (path in listOf("/api/today", "/api/tomorrow", "/api/history?days=2")) {
                val request = s.takeRequest(); assertEquals(path, request.path)
                assertNull(request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `partial failure keeps good results and refuses wrong day`() {
        server().use { s ->
            s.json(today.replace("2026-10-29", "2026-10-28")); s.json(tomorrow); s.json(history)
            val result = s.gateway().fetch(now)
            assertTrue(result.errors.isNotEmpty()); assertNull(result.tariff)
            assertFalse(result.colors.any { it.date == day.toString() })
        }
    }
    @Test fun `bad tariff does not discard confirmed color`() {
        server().use { s ->
            s.json(today.replace("EUR/kWh TTC", "cents")); s.json(tomorrow); s.json(history)
            val result = s.gateway().fetch(now)
            assertEquals(TempoColor.BLUE, result.colors.first().color)
            assertNull(result.tariff); assertTrue(result.errors.any { it.startsWith("Tarifs") })
        }
    }
    @Test fun `redirect error and invalid JSON never create fabricated values`() {
        server().use { s ->
            s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/other"))
            s.enqueue(MockResponse().setResponseCode(503))
            s.json("not JSON")
            val result = s.gateway().fetch(now)
            assertEquals(3, result.errors.size); assertTrue(result.colors.isEmpty()); assertNull(result.tariff)
            assertEquals(3, s.requestCount)
        }
    }
    @Test fun `code color mismatch invalid prices future version and timezone errors rejected`() {
        fun parsed(raw: String) = JsonParser.parseString(raw).asJsonObject
        assertThrows(Exception::class.java) { TempoGateway.parseColor(parsed(today.replace("\"code\":1", "\"code\":3")), day, now) }
        for (invalid in listOf(tariff.replace("0.1356", "-0.1"), tariff.replace("2026-08-01", "2027-08-01"), tariff.replace("06:00-22:00", "07:00-23:00"))) {
            assertThrows(Exception::class.java) { TempoGateway.parseTariff(parsed(invalid), day, now) }
        }
    }
    @Test fun `cache survives failure and midnight never relabels yesterday as today`() {
        val prices = TempoGateway.parseTariff(JsonParser.parseString(tariff).asJsonObject, day, now)
        val old = TempoCache(listOf(ObservedColor(day.toString(), TempoColor.BLUE, now.toString())), prices)
        val next = Instant.parse("2026-10-29T23:30:00Z")
        val merged = old.merge(TempoUpdate(emptyList(), null, listOf("offline")), next)
        assertNull(merged.color(LocalDate.of(2026, 10, 30)))
        assertEquals(old, PersonalCodec.tempo(PersonalCodec.encode(merged)))
        assertEquals(TempoColor.BLUE, merged.calendar(next).price(next).color) // 00:30 Paris still previous Tempo day
        assertNull(merged.calendar(next).price(Instant.parse("2026-10-30T06:00:00Z")).color)
    }
    @Test fun `stale tariffs and future clock timestamps block calculations`() {
        val t = PublishedTariff("2026-08-01", listOf(.1,.2,.3,.4,.5,.6), now.toString())
        assertThrows(IllegalArgumentException::class.java) { t.version(now.plus(Duration.ofDays(7))) }
        assertThrows(IllegalArgumentException::class.java) { t.version(now.minusSeconds(1)) }
        assertEquals(LocalDate.of(2026, 11, 5), t.version(now).validUntil)
    }
}
