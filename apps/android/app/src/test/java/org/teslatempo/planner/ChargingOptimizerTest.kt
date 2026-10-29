package org.teslatempo.planner

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ChargingOptimizerTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 11, 2)
    private fun at(date: LocalDate = day, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant()
    private val rates = mapOf(
        RateKey(TempoColor.BLUE, Period.HC) to .10, RateKey(TempoColor.BLUE, Period.HP) to .15,
        RateKey(TempoColor.WHITE, Period.HC) to .12, RateKey(TempoColor.WHITE, Period.HP) to .20,
        RateKey(TempoColor.RED, Period.HC) to .14, RateKey(TempoColor.RED, Period.HP) to .70)
    private fun tariff(from: LocalDate = LocalDate.of(2026, 1, 1), until: LocalDate = LocalDate.of(2027, 1, 1)) =
        TariffVersion("test", "Synthetic test data", from, until, rates)
    private fun calendar(colors: Map<LocalDate, TempoColor> = mapOf(day to TempoColor.BLUE, day.plusDays(1) to TempoColor.RED)) =
        TempoCalendar(colors, listOf(tariff()))
    private fun request() = demoSimulation(day, 20.0, 60.0, 10, DemoScenario.HOME).request
    private val engine = ChargingOptimizer()

    @Test fun `F20 continues past 6 and indicative departure even on red HP`() {
        val r = request()
        val plan = engine.calculate(r, calendar()).optimized
        assertTrue(plan.complete)
        assertTrue(plan.completion!! > r.departure)
        assertTrue(plan.slots.any { it.price.color == TempoColor.RED && it.price.period == Period.HP })
        assertEquals(40.0, plan.gridKwh, 1e-8)
        assertEquals(80.0, plan.estimatedSoc, 1e-8)
        assertEquals(18.4 * .10 + 21.6 * .70, plan.costEuros, 1e-8)
        assertEquals(21.6 * .70, plan.hpCostEuros, 1e-8)
        assertEquals(21.6 * (.70 - .14), plan.hpPremiumEuros, 1e-8)
    }
    @Test fun `actual absence ends supply and marks target incomplete`() {
        val r = request().let { it.copy(availability = listOf(Availability(it.start, it.departure))) }
        val plan = engine.calculate(r, calendar()).optimized
        assertFalse(plan.complete)
        assertNull(plan.completion)
        assertTrue(plan.slots.all { it.end <= r.departure })
        assertEquals(17.0, plan.missingGridKwh, 1e-8)
    }
    @Test fun `already above target produces no slots and preserves battery`() {
        val result = engine.calculate(request().copy(batteryPercent = 90.0), calendar())
        assertEquals(PlanStatus.TARGET_REACHED, result.status)
        assertEquals(90.0, result.optimized.estimatedSoc, 0.0)
        assertTrue(result.optimized.slots.isEmpty())
    }
    @Test fun `unknown future is conservatively priced without inventing red`() {
        val result = engine.calculate(request(), calendar(mapOf(day to TempoColor.BLUE))).optimized
        assertTrue(result.conservative)
        val future = result.slots.first { it.price.period == Period.HP }
        assertNull(future.price.color)
        assertEquals(.70, future.price.eurosPerKwh, 0.0)
    }
    @Test fun `color persists across midnight and changes exactly at 6`() {
        val c = calendar()
        assertEquals(TempoColor.BLUE, c.price(at(day.plusDays(1), 0)).color)
        assertEquals(TempoColor.BLUE, c.price(at(day.plusDays(1), 6).minusNanos(1)).color)
        assertEquals(TempoColor.RED, c.price(at(day.plusDays(1), 6)).color)
        assertEquals(Period.HP, c.price(at(hour = 22).minusNanos(1)).period)
        assertEquals(Period.HC, c.price(at(hour = 22)).period)
        assertEquals(Period.HP, c.price(at(hour = 6)).period)
    }
    @Test fun `six tariff combinations used correctly`() {
        for (color in TempoColor.entries) {
            val c = calendar(mapOf(day to color))
            assertEquals(rates.getValue(RateKey(color, Period.HP)), c.price(at(hour = 12)).eurosPerKwh, 0.0)
            assertEquals(rates.getValue(RateKey(color, Period.HC)), c.price(at(hour = 23)).eurosPerKwh, 0.0)
        }
    }
    @Test fun `spring night has seven real hours and autumn nine`() {
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 28) to 7, LocalDate.of(2026, 10, 24) to 9)) {
            val start = at(date, 22); val end = at(date.plusDays(1), 6)
            val r = request().copy(start = start, horizon = end, availability = listOf(Availability(start, end)))
            val p = engine.calculate(r, calendar(mapOf(date to TempoColor.BLUE))).optimized
            assertEquals(hours * 2.3, p.gridKwh, 1e-8)
            assertTrue(p.slots.all { it.price.period == Period.HC })
            assertEquals(hours * 2.3 * .10, p.costEuros, 1e-8)
        }
    }
    @Test fun `tariff version changes at civil midnight without changing color`() {
        val c = TempoCalendar(mapOf(day to TempoColor.BLUE), listOf(
            tariff(until = day.plusDays(1)), tariff(from = day.plusDays(1)).copy(id = "new", eurosPerKwh = rates.mapValues { it.value * 2 })))
        val r = request().copy(batteryPercent = 0.0)
        val result = engine.calculate(r, c).optimized
        assertTrue(result.slots.any { it.price.tariffId == "test" })
        assertTrue(result.slots.any { it.price.tariffId == "new" && it.price.color == TempoColor.BLUE })
        assertEquals(.20, c.price(at(day.plusDays(1), 0)).eurosPerKwh, 0.0)
    }
    @Test fun `missing expired and overlapping tariffs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { engine.calculate(request(), TempoCalendar(emptyMap(), emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { engine.calculate(request(), TempoCalendar(emptyMap(), listOf(tariff(until = day)))) }
        assertThrows(IllegalArgumentException::class.java) { TempoCalendar(emptyMap(), listOf(tariff(), tariff())) }
        assertThrows(IllegalArgumentException::class.java) { tariff().copy(eurosPerKwh = emptyMap()) }
    }
    @Test fun `cheaper later slots are preferred with no fictitious deadline at departure`() {
        val r = request().copy(start = at(hour = 18), batteryPercent = 75.0,
            availability = listOf(Availability(at(hour = 18), at(day.plusDays(1), 18))))
        val result = engine.calculate(r, calendar())
        assertEquals(at(hour = 22), result.optimized.slots.first().start)
        assertEquals(at(hour = 18), result.fallback.slots.first().start)
        assertTrue(result.optimized.costEuros < result.fallback.costEuros)
        assertFalse(result.executionConfirmed)
    }
    @Test fun `multi window optimization has single connected window fallback`() {
        val r = request().copy(horizon = at(day.plusDays(2), 6), availability = listOf(
            Availability(at(hour = 22), at(day.plusDays(1), 6)),
            Availability(at(day.plusDays(1), 22), at(day.plusDays(2), 6))))
        val result = engine.calculate(r, calendar())
        assertTrue(result.requiresMultipleWindows)
        assertTrue(result.fallback.missingGridKwh > result.optimized.missingGridKwh)
        assertTrue(result.fallback.slots.all { it.end <= at(day.plusDays(1), 6) })
        assertFalse(result.executionConfirmed)
    }
    @Test fun `adjacent availability windows do not break continuous fallback`() {
        val r = request().copy(availability = listOf(Availability(at(hour = 22), at(day.plusDays(1), 6)),
            Availability(at(day.plusDays(1), 6), at(day.plusDays(1), 18))))
        assertTrue(engine.calculate(r, calendar()).fallback.complete)
    }
    @Test fun `lower measured power is used and high power cannot exceed current cap`() {
        assertEquals(2.3, request().copy(measuredGridKw = 7.0).gridKw, 1e-8)
        val r = request().copy(measuredGridKw = 1.0)
        val p = engine.calculate(r, calendar()).optimized
        assertEquals(20.0, p.gridKwh, 1e-8)
        assertFalse(p.complete)
    }
    @Test fun `invalid currents efficiencies capacity and overlapping windows are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { request().copy(currentAmps = 11) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(efficiency = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(capacityKwh = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(batteryPercent = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { request().copy(availability = request().availability + request().availability) }
    }
    @Test fun `manual schedule away and unavailable never produce executable proposals`() {
        for ((r, status) in listOf(request().copy(manualSchedule = true) to PlanStatus.MANUAL_CONFLICT,
            request().copy(atHome = false) to PlanStatus.AWAY_FROM_HOME,
            request().copy(availability = emptyList()) to PlanStatus.UNAVAILABLE)) {
            val result = engine.calculate(r, calendar())
            assertEquals(status, result.status)
            assertTrue(result.optimized.slots.isEmpty())
            assertFalse(result.executionConfirmed)
        }
    }
    @Test fun `allocation conserves energy respects windows and costs no more than continuous baseline`() {
        for (soc in 0..79 step 7) for (amps in 1..10) {
            val r = request().copy(batteryPercent = soc.toDouble(), currentAmps = amps)
            val result = engine.calculate(r, calendar())
            val p = result.optimized
            assertEquals(r.neededGridKwh, p.gridKwh + p.missingGridKwh, 1e-7)
            assertTrue(p.costEuros <= result.fallback.costEuros + 1e-7)
            assertTrue(p.slots.zipWithNext().all { (a, b) -> a.end <= b.start })
            assertTrue(p.slots.all { it.start >= r.start && it.end <= r.horizon && it.start < it.end })
        }
    }
}
