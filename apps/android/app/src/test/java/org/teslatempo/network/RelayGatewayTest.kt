package org.teslatempo.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RelayGatewayTest {
    private fun server() = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    private val status = """{"mode":"mock","simulated":true}"""
    private val vehicles = """{"simulated":true,"vehicles":[{"id":"demo","name":"Model Y","state":"online"}]}"""
    private fun MockWebServer.json(body: String) = enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    private fun gateway(server: MockWebServer) = RelayGateway(RelayCredentials(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "local-test-token"), true)

    @Test fun `mock relay contract and authorization`() {
        server().use { server ->
            server.json(status); server.json(vehicles)
            server.json("""{"simulated":true,"charge_state":{"battery_level":55,"charge_limit_soc":80,"charging_state":"Stopped","charge_current_request":10}}""")
            val data = gateway(server).load()
            assertTrue(data.simulated)
            assertEquals(55, data.charge?.battery)
            assertEquals("Stopped", data.charge?.state)
            listOf("/v1/status", "/v1/vehicles", "/v1/vehicles/demo/charge-state").forEach {
                val request = server.takeRequest()
                assertEquals(it, request.path)
                assertEquals("Bearer local-test-token", request.getHeader("Authorization"))
                assertEquals("GET", request.method)
            }
        }
    }
    @Test fun `no vehicle means no charge request`() {
        server().use { server ->
            server.json(status); server.json("""{"simulated":true,"vehicles":[]}""")
            assertNull(gateway(server).load().selected)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `null telemetry is not replaced with demo values`() {
        server().use { server ->
            server.json(status); server.json(vehicles)
            server.json("""{"simulated":true,"charge_state":{"battery_level":null}}""")
            val data = gateway(server).load()
            assertNull(data.charge?.battery); assertNull(data.charge?.current)
        }
    }
    @Test fun `invalid battery and inconsistent provenance rejected`() {
        for (body in listOf("""{"simulated":true,"charge_state":{"battery_level":120}}""",
            """{"simulated":false,"charge_state":{"battery_level":55}}""")) {
            server().use { server ->
                server.json(status); server.json(vehicles); server.json(body)
                assertThrows(RelayException::class.java) { gateway(server).load() }
            }
        }
    }
    @Test fun `http authentication rate limit and malformed body are safe errors`() {
        for (code in listOf(401, 403, 429, 502, 200)) {
            server().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("SECRET invalid response"))
                val failure = assertThrows(RelayException::class.java) { gateway(server).load() }
                assertFalse(failure.message!!.contains("SECRET"))
                assertFalse(failure.message!!.contains("local-test-token"))
            }
        }
    }
    @Test fun `redirect never forwards authorization`() {
        server().use { target ->
            server().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/stolen")))
                assertThrows(RelayException::class.java) { gateway(server).load() }
                assertEquals(0, target.requestCount)
            }
        }
    }
    @Test fun `selected vehicle id is used and encoded as one path segment`() {
        server().use { server ->
            server.json(status)
            server.json("""{"simulated":true,"vehicles":[{"id":"first"},{"id":"id/second"}]}""")
            server.json("""{"simulated":true,"charge_state":null}""")
            assertEquals("id/second", gateway(server).load("id/second").selected?.id)
            server.takeRequest(); server.takeRequest()
            assertEquals("/v1/vehicles/id%2Fsecond/charge-state", server.takeRequest().path)
        }
    }
    @Test fun `release requires https and credentials cannot be put in url`() {
        for (url in listOf("http://example.org", "http://127.0.0.1:8080", "https://user:pass@example.org", "https://example.org/path", "https://example.org?token=bad", "https://example.org#fragment")) {
            assertThrows(RelayException::class.java) { validateRelayUrl(url, false) }
        }
        assertEquals("example.org", validateRelayUrl("https://example.org", false).host)
        assertEquals("10.0.2.2", validateRelayUrl("http://10.0.2.2:8080", true).host)
        assertThrows(RelayException::class.java) { validateRelayUrl("http://192.168.1.10", true) }
    }
}
