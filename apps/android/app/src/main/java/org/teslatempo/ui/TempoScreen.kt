package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.teslatempo.planner.*
import org.teslatempo.tempo.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun colorName(color: TempoColor?) = when (color) {
    TempoColor.BLUE -> "Bleu"; TempoColor.WHITE -> "Blanc"; TempoColor.RED -> "Rouge"; null -> "Inconnue / non publiée"
}
internal fun localTime(raw: String) = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(PARIS).format(Instant.parse(raw))

@Composable
fun TempoSummary(state: PersonalState, refresh: () -> Unit, details: () -> Unit) {
    val today = state.now.atZone(PARIS).toLocalDate()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tempo · couleurs publiées", style = MaterialTheme.typography.titleLarge)
            for ((label, date) in listOf("Aujourd’hui" to today, "Demain" to today.plusDays(1))) {
                val observation = state.tempo.color(date)
                Text("$label ($date) : ${colorName(observation?.color)}")
                observation?.let { Text("Cache vérifié le ${localTime(it.checkedAt)}", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.now.atZone(PARIS).hour < 6) Text("Avant 6 h : HC de la veille (${colorName(state.tempo.color(today.minusDays(1))?.color)}).")
            if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Source indépendante : calendrier-tempo.fr. Les dates du cache restent affichées en cas d’échec réseau.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = refresh, enabled = state.ready && !state.syncing) { Text("Actualiser Tempo") }
            TextButton(onClick = details) { Text("Voir les tarifs et la source") }
        }
    }
}

@Composable
fun TempoDetails(state: PersonalState, refresh: () -> Unit) {
    val uri = LocalUriHandler.current
    Text("Couleurs et tarifs Tempo", style = MaterialTheme.typography.titleLarge)
    Text("Données publiées relayées par un service indépendant, non affilié à EDF ou RTE. Aucune prévision de couleur n’est utilisée.")
    OutlinedButton(onClick = { uri.openUri("$TEMPO_SOURCE/api-tempo") }) { Text("Source : calendrier-tempo.fr · CC BY 4.0") }
    OutlinedButton(onClick = { uri.openUri("https://particulier.edf.fr/content/dam/2-Actifs/Documents/Offres/Grille_prix_Tarif_Bleu.pdf") }) { Text("Consulter la grille officielle EDF") }
    val prices = state.tempo.tariff
    if (prices == null) Text("Aucun tarif récupéré. Un calcul personnel est impossible sans grille.") else {
        Text("Date d’effet publiée : ${prices.effectiveFrom}\nDernier contrôle : ${localTime(prices.checkedAt)}")
        Text("Prix TTC en €/kWh, hors abonnement. À vérifier avec votre contrat. L’API ne publie pas de date de fin : l’application exige un nouveau contrôle sous 7 jours.")
        TempoColor.entries.forEachIndexed { index, color ->
            Text("${colorName(color)} : HC ${String.format(Locale.FRANCE, "%.4f", prices.rates[index * 2])} / HP ${String.format(Locale.FRANCE, "%.4f", prices.rates[index * 2 + 1])} €/kWh")
        }
        try { prices.version(state.now) } catch (e: IllegalArgumentException) { Text(e.message ?: "Tarifs périmés", color = MaterialTheme.colorScheme.error) }
    }
    state.syncMessage?.let { Text(it) }
    Button(onClick = refresh, enabled = state.ready && !state.syncing) { Text("Actualiser Tempo") }
}
