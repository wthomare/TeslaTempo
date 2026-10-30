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
import org.teslatempo.domain.Vehicle
import org.teslatempo.network.RelayCredentials
import org.teslatempo.security.CredentialStore
import org.teslatempo.ui.ObservationsScreen
import org.teslatempo.ui.ObservationsViewModel

@RunWith(AndroidJUnit4::class)
class PreparationAndObservationsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativePreviewIsExplicitAndNeverOffersApply() {
        compose.onNodeWithText("Simuler un plan F20").performScrollTo().performClick()
        compose.onNodeWithText("Préparer un programme Tesla").performScrollTo().performClick()
        compose.onNodeWithText("Aperçu uniquement · aucune commande").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exécution unique · aucune heure de fin imposée",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Appliquer",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Secours continu").performScrollTo().performClick()
        compose.onNodeWithText("Aperçu uniquement · aucune commande").assertExists()
    }
    @Test fun observationsLoadExplicitlyAndDeletionRequiresConfirmation() {
        MockWebServer().use{s ->
            s.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            s.enqueue(MockResponse().setBody("""{"simulated":false,"coverage":"snapshots_only","total_energy_kwh":null,"total_cost_euros":null,"samples":[{"sampled_at":"2026-11-02T12:00:00Z","state":"Charging","battery_percent":60,"session_counter_kwh":12,"current_amps":10}]}"""))
            s.enqueue(MockResponse().setBody("""{"cleared":true,"simulated":false}"""))
            val store=CredentialStore(compose.activity);val before=store.load()
            try {
                store.save(RelayCredentials(s.url("/").newBuilder().host("127.0.0.1").build().toString(),"test-token"))
                val vm=ObservationsViewModel(store)
                compose.activity.runOnUiThread{compose.activity.setContent{MaterialTheme{Column(Modifier.verticalScroll(rememberScrollState())){ObservationsScreen(Vehicle("123","Fictif","online"),vm)}}}}
                compose.waitForIdle();assertEquals(0,s.requestCount)
                compose.onNodeWithText("Charger les relevés").performClick()
                compose.waitUntil(10_000){compose.onAllNodesWithText("Coût : inconnu").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Coût : inconnu").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Effacer les relevés de ce véhicule").performScrollTo().performClick()
                compose.onNodeWithText("Annuler").performClick();assertEquals(1,s.requestCount)
                compose.onNodeWithText("Effacer les relevés de ce véhicule").performClick()
                compose.onNodeWithText("Effacer",substring=false).performClick()
                compose.waitUntil(10_000){s.requestCount==2}
                compose.waitUntil(10_000){compose.onAllNodesWithText("Relevés effacés du relais.",substring=true).fetchSemanticsNodes().isNotEmpty()}
                assertEquals("GET",s.takeRequest().method);assertEquals("DELETE",s.takeRequest().method)
            } finally {if(before==null)store.clear() else store.save(before)}
        }
    }
}
