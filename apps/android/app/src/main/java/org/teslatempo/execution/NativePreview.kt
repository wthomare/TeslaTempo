package org.teslatempo.execution

import org.teslatempo.planner.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class PlanChoice { ECONOMIC, FALLBACK }
enum class NativePreviewIssue(val label: String) {
    NO_CHARGE("Aucune recharge à programmer."),
    BLOCKED_CALCULATION("Le calcul ne permet pas de proposer un programme."),
    MULTIPLE_WINDOWS("Plusieurs fenêtres : traduction native non validée. Examinez le secours séparément."),
    STALE("Le calcul a plus de deux minutes : recalculez."),
    UNKNOWN_PRICE("Une couleur inconnue impose une estimation prudente."),
    INCOMPLETE("La cible ne peut pas être atteinte dans la disponibilité retenue."),
    CLOCK_CHANGE("Un changement d’heure exige une validation spécifique du programme."),
    TOO_FAR("Le démarrage est trop éloigné pour ce parcours ponctuel (maximum 18 heures)."),
    OUTSIDE_POLICY("Ce parcours F20 limite la cible à 80 % et le courant à 10 A."),
    NOT_CURRENT_CONNECTION("Cette disponibilité suppose un nouveau branchement : recalculez au retour."),
}
data class NativePreview(
    val choice: PlanChoice, val start: Instant?, val estimate: PlanEstimate?,
    val request: ChargingRequest?, val issues: Set<NativePreviewIssue>,
) {
    val representable: Boolean get() = start != null && estimate != null &&
        issues.all { it == NativePreviewIssue.UNKNOWN_PRICE }
}

/** Pure preview: no VIN, coordinates, schedule ID, payload or network side effects. */
fun previewNative(request: ChargingRequest, comparison: PlanComparison, calendar: TempoCalendar,
    calculatedAt: Instant, now: Instant, choice: PlanChoice): NativePreview {
    fun blocked(issue: NativePreviewIssue) = NativePreview(choice, null, null, null, setOf(issue))
    if (comparison.status == PlanStatus.TARGET_REACHED) return blocked(NativePreviewIssue.NO_CHARGE)
    if (comparison.status != PlanStatus.DRAFT || comparison.executionConfirmed || !request.atHome || request.manualSchedule) return blocked(NativePreviewIssue.BLOCKED_CALCULATION)
    if (now < calculatedAt || Duration.between(calculatedAt, now) >= Duration.ofMinutes(2)) return blocked(NativePreviewIssue.STALE)
    if (request.currentAmps > 10 || request.targetPercent > 80) return blocked(NativePreviewIssue.OUTSIDE_POLICY)
    val selected = if (choice == PlanChoice.ECONOMIC) comparison.optimized else comparison.fallback
    if (selected.slots.isEmpty()) return blocked(NativePreviewIssue.NO_CHARGE)
    if (selected.slots.zipWithNext().any { (a,b) -> a.end != b.start }) return blocked(NativePreviewIssue.MULTIPLE_WINDOWS)
    val connection = request.availability.sortedBy { it.start }.fold(mutableListOf<Availability>()) { windows, next ->
        if (windows.isNotEmpty() && windows.last().end == next.start) {
            val previous = windows.removeAt(windows.lastIndex); windows += Availability(previous.start, next.end)
        } else windows += next
        windows
    }.find { it.start <= now && it.end > now } ?: return blocked(NativePreviewIssue.NOT_CURRENT_CONNECTION)
    val desired = selected.slots.first().start
    if (desired >= connection.end) return blocked(NativePreviewIssue.NOT_CURRENT_CONNECTION)
    // Leave two minutes for a future review/exchange; never silently backdate a program.
    val earliest = now.plusSeconds(120)
    val candidate = maxOf(desired, earliest)
    val rounded = candidate.truncatedTo(ChronoUnit.MINUTES).let { if (it == candidate) it else it.plusSeconds(60) }
    if (rounded >= minOf(connection.end, request.horizon)) return blocked(NativePreviewIssue.INCOMPLETE)
    if (Duration.between(now, rounded) > Duration.ofHours(18)) return blocked(NativePreviewIssue.TOO_FAR)
    val adjusted = request.copy(start = rounded, availability = listOf(Availability(rounded, minOf(connection.end, request.horizon))))
    // Recompute a continuous charge after rounding. Never retain the old optimized cost.
    val estimate = ChargingOptimizer().calculate(adjusted, calendar).fallback
    val issues = mutableSetOf<NativePreviewIssue>()
    if (!estimate.complete) issues += NativePreviewIssue.INCOMPLETE
    if (estimate.conservative) issues += NativePreviewIssue.UNKNOWN_PRICE
    val transition = ZoneId.of("Europe/Paris").rules.nextTransition(now.minusNanos(1))
    if (transition != null && transition.instant <= (estimate.completion ?: adjusted.horizon)) issues += NativePreviewIssue.CLOCK_CHANGE
    return NativePreview(choice, rounded, estimate, adjusted, issues)
}
