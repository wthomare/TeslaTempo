package org.teslatempo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.teslatempo.diagnostics.DiagnosticLog
import org.teslatempo.network.RelayCredentials
import org.teslatempo.security.CredentialStore
import org.teslatempo.ui.DiagnosticsCard

@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun server()=MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"),0) }
    private fun credentials(s: MockWebServer)=RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(),"synthetic-private-token")
    @Test fun crashBufferNeverContainsExceptionText() {
        DiagnosticLog.clear()
        try {
            DiagnosticLog.record("app_crash","crash",immediate=true,failure=IllegalStateException("SECRET token https://private.example"))
            val export=DiagnosticLog.export()
            assertTrue(export.contains("IllegalStateException"));assertFalse(export.contains("SECRET"));assertFalse(export.contains("private.example"))
            DiagnosticLog.record("SECRET","crash",immediate=true)
            assertFalse(DiagnosticLog.export().contains("SECRET"))
        } finally { DiagnosticLog.clear() }
    }
    @Test fun failedReportRemainsPendingButCannotMoveToAnotherRelay() {
        server().use { first -> server().use { second ->
            val store=CredentialStore(compose.activity);val before=store.load();DiagnosticLog.clear()
            try {
                store.save(credentials(first));DiagnosticLog.record("status","network_error",immediate=true)
                DiagnosticLog.consent(DiagnosticLog.snapshot().takeLast(1))
                first.enqueue(MockResponse().setResponseCode(503))
                assertFalse(DiagnosticLog.retryPending(true));assertTrue(DiagnosticLog.pending())
                store.save(credentials(second));assertFalse(DiagnosticLog.retryPending(true));assertFalse(DiagnosticLog.pending())
                assertEquals(1,first.requestCount);assertEquals(0,second.requestCount)
                assertFalse(DiagnosticLog.export().contains("synthetic-private-token"))
            } finally { DiagnosticLog.clear();if(before==null)store.clear() else store.save(before) }
        } }
    }
    @Test fun newConfirmedReportsKeepCorrelationWithoutBeingDeduplicatedTogether() {
        server().use { s ->
            val store=CredentialStore(compose.activity);val before=store.load();DiagnosticLog.clear()
            try {
                store.save(credentials(s));DiagnosticLog.record("status","network_error",immediate=true)
                val rows=DiagnosticLog.snapshot().takeLast(1)
                val ids=mutableListOf<String>()
                repeat(2) {
                    val report=DiagnosticLog.consent(rows)
                    s.enqueue(MockResponse().setBody("""{"accepted":1,"report_id":"$report"}"""))
                    assertTrue(DiagnosticLog.retryPending(true))
                    val row=JsonParser.parseString(s.takeRequest().body.readUtf8()).asJsonObject.getAsJsonArray("events")[0].asJsonObject
                    ids.add(row.get("id").asString)
                    assertEquals(rows[0].request_id,row.get("request_id").asString)
                }
                assertNotEquals(ids[0],ids[1])
            } finally { DiagnosticLog.clear();if(before==null)store.clear() else store.save(before) }
        }
    }
    @Test fun reportRequiresExplicitConfirmation() {
        server().use { s ->
            s.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                val body=JsonParser.parseString(request.body.readUtf8()).asJsonObject
                return MockResponse().setBody("""{"accepted":${body.getAsJsonArray("events").size()},"report_id":"${body.get("report_id").asString}"}""")
            } }
            val store=CredentialStore(compose.activity);val before=store.load();DiagnosticLog.clear()
            try {
                store.save(credentials(s));DiagnosticLog.record("status","network_error",immediate=true)
                compose.activity.runOnUiThread { compose.activity.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { DiagnosticsCard() } } } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Envoyer un diagnostic").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Envoyer un diagnostic").performScrollTo().performClick()
                compose.onNodeWithText("Annuler").performClick();assertEquals(0,s.requestCount)
                compose.onNodeWithText("Envoyer un diagnostic").performScrollTo().performClick()
                compose.onNodeWithText("Confirmer l’envoi").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Rapport reçu par le relais.",substring=true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1,s.requestCount);assertFalse(DiagnosticLog.pending())
            } finally { DiagnosticLog.clear();if(before==null)store.clear() else store.save(before) }
        }
    }
}
