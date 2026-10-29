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
import org.teslatempo.personal.*
import org.teslatempo.tempo.*
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun demoAndSettings() {
        compose.onNodeWithText("Réglages").performScrollTo().performClick()
        compose.onNodeWithText("Démonstration", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("55 %").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("DÉMONSTRATION").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("55 %").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Réglages").performScrollTo().performClick()
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

    @Test fun profileCanBeSavedAndReopened() {
        compose.onNodeWithText("Mon profil et calendrier").performScrollTo().performClick()
        compose.onNodeWithText("Capacité utile estimée (kWh)").performScrollTo().performTextReplacement("72")
        val previous = runBlocking { PersonalStore(compose.activity.applicationContext).loadSettings() }
        compose.onAllNodesWithText(previous.week[0].kind.label).onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("Télétravail").onLast().performClick()
        repeat(previous.exceptions.size) {
            compose.onAllNodesWithText("Supprimer cette exception").onFirst().performScrollTo().performClick()
        }
        compose.onNodeWithText("Du (AAAA-MM-JJ)").performScrollTo().performTextInput("2026-12-24")
        compose.onNodeWithText("Au inclus (AAAA-MM-JJ)").performScrollTo().performTextInput("2026-12-31")
        compose.onNodeWithText("Ajouter l’exception au brouillon").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer le profil").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mon profil et calendrier").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mon profil et calendrier").performScrollTo().performClick()
        compose.onNodeWithText("Capacité utile estimée (kWh)").performScrollTo().assertTextContains("72.0")
        val loaded = runBlocking { PersonalStore(compose.activity.applicationContext).loadSettings() }
        assertEquals(72.0, loaded.capacityKwh, 0.0)
        assertTrue(loaded.confirmed)
        assertEquals(DayKind.REMOTE, loaded.week[0].kind)
        assertEquals("2026-12-24", loaded.exceptions.single().from)
    }

    @Test fun persistedCalendarRoundtrip() = runBlocking {
        val context = compose.activity.applicationContext
        val store = PersonalStore(context)
        val before = store.loadSettings()
        try {
            val settings = PersonalSettings(confirmed = true, capacityKwh = 75.0,
                exceptions = listOf(ScheduleException("2026-12-24", "2027-01-02", DaySchedule(DayKind.VACATION))))
            store.saveSettings(settings)
            assertEquals(settings, PersonalStore(context).loadSettings())
            assertTrue(java.io.File(context.filesDir, "datastore/personal_v1.preferences_pb").exists())
        } finally { store.saveSettings(before) }
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
