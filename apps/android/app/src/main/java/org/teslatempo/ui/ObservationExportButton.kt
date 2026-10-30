package org.teslatempo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.teslatempo.history.ObservationExport
import org.teslatempo.network.ObservationHistory

@Composable
fun ObservationExportButton(history: ObservationHistory) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val content = pending; pending = null
        if (uri != null && content != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(content.toByteArray(Charsets.UTF_8)) } }
                message = "Relevés exportés. Les compteurs ne constituent pas un total d’énergie."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Export non confirmé ; vérifiez le fichier choisi." }
        }
    }
    Text("Exporter les ${history.samples.size} relevés chargés, sans identifiant de véhicule. Le fichier contient des dates et mesures personnelles ; choisissez un emplacement privé.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        runCatching { ObservationExport.csv(history) }.onSuccess { pending = it; save.launch("teslatempo-releves.csv") }
            .onFailure { message = "Relevés non valides pour l’export." }
    }, enabled = pending == null) { Text("Exporter les relevés CSV") }
    message?.let { Text(it) }
}
