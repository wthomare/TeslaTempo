package org.teslatempo.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.teslatempo.network.*

class DiagnosticsTest {
    private fun event(): DiagnosticEvent {
        val id=UUID.randomUUID().toString()
        return DiagnosticEvent(id,id,id,System.currentTimeMillis(),"android","status","ok",200,10,"0.9.0")
    }
    @Test fun strictFieldsRejectSecretsAndStaleEvents() {
        val e=event();assertTrue(DiagnosticLog.valid(e))
        assertFalse(DiagnosticLog.valid(e.copy(operation="SECRET")))
        assertFalse(DiagnosticLog.valid(e.copy(site="secret.kt")))
        assertFalse(DiagnosticLog.valid(e.copy(error_kind="secret exception")))
        assertFalse(DiagnosticLog.valid(e.copy(version="SECRET")))
        assertFalse(DiagnosticLog.valid(e.copy(request_id="SECRET")))
        assertFalse(DiagnosticLog.valid(e.copy(at=0)))
        assertFalse(DiagnosticLog.valid(e.copy(duration_ms=120001)))
    }
    @Test fun requestIdentifierIsSharedWithoutUrlOrPayloadLogging() {
        MockWebServer().use { s ->
            s.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            s.enqueue(MockResponse().setBody("""{"mode":"tesla","simulated":false,"version":"0.5.0","tesla_connected":true,"vehicle_commands_enabled":false}"""))
            RelayGateway(RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(),"synthetic"),true).diagnose()
            UUID.fromString(s.takeRequest().getHeader("X-Request-ID"))
            assertEquals("observations",DiagnosticLog.operation("/v1/vehicles/SECRET/charge-observations"))
            assertEquals("unknown",DiagnosticLog.operation("/SECRET"))
        }
    }
    @Test fun reportRequiresMatchingAcknowledgementAndRefusesRedirect() {
        MockWebServer().use { s ->
            s.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val e=event();val report=DiagnosticReport(e.report_id,true,listOf(e))
            val api=DiagnosticGateway(RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(),"synthetic"),true)
            s.enqueue(MockResponse().setBody("""{"accepted":1,"report_id":"${report.report_id}"}"""));assertTrue(api.send(report))
            val request=s.takeRequest();assertEquals("/v1/diagnostics/events",request.path)
            assertFalse(request.body.readUtf8().contains("synthetic"))
            s.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.example"));assertFalse(api.send(report))
            assertEquals(2,s.requestCount)
        }
    }
}
