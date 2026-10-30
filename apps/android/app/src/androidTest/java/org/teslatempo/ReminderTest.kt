package org.teslatempo

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslatempo.notifications.PlanReminder
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    @Test fun reminderReplacesRestoresCancelsAndExpiresWithoutNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val reminder = PlanReminder(context)
        try {
            reminder.schedule("synthetic-one", Instant.now().plusSeconds(3600))
            assertTrue(reminder.scheduledFor("synthetic-one"))
            reminder.schedule("synthetic-two", Instant.now().plusSeconds(7200))
            assertFalse(reminder.scheduledFor("synthetic-one"))
            PlanReminder(context).restore()
            assertTrue(PlanReminder(context).scheduledFor("synthetic-two"))
            reminder.cancel("synthetic-one")
            assertTrue(reminder.scheduledFor("synthetic-two"))
            reminder.cancel("synthetic-two")
            assertFalse(reminder.scheduledFor("synthetic-two"))
            val prefs = context.getSharedPreferences("plan_reminder", Context.MODE_PRIVATE)
            prefs.edit().putString("plan", "expired").putLong("at", System.currentTimeMillis()-3*3600_000).commit()
            reminder.deliver()
            assertTrue(prefs.all.isEmpty())
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        } finally { reminder.cancel() }
    }
    @Test fun notificationDenialDoesNotCreateAnAlarm() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val denied = object : android.content.ContextWrapper(base) {
            override fun checkSelfPermission(permission: String): Int = android.content.pm.PackageManager.PERMISSION_DENIED
        }
        val reminder = PlanReminder(denied)
        try {
            reminder.cancel()
            assertFalse(reminder.allowed())
            assertThrows(IllegalStateException::class.java) { reminder.schedule("denied",Instant.now().plusSeconds(3600)) }
            assertFalse(reminder.scheduledFor("denied"))
        } finally { reminder.cancel() }
    }
    @Test fun pastReminderIsRefused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IllegalArgumentException::class.java) {
            PlanReminder(context).schedule("synthetic-past", Instant.now().minusSeconds(1))
        }
    }
}
