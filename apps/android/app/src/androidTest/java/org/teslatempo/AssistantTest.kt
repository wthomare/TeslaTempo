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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.teslatempo.history.*
import org.teslatempo.planner.*
import org.teslatempo.network.RelayCredentials
import org.teslatempo.security.CredentialStore
import org.teslatempo.ui.AssistantExplanation
import org.teslatempo.ui.AssistantViewModel
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AssistantTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun previewCancellationDoesNotSendAndCompletedAnswerIsShown() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""{"connected":true,"plan_enabled":true,"account":"oaiapp_synthetic","commands_enabled":false}"""))
            server.enqueue(MockResponse().setBody("""{"models":[{"id":"available","name":"Available"}]}"""))
            server.enqueue(MockResponse().setBody("""{"text":"Explication synthétique terminée.","completed":true,"vehicle_commands_enabled":false}"""))
            val credentials = CredentialStore(compose.activity)
            val before = credentials.load()
            try {
                credentials.save(RelayCredentials(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "synthetic-test-token"))
                val d = demoSimulation(LocalDate.parse("2026-11-02"), 20.0, 60.0, 10, DemoScenario.HOME)
                val plan = SavedPlan.capture(d.request, ChargingOptimizer().calculate(d.request, d.calendar), PlanOrigin.DEMO, Instant.now())
                val vm = AssistantViewModel(credentials)
                compose.activity.runOnUiThread {
                    compose.activity.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AssistantExplanation(plan, vm) } } }
                }
                compose.waitForIdle()
                assertEquals(0, server.requestCount)
                compose.onNodeWithText("Vérifier la connexion ChatGPT").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Choisir un modèle").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Choisir un modèle").performScrollTo().performClick()
                compose.onNodeWithText("Available").performClick()
                compose.onNodeWithText("Relire avant envoi").performScrollTo().performClick()
                compose.onNodeWithText("Envoyer ce résumé à ChatGPT ?").assertIsDisplayed()
                compose.onNodeWithText("Annuler").performClick()
                assertEquals(2, server.requestCount)
                compose.onNodeWithText("Relire avant envoi").performScrollTo().performClick()
                compose.onNodeWithText("Envoyer", substring = false).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Explication synthétique terminée.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Explication synthétique terminée.").performScrollTo().assertIsDisplayed()
                assertEquals(3, server.requestCount)
            } finally { if (before == null) credentials.clear() else credentials.save(before) }
        }
    }
}
