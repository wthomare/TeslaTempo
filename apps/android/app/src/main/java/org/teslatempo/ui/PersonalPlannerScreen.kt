package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import org.teslatempo.personal.availability
import org.teslatempo.history.*
import org.teslatempo.planner.*
import org.teslatempo.tempo.*
import java.time.*

data class PersonalCalculation(val request: ChargingRequest, val result: PlanComparison, val created: Instant)

@Composable
fun PersonalPlannerScreen(state: PersonalState, history: HistoryState, save: (SavedPlan) -> Unit, editProfile: () -> Unit) {
    Text("Plan personnel · lecture seule", style = MaterialTheme.typography.titleLarge)
    if (!state.settings.confirmed) {
        Text("Renseignez et confirmez d’abord la capacité de batterie et votre semaine type.")
        Button(onClick = editProfile) { Text("Configurer mon profil") }
        return
    }
    var soc by rememberSaveable { mutableStateOf("") }
    var home by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var calculation by remember(soc, home, manual, state.settings, state.tempo) { mutableStateOf<PersonalCalculation?>(null) }
    var error by remember(soc, home, manual, state.settings, state.tempo) { mutableStateOf<String?>(null) }
    val p = state.settings
    Text("Capacité estimée ${p.capacityKwh} kWh · cible ${p.targetPercent} % · ${p.currentAmps} A · rendement ${p.efficiency * 100} %")
    Text("Puissance estimée en monophasé 230 V ; aucune mesure réelle de puissance n’est encore utilisée.", style = MaterialTheme.typography.bodySmall)
    Text("Renseignez le niveau de batterie actuel. La valeur fictive du tableau de bord n’est jamais reprise. Le plan commence maintenant et couvre 48 heures, selon votre calendrier.")
    NumberField("Batterie actuelle confirmée (%)", soc) { soc = it }
    Row { Checkbox(home, { home = it }); Text("Je confirme que la voiture est branchée au domicile actuellement.") }
    Row { Checkbox(manual, { manual = it }); Text("Une programmation manuelle Tesla est déjà active.") }
    Text("Une présence future au calendrier suppose que vous rebrancherez le véhicule. Le calcul ne prévoit pas la consommation des trajets ; il faudra recalculer au retour.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = editProfile) { Text("Modifier mon profil et mon calendrier") }
    Button(onClick = {
        try {
            val now = Instant.now()
            val departureTime = LocalTime.parse(p.indicativeDeparture)
            val local = now.atZone(PARIS)
            var departure = local.toLocalDate().atTime(departureTime).atZone(PARIS).toInstant()
            if (departure <= now) departure = local.toLocalDate().plusDays(1).atTime(departureTime).atZone(PARIS).toInstant()
            val end = now.plus(Duration.ofHours(48))
            val request = ChargingRequest(now, end, departure, soc.replace(',', '.').toDoubleOrNull()
                ?: throw IllegalArgumentException("Renseignez le niveau de batterie actuel."), p.targetPercent, p.capacityKwh, p.efficiency,
                p.currentAmps, availability = availability(p, now, end), atHome = home, manualSchedule = manual)
            val calendar = state.tempo.calendar(now)
            calculation = PersonalCalculation(request, ChargingOptimizer().calculate(request, calendar), now)
            error = null
        } catch (e: IllegalArgumentException) { calculation = null; error = e.message ?: "Calcul impossible." }
    }, modifier = Modifier.fillMaxWidth()) { Text("Calculer mon plan") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    calculation?.let { c ->
        if (c.result.status == PlanStatus.DRAFT || c.result.status == PlanStatus.TARGET_REACHED) {
            val snapshot = remember(c) { SavedPlan.capture(c.request, c.result, PlanOrigin.PERSONAL, c.created) }
            SaveSimulation(snapshot, history, save)
        }
        Text("Calculé le ${localTime(c.created.toString())} · horizon 48 h · Europe/Paris")
        if (state.now >= c.created.plusSeconds(300)) Text("Calcul ancien : recalculez avant de vous y référer.", color = MaterialTheme.colorScheme.error)
        when (c.result.status) {
            PlanStatus.TARGET_REACHED -> Text("Objectif déjà atteint : aucune recharge nécessaire.")
            PlanStatus.AWAY_FROM_HOME -> Text("Confirmez le branchement au domicile pour calculer une proposition.")
            PlanStatus.MANUAL_CONFLICT -> Text("Programmation manuelle détectée : aucun nouveau plan proposé. Elle doit être vérifiée dans Tesla.")
            PlanStatus.UNAVAILABLE -> Text("Aucune disponibilité sur les 48 prochaines heures.")
            PlanStatus.DRAFT -> {
                runCatching { state.tempo.calendar(state.now) }.getOrNull()?.let { calendar ->
                    NativePreviewCard(c.request, c.result, calendar, c.created, state.now, false)
                }
                EstimateCard("Plan économique · brouillon", c.result.optimized, c.request, fictitious = false)
                EstimateCard("Secours · charge continue", c.result.fallback, c.request, fictitious = false)
                if (c.result.requiresMultipleWindows) Text("Plusieurs fenêtres nécessaires : programmation autonome Tesla non validée.")
            }
        }
        Text("Source des couleurs et prix : $TEMPO_SOURCE · tarifs contrôlés le ${state.tempo.tariff?.checkedAt?.let(::localTime)}.")
        Text("Aucune commande envoyée. Le secours et les réglages doivent être confirmés dans Tesla. Couleurs futures inconnues : borne tarifaire haute, pas une annonce EDF.")
    }
}
