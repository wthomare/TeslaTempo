package org.teslatempo.history

import org.junit.Assert.*
import org.junit.Test
import org.teslatempo.planner.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class HistoryTest {
    private fun snapshot(scenario: DemoScenario = DemoScenario.UNKNOWN, soc: Double = 20.0): SavedPlan {
        val demo = demoSimulation(LocalDate.parse("2026-11-02"), soc, 60.0, 10, scenario)
        return SavedPlan.capture(demo.request, ChargingOptimizer().calculate(demo.request, demo.calendar),
            PlanOrigin.DEMO, Instant.parse("2026-11-02T12:00:00Z"))
    }
    @Test fun `assistant summary only exports aggregate allowlisted fields`() {
        val saved = snapshot()
        val privateSource = "PRIVATE_SOURCE_DO_NOT_EXPORT"
        val changed = saved.copy(optimized = saved.optimized.copy(slots = saved.optimized.slots.map {
            it.copy(price = it.price.copy(source = privateSource, tariffId = "PRIVATE_TARIFF"))
        }))
        val text = assistantSummary(changed)
        listOf(saved.id, saved.calculatedAt, saved.request.start, saved.request.departure,
            privateSource, "PRIVATE_TARIFF", "2026-11-02").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("Démonstration fictive"))
        assertTrue(text.contains("Couleur Tempo inconnue dans au moins un créneau retenu : oui"))
        assertTrue(text.contains("pas une recharge mesurée"))
    }
    @Test fun `assistant summary preserves shortfall and distinguishes personal from demo`() {
        val partial = snapshot(DemoScenario.LEAVE).copy(origin = PlanOrigin.PERSONAL)
        val text = assistantSummary(partial)
        assertTrue(text.contains("Simulation personnelle"))
        assertTrue(text.contains(String.format(java.util.Locale.FRANCE, "%.2f", partial.optimized.missingGridKwh) + " kWh"))
        assertTrue(assistantSummary(snapshot(soc = 90.0)).contains("coût estimé 0,00 €"))
    }
    @Test fun `roundtrip preserves costs times unknown colors and provenance without recalculation`() {
        val saved = snapshot()
        val loaded = HistoryCodec.decode(HistoryCodec.encode(listOf(saved))).single()
        assertEquals(saved, loaded)
        assertEquals(saved.optimized.estimate().costEuros, loaded.optimized.estimate().costEuros, 0.0)
        assertTrue(loaded.optimized.slots.any { it.price.color == null })
        assertEquals("demo-v1", loaded.optimized.slots.first().price.tariffId)
        assertEquals(PlanOrigin.DEMO, loaded.origin)
    }
    @Test fun `shortfall and target already reached remain distinguishable`() {
        val partial = snapshot(DemoScenario.LEAVE)
        assertFalse(partial.optimized.estimate().complete)
        assertTrue(partial.optimized.missingGridKwh > 0)
        val reached = HistoryCodec.decode(HistoryCodec.encode(listOf(snapshot(soc = 90.0)))).single()
        assertEquals(PlanStatus.TARGET_REACHED, reached.status)
        assertTrue(reached.optimized.slots.isEmpty())
        assertEquals(90.0, reached.optimized.estimatedSoc, 0.0)
    }
    @Test fun `duplicate saving is idempotent and sorting uses instants`() {
        val a = snapshot(); val b = snapshot().copy(calculatedAt = "2026-11-03T12:00:00Z")
        val entries = HistoryCodec.append(listOf(a), b)
        assertEquals(b.id, entries.first().id)
        assertEquals(entries, HistoryCodec.append(entries, b))
    }
    @Test fun `full history refuses insertion without dropping old entries`() {
        val a = snapshot()
        val entries = List(HISTORY_LIMIT) { a.copy(id = UUID.randomUUID().toString()) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCodec.append(entries, a) }
        assertEquals(entries, HistoryCodec.append(entries, entries.first()))
    }
    @Test fun `malformed data and unsupported schema are rejected`() {
        val raw = HistoryCodec.encode(listOf(snapshot()))
        assertThrows(IllegalArgumentException::class.java) { HistoryCodec.decode(raw.replace("\"schema\":1", "\"schema\":2")) }
        assertThrows(Exception::class.java) { HistoryCodec.decode("{broken") }
        assertThrows(Exception::class.java) { HistoryCodec.decode("{\"schema\":1,\"plans\":[{}]}") }
        assertThrows(IllegalArgumentException::class.java) { HistoryCodec.encode(listOf(snapshot().copy(id = "bad-id"))) }
    }
    @Test fun `invalid energy and dates cannot be presented as saved results`() {
        val s = snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(optimized = s.optimized.copy(missingGridKwh = Double.NaN)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(optimized = s.optimized.copy(missingGridKwh = 999.0)).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.copy(optimized = s.optimized.copy(slots = s.optimized.slots.map { it.copy(end = it.start) })).validate()
        }
    }
    @Test fun `conflicting or executed plans cannot become archived simulations`() {
        val d = demoSimulation(LocalDate.parse("2026-11-02"), 20.0, 60.0, 10, DemoScenario.HOME)
        val r = ChargingOptimizer().calculate(d.request, d.calendar)
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlan.capture(d.request, r.copy(executionConfirmed = true), PlanOrigin.PERSONAL, Instant.now())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlan.capture(d.request, r.copy(status = PlanStatus.MANUAL_CONFLICT), PlanOrigin.PERSONAL, Instant.now())
        }
    }
}
