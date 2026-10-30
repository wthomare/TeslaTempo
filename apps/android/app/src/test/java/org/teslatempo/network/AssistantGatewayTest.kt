package org.teslatempo.network

import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AssistantGatewayTest {
    private fun server() = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    private fun gateway(s: MockWebServer) = AssistantGateway(RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(), "test-token"), true)
    @Test fun `explanation sends only reviewed text with consent and never calls Tesla`() {
        server().use { s ->
            s.enqueue(MockResponse().setBody("""{"text":"Explication","completed":true,"vehicle_commands_enabled":false}"""))
            assertEquals("Explication", gateway(s).explain("available", "Synthetic summary", "oaiapp_test"))
            val req = s.takeRequest()
            assertEquals("/v1/chatgpt/explain", req.path)
            assertEquals("Bearer test-token", req.getHeader("Authorization"))
            val body = JsonParser.parseString(req.body.readUtf8()).asJsonObject
            assertEquals(setOf("summary", "model", "consent", "account"), body.keySet())
            assertTrue(body.get("consent").asBoolean)
            assertEquals("Synthetic summary", body.get("summary").asString)
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun `incomplete or command capable responses are rejected`() {
        for (body in listOf("""{"text":"partial","completed":false,"vehicle_commands_enabled":false}""",
            """{"text":"unsafe","completed":true,"vehicle_commands_enabled":true}""", "{}")) {
            server().use { s ->
                s.enqueue(MockResponse().setBody(body))
                assertThrows(RelayException::class.java) { gateway(s).explain("available", "summary", "oaiapp_test") }
            }
        }
    }
    @Test fun `redirect and provider errors never leak body or repeat requests`() {
        for (code in listOf(302, 401, 409, 429, 502)) {
            server().use { s ->
                s.enqueue(MockResponse().setResponseCode(code).setHeader("Location", s.url("/leak")).setBody("SECRET"))
                val e = assertThrows(RelayException::class.java) { gateway(s).explain("available", "summary", "oaiapp_test") }
                assertFalse(e.message!!.contains("SECRET")); assertEquals(1, s.requestCount)
            }
        }
    }
    @Test fun `models and disconnect use dedicated endpoints`() {
        server().use { s ->
            s.enqueue(MockResponse().setBody("""{"models":[{"id":"available","name":"Available"}]}"""))
            s.enqueue(MockResponse().setBody("""{"connected":false,"remote_revocation_confirmed":false}"""))
            val api = gateway(s)
            assertEquals(listOf(AssistantModel("available", "Available")), api.models())
            assertFalse(api.disconnect())
            assertEquals("/v1/chatgpt/models", s.takeRequest().path)
            assertEquals("DELETE", s.takeRequest().method)
        }
    }
}
