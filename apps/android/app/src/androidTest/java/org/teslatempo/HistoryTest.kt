package org.teslatempo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.teslatempo.history.*
import org.teslatempo.personal.PersonalStore
import org.teslatempo.planner.*
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun saveReopenAndDeleteSimulationWithConfirmation() {
        val store = HistoryStore(compose.activity.applicationContext)
        runBlocking { store.clear() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Simuler un plan F20").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer la simulation").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Simulation enregistrée").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Simulation enregistrée").assertIsNotEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Accueil").performScrollTo().performClick()
        compose.onNodeWithText("Historique des simulations").performScrollTo().performClick()
        compose.onNodeWithText("Démonstration fictive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Consulter la simulation").performScrollTo().performClick()
        compose.onNodeWithText("Estimation économique archivée").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Préparer pour ChatGPT").performScrollTo().performClick()
        compose.onNodeWithText("Résumé à partager").assertIsDisplayed()
        compose.onNodeWithText("Explique en français", substring = true).assertExists()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(1, runBlocking { store.load() }.size)
        compose.onNodeWithText("Préparer pour ChatGPT").performScrollTo().performClick()
        compose.onNodeWithText("Copier").performClick()
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(assistantSummary(runBlocking { store.load() }.single()), clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("Supprimer cette simulation").performScrollTo().performClick()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(1, runBlocking { store.load() }.size)
        compose.onNodeWithText("Supprimer cette simulation").performClick()
        compose.onNodeWithText("Supprimer", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Aucune simulation enregistrée.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { store.load() }.isEmpty())
    }

    @Test fun historyPersistenceAndClearDoNotChangePersonalSettings() = runBlocking {
        val context = compose.activity.applicationContext
        val store = HistoryStore(context)
        store.clear()
        val before = PersonalStore(context).loadSettings()
        val demo = demoSimulation(LocalDate.parse("2026-11-02"), 20.0, 60.0, 10, DemoScenario.UNKNOWN)
        val saved = SavedPlan.capture(demo.request, ChargingOptimizer().calculate(demo.request, demo.calendar), PlanOrigin.PERSONAL, Instant.now())
        try {
            store.save(saved); store.save(saved)
            assertEquals(listOf(saved), HistoryStore(context).load())
            assertTrue(java.io.File(context.filesDir, "datastore/plan_history_v1.preferences_pb").exists())
            store.clear()
            assertTrue(HistoryStore(context).load().isEmpty())
            assertEquals(before, PersonalStore(context).loadSettings())
        } finally { store.clear() }
    }
}
