package org.teslatempo.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.teslatempo.history.SavedPlan
import org.teslatempo.history.assistantSummary

@Composable
fun AssistantShare(plan: SavedPlan) {
    val context = LocalContext.current
    var preview by remember(plan.id) { mutableStateOf(false) }
    var feedback by remember(plan.id) { mutableStateOf<String?>(null) }
    val summary = remember(plan) { assistantSummary(plan) }
    OutlinedButton(onClick = { preview = true; feedback = null }) { Text("Préparer pour ChatGPT") }
    Text("Sans clé API : relisez un résumé puis copiez-le ou partagez-le vers ChatGPT. La réponse reste dans ChatGPT.", style = MaterialTheme.typography.bodySmall)
    feedback?.let { Text(it) }
    if (preview) AlertDialog(
        onDismissRequest = { preview = false },
        title = { Text("Résumé à partager") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Seul le texte ci-dessous sera copié ou partagé. Il contient les hypothèses de batterie et les coûts estimés, sans dates, activités, identifiants ni secrets. Le destinataire choisi recevra ce texte.")
                HorizontalDivider()
                Text(summary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, summary)
                    }
                    context.startActivity(Intent.createChooser(send, "Partager le résumé"))
                    preview = false
                } catch (_: ActivityNotFoundException) {
                    preview = false
                    feedback = "Aucune application de partage disponible. Vous pouvez copier le résumé."
                }
            }) { Text("Partager…") }
        },
        dismissButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Simulation TeslaTempo", summary))
                preview = false
                feedback = "Résumé copié. Collez-le dans une conversation ChatGPT si vous souhaitez l'envoyer."
            }) { Text("Copier") }
            TextButton(onClick = { preview = false }) { Text("Annuler") }
        },
    )
}
