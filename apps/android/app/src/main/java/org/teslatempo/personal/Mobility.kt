package org.teslatempo.personal

import org.teslatempo.planner.Availability
import java.time.*

enum class DayKind(val label: String) {
    OFFICE("Bureau"), REMOTE("Télétravail"), WEEKEND("Week-end à domicile"), VACATION("Vacances / absent"), CUSTOM("Absence personnalisée"), HOME("Domicile")
}
data class DaySchedule(val kind: DayKind = DayKind.HOME, val leave: String = "08:00", val back: String = "18:00") {
    fun validate() {
        require(kind in DayKind.entries) { "Profil de journée inconnu." }
        LocalTime.parse(leave); LocalTime.parse(back)
        require(leave.matches(Regex("\\d{2}:\\d{2}")) && back.matches(Regex("\\d{2}:\\d{2}"))) { "Horaires attendus au format HH:mm." }
        if (kind == DayKind.OFFICE || kind == DayKind.CUSTOM) require(leave != back) { "Départ et retour doivent différer." }
    }
}
data class ScheduleException(val from: String, val until: String, val day: DaySchedule) {
    fun validate() {
        require(from.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && until.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Dates attendues au format AAAA-MM-JJ." }
        require(LocalDate.parse(from) <= LocalDate.parse(until)) { "La fin de l’exception précède son début." }
        day.validate()
    }
}
data class PersonalSettings(
    val confirmed: Boolean = false,
    val capacityKwh: Double = 60.0, val targetPercent: Double = 80.0,
    val currentAmps: Int = 10, val efficiency: Double = .9,
    val indicativeDeparture: String = "08:00",
    val week: List<DaySchedule> = List(7) { if (it < 5) DaySchedule(DayKind.OFFICE) else DaySchedule(DayKind.WEEKEND) },
    val exceptions: List<ScheduleException> = emptyList(),
) {
    fun validate(): PersonalSettings {
        require(capacityKwh.isFinite() && capacityKwh in 1.0..300.0) { "Capacité attendue entre 1 et 300 kWh." }
        require(targetPercent.isFinite() && targetPercent in 1.0..100.0) { "Cible attendue entre 1 et 100 %." }
        require(currentAmps in 1..10) { "Courant attendu entre 1 et 10 A." }
        require(efficiency.isFinite() && efficiency in .1..1.0) { "Rendement attendu entre 10 et 100 %." }
        require(indicativeDeparture.matches(Regex("\\d{2}:\\d{2}"))) { "Départ indicatif attendu au format HH:mm." }
        LocalTime.parse(indicativeDeparture)
        require(week.size == 7) { "La semaine doit comporter sept jours." }
        week.forEach { it.validate() }
        require(exceptions.size <= 100) { "Maximum 100 exceptions." }
        exceptions.forEach { it.validate() }
        require(exceptions.sortedBy { it.from }.zipWithNext().all { (a, b) -> a.until < b.from }) { "Les exceptions ne doivent pas se chevaucher." }
        return this
    }
    fun schedule(date: LocalDate) = exceptions.firstOrNull { date >= LocalDate.parse(it.from) && date <= LocalDate.parse(it.until) }?.day
        ?: week[date.dayOfWeek.value - 1]
}

/** Convert local absences to UTC, then subtract their union. Overnight absences include yesterday. */
fun availability(settings: PersonalSettings, start: Instant, end: Instant): List<Availability> {
    settings.validate()
    require(start < end && Duration.between(start, end) <= Duration.ofDays(14))
    val zone = ZoneId.of("Europe/Paris")
    val absent = mutableListOf<Availability>()
    var date = start.atZone(zone).toLocalDate().minusDays(1)
    val last = end.atZone(zone).toLocalDate()
    while (date <= last) {
        val day = settings.schedule(date)
        fun time(d: LocalDate, t: LocalTime, later: Boolean = false): Instant {
            val z = d.atTime(t).atZone(zone)
            return (if (later) z.withLaterOffsetAtOverlap() else z.withEarlierOffsetAtOverlap()).toInstant()
        }
        val interval = when (day.kind) {
            DayKind.VACATION -> Availability(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
            DayKind.OFFICE, DayKind.CUSTOM -> {
                val leave = LocalTime.parse(day.leave); val back = LocalTime.parse(day.back)
                val a = time(date, leave)
                val b = time(if (back < leave) date.plusDays(1) else date, back, true)
                require(a < b) { "Horaires invalides lors du changement d’heure : adaptez l’exception." }
                Availability(a, b)
            }
            else -> null
        }
        if (interval != null && interval.end > start && interval.start < end)
            absent += Availability(maxOf(start, interval.start), minOf(end, interval.end))
        date = date.plusDays(1)
    }
    val result = mutableListOf<Availability>()
    var cursor = start
    for (gap in absent.sortedBy { it.start }) {
        if (gap.start > cursor) result += Availability(cursor, gap.start)
        cursor = maxOf(cursor, gap.end)
    }
    if (cursor < end) result += Availability(cursor, end)
    return result
}
