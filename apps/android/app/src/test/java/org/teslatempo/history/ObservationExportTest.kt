package org.teslatempo.history

import org.junit.Assert.*
import org.junit.Test
import org.teslatempo.network.*
import java.time.Instant

class ObservationExportTest {
    private val at = Instant.parse("2026-01-15T20:00:00Z")
    @Test fun countersRemainSeparateMissingIsEmptyAndInjectionIsNeutralized() {
        val samples = listOf(ChargeObservation(at.plusSeconds(60), "=EVIL()", null, 2.0, null),
            ChargeObservation(at, "Charging", 50.0, 2.0, 10.0))
        val csv = ObservationExport.csv(ObservationHistory(false, samples))
        assertTrue(csv.contains("2026-01-15T20:00:00Z,Charging,50.0,2.0,10.0,,false"))
        assertTrue(csv.contains("2026-01-15T20:01:00Z,Unknown,,2.0,,,false"))
        assertFalse(csv.contains("EVIL")); assertFalse(csv.contains("4.0"))
    }
    @Test fun gapsAndCounterResetsAreNotSessions() {
        val h = ObservationHistory(false,listOf(ChargeObservation(at,"Charging",50.0,3.0,10.0),
            ChargeObservation(at.plusSeconds(3600),"Charging",51.0,1.0,10.0)))
        assertEquals(ObservationCoverage(2,3600,1),observationCoverage(h))
        assertEquals(ObservationCoverage(0,null,0),observationCoverage(ObservationHistory(false,emptyList())))
    }
    @Test fun rejectsNonFiniteMeasures() {
        assertThrows(IllegalArgumentException::class.java) {
            ObservationExport.csv(ObservationHistory(false,listOf(ChargeObservation(at,"Charging",Double.NaN,null,null))))
        }
    }
}
