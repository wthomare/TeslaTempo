package org.teslatempo.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ObservationsGatewayTest {
    private fun server()=MockWebServer().apply{start(java.net.InetAddress.getByName("127.0.0.1"),0)}
    private fun api(s: MockWebServer)=RelayGateway(RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(),"test-token"),true)
    @Test fun `unknown values stay unknown and repeated counters are not summed`() {
        server().use{s ->
            s.enqueue(MockResponse().setBody("""{"simulated":false,"coverage":"snapshots_only","total_energy_kwh":null,"total_cost_euros":null,"samples":[{"sampled_at":"2026-11-02T12:00:00Z","state":"Charging","battery_percent":null,"session_counter_kwh":12,"current_amps":null},{"sampled_at":"2026-11-02T12:01:00Z","state":"Charging","battery_percent":60,"session_counter_kwh":12,"current_amps":10}]}"""))
            val data=api(s).observations("123")
            assertEquals(2,data.samples.size);assertNull(data.samples[0].battery);assertNull(data.samples[0].current)
            assertEquals(12.0,data.samples[0].sessionCounter!!,0.0)
            assertEquals("/v1/vehicles/123/charge-observations",s.takeRequest().path)
            assertEquals(1,s.requestCount)
        }
    }
    @Test fun `invented totals and unsafe data are rejected`() {
        server().use{s ->
            s.enqueue(MockResponse().setBody("""{"simulated":false,"coverage":"snapshots_only","total_energy_kwh":24,"total_cost_euros":0,"samples":[]}"""))
            assertThrows(RelayException::class.java){api(s).observations("123")}
        }
    }
    @Test fun `clear calls only the dedicated history delete route`() {
        server().use{s ->
            s.enqueue(MockResponse().setBody("""{"cleared":true,"simulated":false}"""))
            api(s).clearObservations("123")
            val r=s.takeRequest();assertEquals("DELETE",r.method);assertEquals("/v1/vehicles/123/charge-observations",r.path)
        }
    }
}
