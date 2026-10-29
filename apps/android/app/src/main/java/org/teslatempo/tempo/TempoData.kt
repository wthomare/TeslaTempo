package org.teslatempo.tempo

import org.teslatempo.planner.*
import org.teslatempo.planner.Period
import java.time.*

const val TEMPO_SOURCE = "https://www.calendrier-tempo.fr"
val PARIS: ZoneId = ZoneId.of("Europe/Paris")
data class ObservedColor(val date: String, val color: TempoColor?, val checkedAt: String)
data class PublishedTariff(val effectiveFrom: String, val rates: List<Double>, val checkedAt: String) {
    fun validate() {
        LocalDate.parse(effectiveFrom); Instant.parse(checkedAt)
        require(rates.size == 6 && rates.all { it.isFinite() && it > 0 && it < 10 })
    }
    fun version(now: Instant): TariffVersion {
        validate()
        val checked = Instant.parse(checkedAt)
        // A local revalidation deadline, NOT a provider-announced end of validity.
        val recheckBefore = checked.atZone(PARIS).toLocalDate().plusDays(7)
        require(now >= checked && now < recheckBefore.atStartOfDay(PARIS).toInstant()) { "Tarifs trop anciens : actualisez Tempo avant de calculer." }
        return TariffVersion("tempo-$effectiveFrom", "$TEMPO_SOURCE · TTC · contrôlé le $checkedAt",
            LocalDate.parse(effectiveFrom), recheckBefore, rateMap(rates))
    }
}
fun rateMap(rates: List<Double>) = TempoColor.entries.flatMap { c -> Period.entries.map { RateKey(c, it) } }.zip(rates).toMap()

data class TempoCache(val colors: List<ObservedColor> = emptyList(), val tariff: PublishedTariff? = null) {
    fun validate(): TempoCache {
        require(colors.size <= 20 && colors.map { it.date }.distinct().size == colors.size)
        colors.forEach { LocalDate.parse(it.date); Instant.parse(it.checkedAt) }
        tariff?.validate()
        return this
    }
    fun color(day: LocalDate): ObservedColor? = colors.find { it.date == day.toString() }
    fun calendar(now: Instant): TempoCalendar {
        val prices = tariff ?: throw IllegalArgumentException("Aucun tarif disponible. Actualisez Tempo.")
        return TempoCalendar(colors.mapNotNull { row -> row.color?.let { LocalDate.parse(row.date) to it } }.toMap(), listOf(prices.version(now)))
    }
    fun merge(update: TempoUpdate, now: Instant): TempoCache {
        val today = now.atZone(PARIS).toLocalDate()
        val combined = (colors.associateBy { it.date } + update.colors.associateBy { it.date }).values
            .filter { LocalDate.parse(it.date) in today.minusDays(7)..today.plusDays(1) }.sortedBy { it.date }
        return TempoCache(combined, update.tariff ?: tariff).validate()
    }
}
data class TempoUpdate(val colors: List<ObservedColor>, val tariff: PublishedTariff?, val errors: List<String>)
