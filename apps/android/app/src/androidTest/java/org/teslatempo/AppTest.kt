package org.teslatempo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.teslatempo.network.RelayCredentials
import org.teslatempo.security.CredentialStore

@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun demoAndSettings() {
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("Démonstration", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("55 %").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("DÉMONSTRATION").assertIsDisplayed()
        compose.onNodeWithText("55 %").assertIsDisplayed()
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("URL du relais").performTextInput("http://example.org")
        compose.onNodeWithText("Jeton du relais").performTextInput("test-only-token")
        compose.onNodeWithText("Connecter le relais").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("HTTPS est obligatoire pour ce relais.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun plannerSimulationAndValidation() {
        compose.onNodeWithText("Simuler un plan F20").performClick()
        compose.onNodeWithText("SIMULATION · DONNÉES FICTIVES").assertIsDisplayed()
        compose.onNodeWithText("Plan économique · brouillon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Absence à 8 h").performScrollTo().performClick()
        compose.onAllNodesWithText("Objectif non atteint", substring = true).onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Courant (A, maximum 10)").performScrollTo().performTextReplacement("11")
        compose.onNodeWithText("Le courant doit rester entre 1 et 10 A.").performScrollTo().assertIsDisplayed()
    }

    @Test fun credentialEncryptionRoundtripAndDeletion() {
        val context = compose.activity.applicationContext
        val store = CredentialStore(context)
        try {
            store.save(RelayCredentials("https://relay.example.org", "instrumented-test-secret"))
            val raw = context.getSharedPreferences("relay_credentials", 0).getString("sealed", "")!!
            assertFalse(raw.contains("instrumented-test-secret"))
            assertFalse(raw.contains("relay.example.org"))
            assertEquals("instrumented-test-secret", CredentialStore(context).load()?.token)
            store.clear()
            assertNull(store.load())
        } finally { store.clear() }
    }
}
