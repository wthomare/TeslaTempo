package org.teslatempo.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import org.teslatempo.MainActivity
import org.teslatempo.R
import java.time.Instant

/** A local reminder to review a plan, never a charging scheduler. One reminder at a time. */
class PlanReminder(private val context: Context) {
    private val prefs = context.getSharedPreferences("plan_reminder", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 41,
        Intent(context, ReminderReceiver::class.java).setAction("org.teslatempo.REVIEW_PLAN"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun allowed() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun scheduledFor(id: String) = prefs.getString("plan", null) == id && prefs.getLong("at", 0) > System.currentTimeMillis()
    fun schedule(id: String, at: Instant) {
        require(at.isAfter(Instant.now()) && at.isBefore(Instant.now().plusSeconds(7 * 86400)))
        check(allowed())
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels de vérification", NotificationManager.IMPORTANCE_DEFAULT))
        check(prefs.edit().putString("plan", id).putLong("at", at.toEpochMilli()).commit())
        try { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending()) }
        catch (e: Exception) { cancel(); throw e }
    }
    fun cancel(id: String? = null) {
        if (id != null && prefs.getString("plan", null) != id) return
        alarm.cancel(pending()); prefs.edit().clear().commit(); manager.cancel(41)
    }
    fun restore() {
        val at = prefs.getLong("at", 0)
        if (at <= System.currentTimeMillis() || !allowed()) { cancel(); return }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
    }
    fun deliver() {
        val at = prefs.getLong("at", 0)
        if (at == 0L || System.currentTimeMillis() < at) return
        // A stale reminder after an extended shutdown must not encourage using an old plan.
        if (System.currentTimeMillis() - at > 2 * 3600_000 || !allowed()) { cancel(); return }
        prefs.edit().clear().commit()
        val open = PendingIntent.getActivity(context, 41, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Vérifier votre recharge")
            .setContentText("Actualisez les données et recalculez votre plan. Aucune commande automatique.")
            .setStyle(Notification.BigTextStyle().bigText("Actualisez les données et recalculez votre plan. Aucune commande automatique."))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build()
        try { manager.notify(41, notification) } catch (_: SecurityException) { /* Permission revoked meanwhile. */ }
    }
    companion object { const val CHANNEL = "plan_review" }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = PlanReminder(context)
        when (intent.action) {
            "org.teslatempo.REVIEW_PLAN" -> reminder.deliver()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED -> reminder.restore()
        }
    }
}
