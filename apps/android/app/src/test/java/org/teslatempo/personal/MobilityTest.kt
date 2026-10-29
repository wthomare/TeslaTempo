package org.teslatempo.personal

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MobilityTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant()
    private val monday = at("2026-11-02T00:00")
    private val tuesday = at("2026-11-03T00:00")

    @Test fun `office removes actual absence and home remains available`() {
        val p = PersonalSettings(confirmed = true, capacityKwh = 75.0)
        val windows = availability(p, monday, tuesday)
        assertEquals(2, windows.size)
        assertEquals(at("2026-11-02T08:00"), windows[0].end)
        assertEquals(at("2026-11-02T18:00"), windows[1].start)
        val remote = p.copy(week = List(7) { DaySchedule(DayKind.REMOTE) })
        assertEquals(1, availability(remote, monday, tuesday).size)
    }
    @Test fun `dated exception overrides week and inclusive vacation ends next midnight`() {
        val p = PersonalSettings(exceptions = listOf(ScheduleException("2026-11-02", "2026-11-03", DaySchedule(DayKind.VACATION))))
        assertTrue(availability(p, monday, tuesday).isEmpty())
        assertEquals(DayKind.VACATION, p.schedule(LocalDate.parse("2026-11-03")).kind)
        assertEquals(DayKind.OFFICE, p.schedule(LocalDate.parse("2026-11-04")).kind)
        val home = p.copy(exceptions = listOf(ScheduleException("2026-11-02", "2026-11-02", DaySchedule(DayKind.HOME))))
        assertEquals(24, Duration.between(availability(home, monday, tuesday).single().start, tuesday).toHours())
    }
    @Test fun `overnight absence from previous day is honored`() {
        val p = PersonalSettings(week = List(7) { DaySchedule(DayKind.CUSTOM, "22:00", "07:00") })
        val w = availability(p, monday, tuesday)
        assertEquals(1, w.size)
        assertEquals(at("2026-11-02T07:00"), w.single().start)
        assertEquals(at("2026-11-02T22:00"), w.single().end)
    }
    @Test fun `all-day availability follows DST and ambiguous absence is conservative`() {
        val p = PersonalSettings(week = List(7) { DaySchedule(DayKind.HOME) })
        val spring = availability(p, at("2026-03-29T00:00"), at("2026-03-30T00:00")).single()
        val autumn = availability(p, at("2026-10-25T00:00"), at("2026-10-26T00:00")).single()
        assertEquals(23, Duration.between(spring.start, spring.end).toHours())
        assertEquals(25, Duration.between(autumn.start, autumn.end).toHours())
        val q = p.copy(exceptions = listOf(ScheduleException("2026-10-25", "2026-10-25", DaySchedule(DayKind.CUSTOM, "02:15", "02:45"))))
        val windows = availability(q, autumn.start, autumn.end)
        assertEquals(90, Duration.between(windows[0].end, windows[1].start).toMinutes())
    }
    @Test fun `overlapping exceptions invalid fields and equal hours rejected`() {
        val e = ScheduleException("2026-11-02", "2026-11-03", DaySchedule())
        assertThrows(IllegalArgumentException::class.java) { PersonalSettings(exceptions = listOf(e, e)).validate() }
        assertThrows(IllegalArgumentException::class.java) { PersonalSettings(currentAmps = 11).validate() }
        assertThrows(IllegalArgumentException::class.java) { PersonalSettings(capacityKwh = Double.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { DaySchedule(DayKind.OFFICE, "08:00", "08:00").validate() }
        assertThrows(Exception::class.java) { DaySchedule(leave = "25:00").validate() }
    }
    @Test fun `profile codec retains week exceptions and confirmation with no secret`() {
        val p = PersonalSettings(confirmed = true, capacityKwh = 72.0,
            exceptions = listOf(ScheduleException("2026-12-24", "2027-01-02", DaySchedule(DayKind.VACATION))))
        assertEquals(p, PersonalCodec.settings(PersonalCodec.encode(p)))
        assertThrows(Exception::class.java) { PersonalCodec.settings("{\"schema\":99,\"data\":{}}") }
        assertThrows(Exception::class.java) { PersonalCodec.settings("garbage") }
        assertThrows(Exception::class.java) { PersonalCodec.settings(PersonalCodec.encode(p).replace("72.0", "-1")) }
    }
}
