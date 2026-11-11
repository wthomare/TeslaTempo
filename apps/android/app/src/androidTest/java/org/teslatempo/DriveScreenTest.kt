package org.teslatempo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.teslatempo.drive.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DriveScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun driveIsAccessibleWithoutRelayOrBluetoothAndRejectsInvalidVin() {
        compose.onNodeWithText("Conduite").performClick()
        compose.onNodeWithText("Votre cockpit local").assertIsDisplayed()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("— km/h"))
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
        compose.onNodeWithText("Connecter en BLE").performScrollTo().performClick()
        compose.onNodeWithText("Saisissez les 17 caractères du VIN, sans I, O ni Q.").performScrollTo().assertIsDisplayed()
        assertFalse(DriveRuntime.state.value.running)
    }
    @Test fun pairingNeedsExplicitConfirmationAndCanBeCancelled() {
        compose.onNodeWithText("Conduite").performClick()
        compose.onNodeWithText("Ajouter une clé de lecture à la voiture").performScrollTo().performClick()
        compose.onNodeWithText("Autoriser une clé de lecture").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        assertFalse(DriveRuntime.state.value.running)
    }
    @Test fun overlayWindowsNeverCaptureTouchesAndAreRemovedTogether() {
        // Emulator/test installation only: restore its previous app-op in all cases.
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg=compose.activity.packageName
        fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
        val previous=shell("appops get $pkg SYSTEM_ALERT_WINDOW")
        val mode=Regex("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)").find(previous)?.groupValues?.get(1) ?: "default"
        val overlay=CockpitOverlay(compose.activity)
        try {
            shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            compose.runOnIdle {
                overlay.show(CockpitPreferences())
                overlay.render(CockpitText("— km/h","— %","— km"))
                assertEquals(2,overlay.windows.size)
                val views=overlay.windows.toList()
                for (view in views) {
                    val layout=view.layoutParams as android.view.WindowManager.LayoutParams
                    assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,layout.type)
                    assertTrue(layout.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                    assertTrue(layout.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    assertTrue(layout.alpha <= 0.75f)
                }
                overlay.close();overlay.close()
                assertFalse(overlay.visible)
                assertTrue(views.none { it.isAttachedToWindow })
            }
        } finally {
            compose.runOnIdle { overlay.close() }
            shell("appops set $pkg SYSTEM_ALERT_WINDOW $mode")
        }
    }
    @Test fun vinIsEncryptedAndKeystoreKeyCanAgreeWithoutExportingPrivateKey() {
        val identity=DriveIdentity(compose.activity)
        val before=identity.loadVin()
        try {
            val vin="5YJS0000000000000"
            identity.saveVin(vin)
            val stored=compose.activity.getSharedPreferences("drive_identity",0).getString("vin",null)!!
            assertFalse(stored.contains(vin));assertEquals(vin,identity.loadVin())
            val local=identity.keyPair()
            assertNull(local.private.encoded)
            val remote=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val actual=TeslaSession.deriveKey(local.private,DriveIdentity.publicBytes(remote.public))
            val reverse=KeyAgreement.getInstance("ECDH").apply { init(remote.private);doPhase(local.public,true) }.generateSecret()
            assertArrayEquals(MessageDigest.getInstance("SHA-1").digest(reverse).copyOf(16),actual)
            assertArrayEquals(local.public.encoded,identity.keyPair().public.encoded)
        } finally { if(before==null)identity.forgetVin() else identity.saveVin(before) }
    }
}
