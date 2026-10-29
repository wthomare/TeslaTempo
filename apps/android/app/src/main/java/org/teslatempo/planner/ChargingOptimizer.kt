package org.teslatempo.planner

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class Availability(val start: Instant, val end: Instant) {
    init { require(start < end) { "Une disponibilité doit avoir une durée positive." } }
}
data class ChargingRequest(
    val start: Instant, val horizon: Instant, val departure: Instant,
    val batteryPercent: Double, val targetPercent: Double = 80.0,
    val capacityKwh: Double, val efficiency: Double = 0.90,
    val currentAmps: Int = 10, val measuredGridKw: Double? = null,
    val availability: List<Availability>, val atHome: Boolean = true,
    val manualSchedule: Boolean = false,
) {
    init {
        require(start < horizon && Duration.between(start, horizon) <= Duration.ofDays(14)) { "L’horizon doit durer entre 0 et 14 jours." }
        require(batteryPercent.isFinite() && batteryPercent in 0.0..100.0 && targetPercent.isFinite() && targetPercent in 0.0..100.0) { "Les niveaux de batterie doivent être compris entre 0 et 100 %." }
        require(capacityKwh.isFinite() && capacityKwh > 0 && capacityKwh <= 300) { "Capacité de batterie invalide." }
        require(efficiency.isFinite() && efficiency > 0 && efficiency <= 1) { "Rendement invalide." }
        require(currentAmps in 1..10) { "Le courant doit rester entre 1 et 10 A." }
        require(measuredGridKw == null || measuredGridKw.isFinite() && measuredGridKw > 0) { "Puissance mesurée invalide." }
        require(availability.sortedBy { it.start }.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Les disponibilités ne doivent pas se chevaucher." }
    }
    // Single-phase 230 V model. Measured grid power can lower, never raise the current cap.
    val gridKw: Double get() = min(currentAmps * 0.230, measuredGridKw ?: Double.MAX_VALUE)
    val neededGridKwh: Double get() = max(0.0, targetPercent - batteryPercent) / 100 * capacityKwh / efficiency
}
data class ChargeSlot(val start: Instant, val end: Instant, val gridKwh: Double, val price: Price) {
    val costEuros: Double get() = gridKwh * price.eurosPerKwh
}
data class PlanEstimate(val slots: List<ChargeSlot>, val missingGridKwh: Double, val estimatedSoc: Double) {
    val costEuros: Double get() = slots.sumOf { it.costEuros }
    val gridKwh: Double get() = slots.sumOf { it.gridKwh }
    val hpCostEuros: Double get() = slots.filter { it.price.period == Period.HP }.sumOf { it.costEuros }
    // Comparison against HC of the SAME color/version; not a claim of achievable savings.
    val hpPremiumEuros: Double get() = slots.filter { it.price.period == Period.HP }
        .sumOf { it.gridKwh * max(0.0, it.price.eurosPerKwh - it.price.hcReference) }
    val complete: Boolean get() = missingGridKwh < 1e-7
    val completion: Instant? get() = if (complete) slots.lastOrNull()?.end else null
    val conservative: Boolean get() = slots.any { it.price.color == null }
}
enum class PlanStatus { DRAFT, TARGET_REACHED, AWAY_FROM_HOME, UNAVAILABLE, MANUAL_CONFLICT }
data class PlanComparison(
    val status: PlanStatus, val optimized: PlanEstimate, val fallback: PlanEstimate,
    val requiresMultipleWindows: Boolean,
    // Execution support is intentionally not inferred from a mathematical schedule.
    val executionConfirmed: Boolean = false,
)

class ChargingOptimizer {
    fun calculate(request: ChargingRequest, tempo: TempoCalendar): PlanComparison {
        fun empty(status: PlanStatus): PlanComparison {
            val p = PlanEstimate(emptyList(), request.neededGridKwh, request.batteryPercent)
            return PlanComparison(status, p, p, false)
        }
        if (request.neededGridKwh < 1e-7) return empty(PlanStatus.TARGET_REACHED)
        if (!request.atHome) return empty(PlanStatus.AWAY_FROM_HOME)
        if (request.manualSchedule) return empty(PlanStatus.MANUAL_CONFLICT)
        val windows = request.availability.mapNotNull {
            val from = maxOf(request.start, it.start)
            val to = minOf(request.horizon, it.end)
            if (from < to) Availability(from, to) else null
        }.sortedBy { it.start }
        if (windows.isEmpty()) return empty(PlanStatus.UNAVAILABLE)
        val candidates = windows.flatMap { window ->
            tempo.boundaries(window.start, window.end).zipWithNext().map { (a, b) ->
                ChargeSlot(a, b, hours(a, b) * request.gridKw, tempo.price(a))
            }
        }
        fun allocate(ordered: List<ChargeSlot>): PlanEstimate {
            var remaining = request.neededGridKwh
            val selected = mutableListOf<ChargeSlot>()
            for (slot in ordered) {
                if (remaining < 1e-9) break
                val energy = min(remaining, slot.gridKwh)
                val end = if (energy == slot.gridKwh) slot.end else
                    minOf(slot.end, slot.start.plusNanos(ceil(energy / request.gridKw * 3_600_000_000_000.0).toLong()))
                selected += slot.copy(end = end, gridKwh = energy)
                remaining = max(0.0, remaining - energy)
            }
            val chronological = selected.sortedBy { it.start }
            return PlanEstimate(chronological, remaining,
                min(request.targetPercent, request.batteryPercent + chronological.sumOf { it.gridKwh } * request.efficiency / request.capacityKwh * 100))
        }
        // Linear cost minimization within the explicit horizon; price ties go to the earliest slot.
        val optimized = allocate(candidates.sortedWith(compareBy<ChargeSlot> { it.price.eurosPerKwh }.thenBy { it.start }))
        // Safe proposal: start at first availability and charge continuously in this connected window.
        // It does not promise a restart after unplugging or after an absence.
        var continuousEnd = windows.first().end
        for (window in windows.drop(1)) {
            if (window.start != continuousEnd) break
            continuousEnd = window.end
        }
        val fallback = allocate(candidates.filter { it.start < continuousEnd })
        val multiple = optimized.slots.zipWithNext().any { (a, b) -> a.end != b.start }
        return PlanComparison(PlanStatus.DRAFT, optimized, fallback, multiple)
    }
}
private fun hours(start: Instant, end: Instant): Double = Duration.between(start, end).toNanos() / 3_600_000_000_000.0
