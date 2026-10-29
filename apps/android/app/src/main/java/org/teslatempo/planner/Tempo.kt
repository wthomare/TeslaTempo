package org.teslatempo.planner

import java.time.*

enum class TempoColor { BLUE, WHITE, RED }
enum class Period { HC, HP }
data class RateKey(val color: TempoColor, val period: Period)
data class TariffVersion(
    val id: String, val source: String,
    val validFrom: LocalDate, val validUntil: LocalDate, // inclusive/exclusive civil dates
    val eurosPerKwh: Map<RateKey, Double>,
) {
    init {
        require(id.isNotBlank() && source.isNotBlank() && validFrom < validUntil)
        require(eurosPerKwh.keys == TempoColor.entries.flatMap { c -> Period.entries.map { RateKey(c, it) } }.toSet())
        require(eurosPerKwh.values.all { it.isFinite() && it >= 0 })
    }
}
data class Price(
    val color: TempoColor?, val period: Period, val eurosPerKwh: Double,
    val hcReference: Double, val tariffId: String, val source: String,
)

class TempoCalendar(private val colors: Map<LocalDate, TempoColor>, tariffs: List<TariffVersion>) {
    val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val versions = tariffs.sortedBy { it.validFrom }
    init { require(versions.zipWithNext().all { (a, b) -> a.validUntil <= b.validFrom }) }
    fun price(at: Instant): Price {
        val local = at.atZone(zone)
        val day = if (local.hour < 6) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val period = if (local.hour < 6 || local.hour >= 22) Period.HC else Period.HP
        val tariff = versions.singleOrNull { local.toLocalDate() >= it.validFrom && local.toLocalDate() < it.validUntil }
            ?: throw IllegalArgumentException("Tarifs absents ou expirés pour le ${local.toLocalDate()}.")
        val color = colors[day]
        fun rate(p: Period) = color?.let { tariff.eurosPerKwh.getValue(RateKey(it, p)) }
            ?: TempoColor.entries.maxOf { tariff.eurosPerKwh.getValue(RateKey(it, p)) }
        // Unknown remains unknown. The maximum price is a conservative assumption, not a published color.
        return Price(color, period, rate(period), rate(Period.HC), tariff.id, tariff.source)
    }
    fun boundaries(start: Instant, end: Instant): List<Instant> {
        val points = mutableSetOf(start, end)
        var date = start.atZone(zone).toLocalDate()
        val last = end.atZone(zone).toLocalDate()
        while (date <= last) {
            for (hour in listOf(0, 6, 22)) {
                val point = date.atTime(hour, 0).atZone(zone).toInstant()
                if (point > start && point < end) points += point
            }
            date = date.plusDays(1)
        }
        return points.sorted()
    }
}
