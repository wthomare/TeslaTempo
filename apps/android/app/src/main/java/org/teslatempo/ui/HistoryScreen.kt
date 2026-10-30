package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.teslatempo.history.*
import org.teslatempo.planner.PlanStatus
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun archiveTime(value: String) = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Europe/Paris")).format(Instant.parse(value))

@Composable
fun SaveSimulation(plan: SavedPlan, state: HistoryState, save: (SavedPlan) -> Unit) {
    val saved = state.plans.any { it.id == plan.id }
    Button(onClick = { save(plan) }, modifier = Modifier.fillMaxWidth(),
        enabled = state.ready && !state.busy && !state.unreadable && !saved && state.plans.size < HISTORY_LIMIT) {
        Text(if (saved) "Simulation enregistrée" else "Enregistrer la simulation")
    }
    Text("Enregistrement local uniquement. Aucun programme envoyé au véhicule.", style = MaterialTheme.typography.bodySmall)
    if (state.plans.size >= HISTORY_LIMIT && !saved) Text("Historique plein : supprimez une simulation pour libérer une place.")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun HistoryScreen(state: HistoryState, delete: (String) -> Unit, clear: () -> Unit, assistant: AssistantViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var removal by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Simulations enregistrées", style = MaterialTheme.typography.titleLarge)
    Text("Estimations uniquement : aucune recharge réalisée ni économie mesurée. Les calculs conservés ne sont pas recalculés avec les nouveaux tarifs.")
    Text("Stockage privé sur ce téléphone, exclu des sauvegardes Android. ${state.plans.size}/$HISTORY_LIMIT simulations ; aucune suppression automatique.", style = MaterialTheme.typography.bodySmall)
    if (!state.ready || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val current = state.plans.find { it.id == selected }
    if (current != null) {
        TextButton(onClick = { selected = null }) { Text("Retour à la liste") }
        Text(current.origin.label, color = MaterialTheme.colorScheme.primary)
        Text("Calcul du ${archiveTime(current.calculatedAt)} · Europe/Paris")
        Text("Archive à consulter uniquement. Pour préparer une recharge, créez un nouveau calcul avec votre batterie et votre disponibilité actuelles.")
        val r = current.request.request()
        Text("Horizon : ${archiveTime(current.request.start)} → ${archiveTime(current.request.horizon)}")
        Text("Batterie ${r.batteryPercent} % → ${r.targetPercent} % · capacité estimée ${r.capacityKwh} kWh · ${r.currentAmps} A · rendement ${r.efficiency * 100} %")
        if (current.status == PlanStatus.TARGET_REACHED) Text("Objectif déjà atteint dans cette simulation : aucune recharge nécessaire.")
        else {
            EstimateCard("Estimation économique archivée", current.optimized.estimate(), r, current.origin == PlanOrigin.DEMO)
            EstimateCard("Estimation de secours archivée", current.fallback.estimate(), r, current.origin == PlanOrigin.DEMO)
        }
        (current.optimized.slots + current.fallback.slots).map { it.price.tariffId to it.price.source }.distinct().forEach { (id, source) ->
            Text("Tarif utilisé : $id\nSource au calcul : $source", style = MaterialTheme.typography.bodySmall)
        }
        ReminderCard(current)
        AssistantExplanation(current, assistant)
        AssistantShare(current)
        OutlinedButton(onClick = { removal = current.id }, enabled = !state.busy) { Text("Supprimer cette simulation") }
    } else {
        if (state.ready && state.plans.isEmpty() && !state.unreadable) Text("Aucune simulation enregistrée. Calculez un plan personnel ou une démonstration, puis choisissez de l’enregistrer.")
        state.plans.forEach { plan ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plan.origin.label, style = MaterialTheme.typography.titleMedium)
                    Text(archiveTime(plan.calculatedAt))
                    Text("${plan.request.batteryPercent} % → ${plan.request.targetPercent} % · coût estimé ${String.format(Locale.FRANCE, "%.2f", plan.optimized.estimate().costEuros)} €")
                    TextButton(onClick = { selected = plan.id }) { Text("Consulter la simulation") }
                }
            }
        }
    }
    if (state.plans.isNotEmpty() || state.unreadable) {
        OutlinedButton(onClick = { removal = "all" }, enabled = !state.busy) { Text("Effacer l’historique") }
    }
    removal?.let { id ->
        AlertDialog(onDismissRequest = { removal = null },
            title = { Text(if (id == "all") "Effacer toutes les simulations ?" else "Supprimer cette simulation ?") },
            text = { Text("Cette suppression locale est définitive. Le profil, les données Tempo et la connexion au relais sont conservés.") },
            confirmButton = { TextButton(onClick = {
                removal = null
                org.teslatempo.notifications.PlanReminder(context).cancel(if (id == "all") null else id)
                if (id == "all") clear() else delete(id)
            }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Annuler") } })
    }
}
