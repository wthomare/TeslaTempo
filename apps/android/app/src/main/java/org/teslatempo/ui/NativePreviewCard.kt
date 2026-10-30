package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.teslatempo.execution.*
import org.teslatempo.planner.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NativePreviewCard(request: ChargingRequest, comparison: PlanComparison, calendar: TempoCalendar,
    calculatedAt: Instant, now: Instant, fictitious: Boolean) {
    var open by remember(request, comparison) { mutableStateOf(false) }
    var choice by remember(request, comparison) { mutableStateOf(PlanChoice.ECONOMIC) }
    OutlinedButton(onClick = { open = !open }) { Text(if (open) "Masquer l’aperçu Tesla" else "Préparer un programme Tesla") }
    if (!open) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Aperçu uniquement · aucune commande", style = MaterialTheme.typography.titleMedium)
            Text("Cet aperçu prépare un démarrage ponctuel sans heure d’arrêt. La limite de batterie arrête la recharge ; 6 h et le départ indicatif ne la coupent pas.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(choice == PlanChoice.ECONOMIC, { choice = PlanChoice.ECONOMIC }, label = { Text("Économique") })
                FilterChip(choice == PlanChoice.FALLBACK, { choice = PlanChoice.FALLBACK }, label = { Text("Secours continu") })
            }
            val preview = remember(request, comparison, calendar, calculatedAt, now, choice) {
                runCatching { previewNative(request, comparison, calendar, calculatedAt, now, choice) }
            }
            if (preview.isFailure) Text("Aperçu indisponible : recalculez avec des tarifs et disponibilités valides.")
            else {
                val p = preview.getOrThrow()
                p.issues.forEach { Text(it.label) }
                p.start?.let {
                    val label = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm xxx").withZone(ZoneId.of("Europe/Paris")).format(it)
                    Text("Début proposé : $label · Europe/Paris\nCible ${request.targetPercent} % · ${request.currentAmps} A maximum\nExécution unique · aucune heure de fin imposée")
                    Text("Début arrondi à la minute avec deux minutes de préparation ; coût et énergie recalculés. La voiture doit rester branchée jusqu’à la cible.", style = MaterialTheme.typography.bodySmall)
                }
                if (p.estimate != null && p.request != null) EstimateCard("Estimation du programme continu", p.estimate, p.request, fictitious)
                if (p.representable) Text("Forme de programme préparée. L’application réelle exige encore la lecture du véhicule, des programmes existants et la validation de la clé virtuelle.")
            }
            Text("Les programmes existants ne seront pas remplacés automatiquement. Cet écran ne constitue pas un consentement à commander la voiture.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
