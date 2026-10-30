package org.teslatempo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.teslatempo.diagnostics.DiagnosticLog
import org.teslatempo.diagnostics.DiagnosticEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DiagnosticsCard() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<DiagnosticEvent>()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<DiagnosticEvent>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { rows=withContext(Dispatchers.IO) { DiagnosticLog.snapshot() };pending=DiagnosticLog.pending() }
    LaunchedEffect(Unit) { reload() }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val raw=export;export=null
        if(uri!=null && raw!=null)scope.launch {
            message=runCatching { withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { it.write(raw.toByteArray()) } };"Diagnostic exporté." }
                .getOrElse { "Export non confirmé." }
        }
    }
    HorizontalDivider()
    Text("Diagnostics privés",style=MaterialTheme.typography.titleLarge)
    Text("Les 500 derniers événements techniques sont conservés au maximum sept jours sur ce téléphone : version, opération, durée, code d’erreur et identifiants aléatoires. Aucun jeton, contenu ChatGPT, calendrier, position ou identifiant de véhicule.")
    Text("L’envoi est facultatif, vers votre relais enregistré. En cas d’échec, seul le rapport confirmé sera réessayé à une prochaine ouverture. Il sera abandonné si la connexion enregistrée change. Les arrêts forcés Android ne produisent pas tous un événement de crash.",style=MaterialTheme.typography.bodySmall)
    Text("${rows.size} événements locaux" + if(pending) " · rapport en attente" else "")
    val formatter=DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault())
    rows.takeLast(5).reversed().forEach { e ->
        SelectionContainer { Text("${formatter.format(Instant.ofEpochMilli(e.at))} · ${e.operation} · ${e.outcome} · HTTP ${e.code}\n${e.request_id}",style=MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick={scope.launch { reload() }},enabled=!busy) { Text("Actualiser le diagnostic local") }
    OutlinedButton(onClick={preview=rows.takeLast(100)},enabled=rows.isNotEmpty() && !busy) { Text("Envoyer un diagnostic") }
    OutlinedButton(onClick={scope.launch { export=withContext(Dispatchers.IO) { DiagnosticLog.export() };save.launch("teslatempo-diagnostic.json") }},enabled=rows.isNotEmpty() && export==null && !busy) { Text("Exporter le diagnostic JSON") }
    if(pending)TextButton(onClick={busy=true;scope.launch {
        try { val ok=withContext(Dispatchers.IO) { DiagnosticLog.retryPending(true) };message=if(ok)"Rapport reçu par le relais." else "Envoi non confirmé ou rapport abandonné après changement de connexion. Vous pouvez exporter le diagnostic.";reload() }
        finally { busy=false }
    }},enabled=!busy) { Text("Réessayer le rapport confirmé") }
    TextButton(onClick={scope.launch { withContext(Dispatchers.IO) { DiagnosticLog.clear() };reload();message="Diagnostics locaux et attente effacés. Les rapports déjà transmis restent soumis à la rétention du NAS." }},enabled=!busy) { Text("Effacer les diagnostics locaux") }
    message?.let { SelectionContainer { Text(it) } }
    preview?.let { selected ->
        AlertDialog(onDismissRequest={preview=null},title={Text("Transmettre ${selected.size} événements ?")},
            text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
                Text("Voici le contenu technique du rapport. Un nouveau rapport remplace l’attente précédente ; un envoi déjà en cours peut aboutir. Cette autorisation concerne ce rapport uniquement.")
                Text(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(selected),style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(onClick={preview=null;busy=true;scope.launch {
                try {
                    val id=withContext(Dispatchers.IO) { DiagnosticLog.consent(selected) }
                    val ok=withContext(Dispatchers.IO) { DiagnosticLog.retryPending(true) }
                    message=(if(ok)"Rapport reçu par le relais. Référence : " else "Rapport conservé en attente. Référence : ")+id
                    reload()
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { message="Envoi impossible. Configurez le relais ou exportez le diagnostic." }
                finally { busy=false }
            }}) { Text("Confirmer l’envoi") }},dismissButton={TextButton(onClick={preview=null}) { Text("Annuler") }})
    }
}
