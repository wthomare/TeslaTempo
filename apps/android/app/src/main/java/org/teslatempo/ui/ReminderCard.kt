package org.teslatempo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.teslatempo.history.*
import org.teslatempo.notifications.PlanReminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReminderCard(plan: SavedPlan) {
    if (plan.origin != PlanOrigin.PERSONAL) return
    val context = LocalContext.current
    val reminder = remember { PlanReminder(context) }
    val start = plan.optimized.slots.firstOrNull()?.start?.let(Instant::parse) ?: return
    val at = start.minusSeconds(15 * 60)
    var message by remember(plan.id) { mutableStateOf<String?>(null) }
    var scheduled by remember(plan.id) { mutableStateOf(reminder.scheduledFor(plan.id)) }
    fun schedule() {
        message = runCatching { reminder.schedule(plan.id, at); scheduled = true; "Rappel enregistré sur ce téléphone." }
            .getOrElse { "Rappel indisponible. Vérifiez les notifications Android et recalculez si l’horaire est passé." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) schedule() else message = "Notifications refusées. Aucun rappel créé ; la planification reste disponible."
    }
    Text("Rappel local facultatif", style = MaterialTheme.typography.titleMedium)
    Text("Vous rappeler de recalculer et vérifier le plan 15 minutes avant son premier créneau économique. Un seul rappel à la fois ; un nouveau remplace le précédent. Android peut retarder la notification. Elle ne démarre aucune recharge.")
    if (at.isAfter(Instant.now()) && at.isBefore(Instant.now().plusSeconds(7 * 86400))) {
        Text(DateTimeFormatter.ofPattern("dd/MM HH:mm xxx").withZone(ZoneId.of("Europe/Paris")).format(at))
        OutlinedButton(onClick = { if (reminder.allowed()) schedule() else permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Me rappeler de vérifier ce plan") }
    } else Text("Horaire trop proche, passé ou au-delà de sept jours : recalculez avant de créer un rappel.")
    if (scheduled) TextButton(onClick = { reminder.cancel(plan.id); scheduled = false; message = "Rappel annulé." }) { Text("Annuler le rappel") }
    message?.let { Text(it) }
}
