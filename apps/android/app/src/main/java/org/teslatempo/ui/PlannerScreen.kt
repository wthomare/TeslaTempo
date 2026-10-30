package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.teslatempo.planner.*
import org.teslatempo.history.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun amount(value: Double) = String.format(Locale.FRANCE, "%.2f", value)
private val timeFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm xxx").withZone(ZoneId.of("Europe/Paris"))

@Composable
fun PlannerScreen(history: HistoryState, save: (SavedPlan) -> Unit) {
    var soc by rememberSaveable { mutableStateOf("20") }
    var capacity by rememberSaveable { mutableStateOf("60") }
    var amps by rememberSaveable { mutableStateOf("10") }
    var scenarioName by rememberSaveable { mutableStateOf(DemoScenario.HOME.name) }
    val day = remember { LocalDate.now(ZoneId.of("Europe/Paris")) }
    val result = remember(soc, capacity, amps, scenarioName, day) {
        runCatching {
            fun number(text: String) = text.replace(',', '.').toDoubleOrNull()
                ?: throw IllegalArgumentException("Saisissez des valeurs numériques valides.")
            val simulation = demoSimulation(day, number(soc), number(capacity), amps.toIntOrNull()
                ?: throw IllegalArgumentException("Le courant doit être un entier entre 1 et 10 A."), DemoScenario.valueOf(scenarioName))
            simulation to ChargingOptimizer().calculate(simulation.request, simulation.calendar)
        }
    }
    Text("SIMULATION · DONNÉES FICTIVES", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    Text("Objectif 80 %", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text("Scénario indépendant du véhicule affiché. Début à 22 h, départ indicatif à 8 h et horizon à 18 h le lendemain, heure de Paris. Le départ indicatif ne coupe pas la recharge.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(soc, { soc = it }, label = { Text("Batterie (%)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        OutlinedTextField(capacity, { capacity = it }, label = { Text("Capacité (kWh)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
    }
    OutlinedTextField(amps, { amps = it }, label = { Text("Courant (A, maximum 10)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Text("Hypothèses : monophasé 230 V, rendement 90 %, batterie de capacité constante. Aucun trajet ni consommation annexe n’est modélisé.", style = MaterialTheme.typography.bodySmall)
    DemoScenario.entries.forEach { scenario ->
        FilterChip(selected = scenarioName == scenario.name, onClick = { scenarioName = scenario.name }, label = { Text(scenario.label) })
    }
    if (result.isFailure) {
        Text(result.exceptionOrNull()?.message ?: "Paramètres invalides", color = MaterialTheme.colorScheme.error)
    } else {
        val (simulation, comparison) = result.getOrThrow()
        val request = simulation.request
        val snapshot = remember(result) { SavedPlan.capture(request, comparison, PlanOrigin.DEMO, Instant.now()) }
        SaveSimulation(snapshot, history, save)
        Text("Du ${timeFormat.format(request.start)} au ${timeFormat.format(request.horizon)}", style = MaterialTheme.typography.bodySmall)
        if (comparison.status == PlanStatus.TARGET_REACHED) {
            Text("Objectif déjà atteint · aucune recharge nécessaire.")
        } else {
            NativePreviewCard(request, comparison, simulation.calendar, request.start, request.start, true)
            EstimateCard("Plan économique · brouillon", comparison.optimized, request)
            EstimateCard("Secours · charge continue", comparison.fallback, request)
            Text("Écart de coût secours − plan : ${amount(comparison.fallback.costEuros - comparison.optimized.costEuros)} €" +
                if (kotlin.math.abs(comparison.fallback.gridKwh - comparison.optimized.gridKwh) > 1e-7) " · énergies différentes, comparaison non équivalente" else "")
            if (comparison.requiresMultipleWindows) Text("Le plan économique nécessite plusieurs fenêtres. Leur programmation autonome sur la Tesla n’est pas validée.")
        }
        Text("Proposition uniquement : aucun programme n’est appliqué. Le secours suppose une voiture branchée au domicile et une limite réglée à 80 % ; il doit encore être confirmé dans Tesla.", color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Text("Tarifs de démonstration", style = MaterialTheme.typography.titleLarge)
        Text("${simulation.tariffs.id} · ${simulation.tariffs.source}\nValidité : ${simulation.tariffs.validFrom} inclus au ${simulation.tariffs.validUntil} exclu.")
        Text("Bleu : HC 0,10 / HP 0,15 €/kWh\nBlanc : HC 0,12 / HP 0,20 €/kWh\nRouge : HC 0,14 / HP 0,70 €/kWh")
        Text("Nuit fictive bleue jusqu’à 6 h, puis rouge dans les scénarios connus. Une couleur inconnue reste inconnue : estimation au tarif maximal de la période. Aucun calendrier EDF réel n’est récupéré.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun EstimateCard(title: String, plan: PlanEstimate, request: ChargingRequest, fictitious: Boolean = true) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("${amount(plan.costEuros)} €", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(if (plan.conservative) "Estimation conservatrice · couleur inconnue" else if (fictitious) "Coût estimé · tarifs fictifs" else "Coût estimé · grille publiée")
            Text("${amount(plan.gridKwh)} kWh au compteur · ${amount(request.gridKw)} kW estimés")
            if (plan.complete) {
                Text("Objectif atteint à ${plan.completion?.let(timeFormat::format) ?: "—"}")
                if (plan.completion?.isAfter(request.departure) == true) Text("F20 : objectif atteint après le départ indicatif (${timeFormat.format(request.departure)}).")
            } else {
                Text("Objectif non atteint : ${amount(plan.estimatedSoc)} % estimés, ${amount(plan.missingGridKwh)} kWh réseau manquants.", color = MaterialTheme.colorScheme.error)
            }
            Text("Complément HP : ${amount(plan.hpCostEuros)} €\nSurcoût HP vs HC de même couleur : ${amount(plan.hpPremiumEuros)} €")
            plan.slots.forEach { slot ->
                HorizontalDivider()
                val color = when (slot.price.color) { TempoColor.BLUE -> "Bleu"; TempoColor.WHITE -> "Blanc"; TempoColor.RED -> "Rouge"; null -> "Inconnue (borne haute)" }
                Text("${timeFormat.format(slot.start)} → ${timeFormat.format(slot.end)}", style = MaterialTheme.typography.bodyMedium)
                Text("$color · ${slot.price.period} · ${amount(slot.gridKwh)} kWh · ${amount(slot.costEuros)} €")
            }
        }
    }
}
