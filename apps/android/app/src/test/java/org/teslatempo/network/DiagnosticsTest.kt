package org.teslatempo.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun teslaAuthorizationIsNotReportedAsWrongRelayToken() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"tesla_authorization_required"}"""))
            val failure = assertThrows(RelayException::class.java) {
                RelayGateway(RelayCredentials(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "synthetic"), true).diagnose()
            }
            assertTrue(failure.message!!.contains("session Tesla"))
            assertFalse(failure.message!!.contains("synthetic"))
        }
    }
    @Test fun diagnosticsDoNotReadOrWakeVehicle() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            server.enqueue(MockResponse().setBody("""{"mode":"tesla","simulated":false,"version":"0.3.0","tesla_connected":false,"vehicle_commands_enabled":false}"""))
            val d = RelayGateway(RelayCredentials(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "synthetic"), true).diagnose()
            assertFalse(d.teslaConnected); assertFalse(d.commandsEnabled)
            assertEquals("/v1/status", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }
}
