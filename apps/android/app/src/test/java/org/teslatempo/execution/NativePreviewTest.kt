package org.teslatempo.execution

import org.junit.Assert.*
import org.junit.Test
import org.teslatempo.planner.*
import java.time.*

class NativePreviewTest {
    private fun fixture(day: String = "2026-11-02", soc: Double = 70.0) = demoSimulation(LocalDate.parse(day), soc, 60.0, 10, DemoScenario.HOME)
    @Test fun `continuous preview recalculates after rounding without imposing an end time`() {
        val f=fixture(); val r=ChargingOptimizer().calculate(f.request,f.calendar)
        val p=previewNative(f.request,r,f.calendar,f.request.start,f.request.start,PlanChoice.ECONOMIC)
        assertTrue(p.representable)
        assertEquals(f.request.start.plusSeconds(120),p.start)
        assertEquals(f.request.neededGridKwh,p.estimate!!.gridKwh,1e-6)
        assertTrue(p.estimate!!.complete)
    }
    @Test fun `a stale draft cannot bypass newly discovered home or manual conflict`() {
        val f=fixture();val r=ChargingOptimizer().calculate(f.request,f.calendar)
        for(request in listOf(f.request.copy(atHome=false),f.request.copy(manualSchedule=true))) {
            assertEquals(setOf(NativePreviewIssue.BLOCKED_CALCULATION),previewNative(request,r,f.calendar,f.request.start,f.request.start,PlanChoice.ECONOMIC).issues)
        }
    }
    @Test fun `unknown colors remain warnings not known predictions`() {
        val f=demoSimulation(LocalDate.parse("2026-11-02"),20.0,60.0,10,DemoScenario.UNKNOWN)
        val r=ChargingOptimizer().calculate(f.request,f.calendar)
        val p=previewNative(f.request,r,f.calendar,f.request.start,f.request.start,PlanChoice.FALLBACK)
        assertTrue(NativePreviewIssue.UNKNOWN_PRICE in p.issues)
    }
    @Test fun `multiple windows require an explicit fallback choice`() {
        val f=fixture(); val r=ChargingOptimizer().calculate(f.request,f.calendar)
        val slot=r.optimized.slots.first()
        val multi=r.copy(optimized=r.optimized.copy(slots=listOf(slot,slot.copy(start=slot.end.plusSeconds(3600),end=slot.end.plusSeconds(7200)))))
        val p=previewNative(f.request,multi,f.calendar,f.request.start,f.request.start,PlanChoice.ECONOMIC)
        assertEquals(setOf(NativePreviewIssue.MULTIPLE_WINDOWS),p.issues)
        assertTrue(previewNative(f.request,multi,f.calendar,f.request.start,f.request.start,PlanChoice.FALLBACK).representable)
    }
    @Test fun `stale calculations and missed connection windows are refused`() {
        val f=fixture(); val r=ChargingOptimizer().calculate(f.request,f.calendar)
        assertEquals(setOf(NativePreviewIssue.STALE),previewNative(f.request,r,f.calendar,f.request.start,f.request.start.plusSeconds(120),PlanChoice.ECONOMIC).issues)
        val future=f.request.copy(availability=f.request.availability.map { Availability(it.start.plusSeconds(600),it.end) })
        assertEquals(setOf(NativePreviewIssue.NOT_CURRENT_CONNECTION),previewNative(future,r,f.calendar,f.request.start,f.request.start,PlanChoice.ECONOMIC).issues)
    }
    @Test fun `DST inside the overnight charging window remains blocked`() {
        val f=fixture("2026-10-24",20.0);val r=ChargingOptimizer().calculate(f.request,f.calendar)
        val p=previewNative(f.request,r,f.calendar,f.request.start,f.request.start,PlanChoice.FALLBACK)
        assertTrue(NativePreviewIssue.CLOCK_CHANGE in p.issues)
        assertFalse(p.representable)
    }
}
