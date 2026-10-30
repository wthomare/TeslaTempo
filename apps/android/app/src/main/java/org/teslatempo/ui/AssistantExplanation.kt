package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.teslatempo.history.SavedPlan
import org.teslatempo.history.assistantSummary

@Composable
fun AssistantExplanation(plan: SavedPlan, model: AssistantViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by remember(plan.id) { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Comprendre avec ChatGPT", style = MaterialTheme.typography.titleMedium)
            Text("Utilise l’abonnement du compte connecté au relais. Aucun envoi automatique. Les explications ne commandent pas le véhicule.")
            OutlinedButton(onClick = model::refresh, enabled = !state.busy) { Text("Vérifier la connexion ChatGPT") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it) }
            state.connection?.let { connection ->
                if (!connection.enabled) Text("Connexion et autorisation de l’abonnement nécessaires. Exécutez le parcours local du guide ChatGPT, puis transférez ses identifiants vers votre relais.")
                else {
                    Text("Compte connecté · inscription ${connection.account.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(onClick = { menu = true }, enabled = !state.busy) {
                            Text(state.models.find { it.id == state.selected }?.name ?: "Choisir un modèle")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            state.models.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) },
                                onClick = { model.select(choice.id); menu = false }) }
                        }
                    }
                    if (state.models.isEmpty()) Text("Aucun modèle disponible pour ce compte.")
                    Button(onClick = { confirm = true }, enabled = !state.busy && state.selected != null) { Text("Relire avant envoi") }
                }
                if (connection.connected) TextButton(onClick = { disconnect = true }, enabled = !state.busy) { Text("Déconnecter ChatGPT du relais") }
            }
            if (state.planId == plan.id) state.answer?.let {
                Text("Explication générée · à vérifier", style = MaterialTheme.typography.titleSmall)
                Text(it)
                Text("Le calcul archivé reste la référence. Aucun programme appliqué. Cette réponse n’est pas enregistrée dans l’historique.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Envoyer ce résumé à ChatGPT ?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Modèle : ${state.models.find { it.id == state.selected }?.name.orEmpty()}. Le résumé ci-dessous sera transmis via votre relais à OpenAI et consommera les quotas du compte connecté. Sans dates, activités ni identifiants du véhicule.")
            HorizontalDivider(); Text(assistantSummary(plan))
        } },
        confirmButton = { TextButton(onClick = { confirm = false; model.explain(plan) }, enabled = !state.busy) { Text("Envoyer") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text("Déconnecter ChatGPT ?") },
        text = { Text("Les accès ChatGPT actifs du relais seront retirés. La connexion Tesla et vos simulations seront conservées.") },
        confirmButton = { TextButton(onClick = { disconnect = false; model.disconnect() }) { Text("Déconnecter") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Annuler") } })
}
