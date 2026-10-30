package org.teslatempo.execution

import org.teslatempo.planner.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Offline policy only. No transport, Tesla payload, or claim of execution support. */
enum class PreparationBlocker {
    NO_PLAN, INCOMPLETE_TARGET, STALE_CALCULATION, STALE_VEHICLE, VEHICLE_CHANGED,
    NOT_CONNECTED_AT_HOME, EXISTING_SCHEDULE, CAPABILITY_UNVERIFIED, CLOCK_CHANGE,
    CURRENT_OR_TARGET_OUTSIDE_POLICY, SLOT_ALREADY_STARTED, INVALID_ESTIMATE,
}
data class VehicleEvidence(
    val vehicleId: String, val observedAt: Instant, val batteryPercent: Double,
    val pluggedIn: Boolean, val atHome: Boolean, val online: Boolean,
    // null means not read, never "no schedules".
    val schedulesRevision: String?, val hasExistingSchedules: Boolean?,
    val signatureVerified: Boolean = false, val programmingVerified: Boolean = false,
)
data class Preparation(
    val fingerprint: String, val expiresAt: Instant, val blockers: Set<PreparationBlocker>,
) {
    val reviewable: Boolean get() = blockers.isEmpty()
}

fun prepareExecution(request: ChargingRequest, estimate: PlanEstimate, calculatedAt: Instant,
    evidence: VehicleEvidence, now: Instant): Preparation {
    val blockers = mutableSetOf<PreparationBlocker>()
    fun fresh(at: Instant) = at <= now && Duration.between(at, now) <= Duration.ofMinutes(2)
    if (!fresh(calculatedAt)) blockers += PreparationBlocker.STALE_CALCULATION
    if (!fresh(evidence.observedAt)) blockers += PreparationBlocker.STALE_VEHICLE
    if (evidence.vehicleId.isBlank() || !evidence.batteryPercent.isFinite() || evidence.batteryPercent !in 0.0..100.0 ||
        kotlin.math.abs(evidence.batteryPercent - request.batteryPercent) > 1.0) blockers += PreparationBlocker.VEHICLE_CHANGED
    if (!request.atHome || !evidence.atHome || !evidence.pluggedIn || !evidence.online) blockers += PreparationBlocker.NOT_CONNECTED_AT_HOME
    if (request.manualSchedule || evidence.hasExistingSchedules != false || evidence.schedulesRevision.isNullOrBlank())
        blockers += PreparationBlocker.EXISTING_SCHEDULE
    if (!evidence.signatureVerified || !evidence.programmingVerified) blockers += PreparationBlocker.CAPABILITY_UNVERIFIED
    if (request.currentAmps !in 1..10 || request.targetPercent > 80.0) blockers += PreparationBlocker.CURRENT_OR_TARGET_OUTSIDE_POLICY
    if (estimate.slots.isEmpty()) blockers += PreparationBlocker.NO_PLAN
    if (!estimate.complete) blockers += PreparationBlocker.INCOMPLETE_TARGET
    val zone = ZoneId.of("Europe/Paris")
    estimate.slots.forEach { slot ->
        if (slot.start <= now) blockers += PreparationBlocker.SLOT_ALREADY_STARTED
        if (slot.start >= slot.end || slot.start < request.start || slot.end > request.horizon ||
            !slot.gridKwh.isFinite() || slot.gridKwh <= 0 ||
            slot.gridKwh > Duration.between(slot.start, slot.end).toNanos() / 3_600_000_000_000.0 * request.gridKw + 1e-6 ||
            !slot.price.eurosPerKwh.isFinite() || slot.price.eurosPerKwh < 0 ||
            request.availability.none { slot.start >= it.start && slot.end <= it.end }) blockers += PreparationBlocker.INVALID_ESTIMATE
        val transition = zone.rules.nextTransition(slot.start.minusNanos(1))
        if (transition != null && transition.instant < slot.end) blockers += PreparationBlocker.CLOCK_CHANGE
    }
    if (estimate.slots.zipWithNext().any { (a,b) -> a.end > b.start } ||
        !estimate.missingGridKwh.isFinite() || estimate.missingGridKwh < 0 ||
        !estimate.estimatedSoc.isFinite() || estimate.estimatedSoc !in 0.0..100.0 ||
        kotlin.math.abs(estimate.estimatedSoc - minOf(request.targetPercent, request.batteryPercent + estimate.gridKwh * request.efficiency / request.capacityKwh * 100)) > 1e-6 ||
        kotlin.math.abs(estimate.gridKwh + estimate.missingGridKwh - request.neededGridKwh) > 1e-6)
        blockers += PreparationBlocker.INVALID_ESTIMATE
    // Binary encoding avoids ambiguity from separators and binds costs as well as physical settings.
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeUTF("teslatempo-preparation-v1")
        out.writeUTF(evidence.vehicleId); out.writeUTF(evidence.observedAt.toString())
        out.writeDouble(evidence.batteryPercent); out.writeUTF(evidence.schedulesRevision.orEmpty())
        out.writeUTF(calculatedAt.toString()); out.writeUTF(request.start.toString()); out.writeUTF(request.horizon.toString())
        out.writeUTF(request.departure.toString()); out.writeDouble(request.batteryPercent)
        out.writeDouble(request.targetPercent); out.writeDouble(request.capacityKwh); out.writeDouble(request.efficiency)
        out.writeInt(request.currentAmps); out.writeDouble(request.gridKw)
        out.writeInt(estimate.slots.size)
        estimate.slots.forEach {
            out.writeUTF(it.start.toString()); out.writeUTF(it.end.toString()); out.writeDouble(it.gridKwh)
            out.writeDouble(it.price.eurosPerKwh); out.writeUTF(it.price.tariffId)
            out.writeUTF(it.price.color?.name ?: "UNKNOWN"); out.writeUTF(it.price.period.name)
        }
        out.writeDouble(estimate.missingGridKwh); out.writeDouble(estimate.estimatedSoc)
        out.writeUTF(blockers.map { it.name }.sorted().joinToString(","))
    }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    return Preparation(fingerprint, minOf(calculatedAt, evidence.observedAt).plusSeconds(120), blockers)
}

/** A local confirmation is not a server authorization. The relay must repeat all checks. */
class ConfirmationGate {
    private var approved: String? = null
    fun confirm(preview: Preparation, now: Instant) {
        require(preview.reviewable && now < preview.expiresAt)
        approved = preview.fingerprint
    }
    fun consume(current: Preparation, now: Instant): Boolean {
        val accepted = current.reviewable && now < current.expiresAt && approved == current.fingerprint
        approved = null // Invalidated on both success and mismatch; never automatically retry.
        return accepted
    }
    fun cancel() { approved = null }
}

enum class ApplicationOutcome { NOT_SENT, AWAITING_READBACK, APPLIED_CONFIRMED, UNKNOWN_OUTCOME, REJECTED }
class ExecutionOutcome {
    var state = ApplicationOutcome.NOT_SENT
        private set
    fun acknowledged() { check(state == ApplicationOutcome.NOT_SENT); state = ApplicationOutcome.AWAITING_READBACK }
    fun timedOut() { check(state in setOf(ApplicationOutcome.NOT_SENT, ApplicationOutcome.AWAITING_READBACK)); state = ApplicationOutcome.UNKNOWN_OUTCOME }
    fun rejected() { check(state == ApplicationOutcome.NOT_SENT); state = ApplicationOutcome.REJECTED }
    fun readBack(matches: Boolean) {
        check(state in setOf(ApplicationOutcome.AWAITING_READBACK, ApplicationOutcome.UNKNOWN_OUTCOME))
        state = if (matches) ApplicationOutcome.APPLIED_CONFIRMED else ApplicationOutcome.UNKNOWN_OUTCOME
    }
}
