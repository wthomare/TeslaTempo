package org.teslatempo

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.teslatempo.tempo.TempoGateway
import java.time.Instant

/** Explicit opt-in only. Does not run on ordinary test/CI invocations. */
@RunWith(AndroidJUnit4::class)
class LiveTempoTest {
    @Test fun readPublicServiceFromAndroid() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTempo") == "true")
        val result = TempoGateway().fetch(Instant.now())
        assertTrue("Live Tempo HTTP failed: ${result.errors}", result.errors.isEmpty())
        assertNotNull(result.tariff)
        assertEquals(6, result.tariff!!.rates.size)
        assertEquals(3, result.colors.size)
    }
}
