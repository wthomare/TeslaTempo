package org.teslatempo.execution

import org.junit.Assert.*
import org.junit.Test
import org.teslatempo.planner.*
import java.time.Instant

class PreparationTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")
    private val request = ChargingRequest(now, now.plusSeconds(86400), now.plusSeconds(86400), 50.0, 51.0, 60.0,
        currentAmps = 10, availability = listOf(Availability(now, now.plusSeconds(86400))))
    private val estimate = PlanEstimate(listOf(ChargeSlot(now.plusSeconds(3600), now.plusSeconds(7200), request.neededGridKwh,
        Price(TempoColor.BLUE, Period.HC, 0.15, 0.15, "synthetic", "test"))), 0.0, 51.0)
    private val evidence = VehicleEvidence("synthetic-id", now, 50.0, true, true, true, "revision-1", false, true, true)
    private fun prepare(r: ChargingRequest = request, e: PlanEstimate = estimate, v: VehicleEvidence = evidence, at: Instant = now) =
        prepareExecution(r,e,now,v,at)
    @Test fun `confirmation bound to reviewed cost vehicle and schedule revision is single use`() {
        val p = prepare(); assertTrue(p.reviewable)
        val gate = ConfirmationGate(); gate.confirm(p,now)
        assertTrue(gate.consume(p,now)); assertFalse(gate.consume(p,now))
        for (changed in listOf(prepare(v=evidence.copy(vehicleId="other")), prepare(v=evidence.copy(schedulesRevision="revision-2")),
            prepare(e=estimate.copy(slots=estimate.slots.map { it.copy(price=it.price.copy(eurosPerKwh=0.30)) })))) {
            gate.confirm(p,now); assertFalse(gate.consume(changed,now)); assertFalse(gate.consume(p,now))
        }
    }
    @Test fun `stale unknown or unsafe evidence prevents confirmation`() {
        val variants = listOf(evidence.copy(observedAt=now.minusSeconds(121)), evidence.copy(batteryPercent=40.0),
            evidence.copy(pluggedIn=false), evidence.copy(atHome=false), evidence.copy(online=false),
            evidence.copy(hasExistingSchedules=null), evidence.copy(hasExistingSchedules=true),
            evidence.copy(schedulesRevision=null), evidence.copy(signatureVerified=false), evidence.copy(programmingVerified=false))
        variants.forEach { v -> assertFalse(prepare(v=v).reviewable) }
        assertFalse(prepare(at=now.plusSeconds(121)).reviewable)
        assertThrows(IllegalArgumentException::class.java) { ConfirmationGate().confirm(prepare(), now.plusSeconds(120)) }
    }
    @Test fun `incomplete or started plan cannot be consumed`() {
        assertFalse(prepare(e=estimate.copy(missingGridKwh=1.0)).reviewable)
        assertFalse(prepare(e=estimate.copy(slots=estimate.slots.map { it.copy(start=now) })).reviewable)
        val gate = ConfirmationGate(); gate.confirm(prepare(), now); gate.cancel(); assertFalse(gate.consume(prepare(), now))
    }
    @Test fun `clock transition requires adapter validation and never silently maps repeated hour`() {
        val t = Instant.parse("2026-10-25T00:00:00Z")
        val r = request.copy(start=t,horizon=t.plusSeconds(86400),departure=t.plusSeconds(86400),availability=listOf(Availability(t,t.plusSeconds(86400))))
        val e = estimate.copy(slots=estimate.slots.map { it.copy(start=t.plusSeconds(1800),end=t.plusSeconds(7200)) })
        val p = prepareExecution(r,e,t,evidence.copy(observedAt=t),t)
        assertTrue(PreparationBlocker.CLOCK_CHANGE in p.blockers)
    }
    @Test fun `acknowledgement or timeout is never proof of application`() {
        val out = ExecutionOutcome(); out.acknowledged()
        assertEquals(ApplicationOutcome.AWAITING_READBACK, out.state)
        out.timedOut(); assertEquals(ApplicationOutcome.UNKNOWN_OUTCOME,out.state)
        out.readBack(false); assertEquals(ApplicationOutcome.UNKNOWN_OUTCOME,out.state)
        out.readBack(true); assertEquals(ApplicationOutcome.APPLIED_CONFIRMED,out.state)
        assertThrows(IllegalStateException::class.java) { out.acknowledged() }
        assertThrows(IllegalStateException::class.java) { ExecutionOutcome().readBack(true) }
    }
}
