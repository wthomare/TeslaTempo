package org.teslatempo.drive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

@Composable
fun DriveScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by DriveRuntime.state.collectAsStateWithLifecycle()
    var vin by remember { mutableStateOf("") } // Never in saved-state Bundles.
    var feedback by remember { mutableStateOf<String?>(null) }
    var demo by remember { mutableStateOf(false) }
    var pairingConfirmation by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(DriveService.START) }
    var pending by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(CockpitPreferences.load(context)) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        try { vin = withContext(Dispatchers.IO) { DriveIdentity(context).loadVin() }.orEmpty() }
        catch (_: Exception) { feedback = "VIN enregistré illisible. Saisissez-le à nouveau." }
        while (isActive) { now = SystemClock.elapsedRealtime(); delay(500) }
    }
    fun startSession() {
        if (pending || state.running) return
        pending = true; demo = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { DriveIdentity(context).saveVin(vin) }
                context.startForegroundService(Intent(context, DriveService::class.java).setAction(action))
                feedback = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                feedback = if (e is DriveFailure) e.userMessage else "Impossible de démarrer. Vérifiez les permissions Bluetooth et notifications."
            } finally { pending = false }
        }
    }
    val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startSession()
        else feedback = "Autorisez les appareils à proximité et les notifications pour connecter le cockpit et pouvoir l’arrêter."
    }
    fun requestSession(pair: Boolean) {
        try { normalizedVin(vin) } catch (e: DriveFailure) { feedback = e.userMessage; return }
        action = if (pair) DriveService.PAIR else DriveService.START
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startSession()
        else permissionLauncher.launch(permissions)
    }
    fun openWaze(destination: DriveDestination?, overlay: Boolean) {
        try {
            context.packageManager.getPackageInfo("com.waze", 0)
            if (overlay && !Settings.canDrawOverlays(context)) {
                feedback = "Autorisez la superposition, revenez ici puis relancez Waze avec le cockpit."
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                return
            }
            val uri = destination?.wazeLink() ?: "https://waze.com/ul?utm_source=teslatempo"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.waze"))
            if (overlay) DriveRuntime.requestOverlay = true
        } catch (_: PackageManager.NameNotFoundException) { feedback = "Waze n’est pas installé. Installez-le depuis votre boutique d’applications." }
        catch (_: Exception) { feedback = "Impossible d’ouvrir Waze ou les réglages de superposition." }
    }

    Text("Votre cockpit local", style = MaterialTheme.typography.titleLarge)
    Text("Bluetooth uniquement · aucune lecture via le relais ou l’API Tesla", style = MaterialTheme.typography.bodySmall)
    Text(state.message, color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Aperçu de démonstration")
        Switch(checked = demo && !state.running, onCheckedChange = { demo = it }, enabled = !state.running)
    }
    if (demo && !state.running) Text("DÉMONSTRATION · chiffres fictifs, aucun Bluetooth activé")
    val text = if (demo && !state.running) CockpitText("73 %", if (preferences.showRange) "418 km" else "") else state.cockpit(now, preferences.showRange)
    key(preferences.size) {
        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
            AndroidView(factory = { CockpitBlock(it,preferences.size) }, update = { it.render(text) })
        }
    }
    Text("Batterie et autonomie de référence fournies par Tesla en BLE. L’autonomie affichée n’est pas une prévision pour votre trajet.", style = MaterialTheme.typography.bodySmall)
    fun age(at: Long?) = if (at == null) "jamais reçue" else "il y a ${((now-at).coerceAtLeast(0)/1000)} s"
    Text("Batterie/autonomie : ${age(state.chargeAt)}", style = MaterialTheme.typography.bodySmall)
    Text("Les valeurs deviennent — après 60 s sans nouvelle mesure.", style = MaterialTheme.typography.bodySmall)
    if (!state.running) {
        OutlinedTextField(vin, { vin = it.take(17) }, label = { Text("VIN de la voiture (17 caractères)") },
            singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        Text("Le VIN reste chiffré sur ce téléphone. La clé BLE est distincte du jeton du relais et de la clé de l’application Tesla.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { requestSession(false) }, enabled = !pending, modifier = Modifier.fillMaxWidth()) { Text("Connecter en BLE") }
        OutlinedButton(onClick = { pairingConfirmation = true }, enabled = !pending, modifier = Modifier.fillMaxWidth()) { Text("Ajouter une clé de lecture à la voiture") }
    } else {
        OutlinedButton(onClick = { context.startService(Intent(context,DriveService::class.java).setAction(DriveService.STOP)) }, modifier = Modifier.fillMaxWidth()) { Text("Arrêter le cockpit") }
    }
    Button(onClick = { openWaze(null,true) }, enabled = state.connected && !demo, modifier = Modifier.fillMaxWidth()) { Text("Lancer Waze avec le cockpit") }
    TextButton(onClick = { openWaze(null,false) }) { Text("Ouvrir Waze seul") }
    HorizontalDivider()
    Text("Destination Tesla", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = { DriveRuntime.syncDestination(); feedback = null }, enabled = state.connected) { Text("Synchroniser la destination Tesla") }
    val destination = state.destination?.takeIf { fresh(it.at, now,60_000) }
    if (destination != null) {
        Text(destination.name)
        Text("Reçue ${age(destination.at)}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { openWaze(destination,false) }) { Text("Ouvrir cette destination dans Waze") }
    } else Text("Aucune destination récente. Waze reste utilisable directement.", style = MaterialTheme.typography.bodySmall)
    Text("Waze calcule son propre itinéraire. Les étapes et arrêts Tesla ne sont pas copiés.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Affichage du cockpit", style = MaterialTheme.typography.titleMedium)
    Text("Taille des chiffres : ${preferences.size.toInt()}")
    Slider(value = preferences.size, onValueChange = { preferences = preferences.copy(size=it); preferences.save(context) }, valueRange = 24f..48f, enabled = !state.running)
    Text("Décalage depuis le haut : ${preferences.offset}")
    Slider(value = preferences.offset.toFloat(), onValueChange = { preferences = preferences.copy(offset=it.toInt()); preferences.save(context) }, valueRange = 16f..180f, enabled = !state.running)
    Text("Opacité : ${(preferences.opacity*100).toInt()} %")
    Slider(value = preferences.opacity, onValueChange = { preferences = preferences.copy(opacity=it); preferences.save(context) }, valueRange = 0.3f..0.75f, enabled = !state.running)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Afficher l’autonomie en km")
        Switch(checked = preferences.showRange, onCheckedChange = { preferences = preferences.copy(showRange=it); preferences.save(context) }, enabled = !state.running)
    }
    Text("Réglez la position à l’arrêt pour laisser les indications Waze visibles. Arrêtez le cockpit depuis sa notification ou ici. Retirer l’application des tâches récentes arrête aussi la session.", style = MaterialTheme.typography.bodySmall)
    if (pairingConfirmation) AlertDialog(onDismissRequest = { pairingConfirmation = false },
        title = { Text("Autoriser une clé de lecture") },
        text = { Text("À l’arrêt, dans la voiture : envoyez la demande, présentez votre carte-clé sur le lecteur et confirmez sur l’écran Tesla. Seul le rôle Vehicle Monitor est demandé. L’envoi de la demande ne confirme pas l’ajout. Reconnectez ensuite le BLE pour le vérifier.") },
        confirmButton = { TextButton(onClick = { pairingConfirmation=false; requestSession(true) }) { Text("Envoyer la demande") } },
        dismissButton = { TextButton(onClick = { pairingConfirmation=false }) { Text("Annuler") } })
}
