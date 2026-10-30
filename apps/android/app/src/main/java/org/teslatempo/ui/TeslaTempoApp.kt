package org.teslatempo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.teslatempo.BuildConfig
import org.teslatempo.domain.Dashboard
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TeslaTempoApp(model: DashboardViewModel, personal: PersonalViewModel, history: HistoryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val personalState by personal.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, personal) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { personal.onResume(); delay(60_000) }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    val colors = darkColorScheme(
        primary = Color(0xFF82B5FF), onPrimary = Color(0xFF08192E),
        background = Color(0xFF101112), surface = Color(0xFF171819),
        surfaceVariant = Color(0xFF26282A), surfaceContainerHighest = Color(0xFF232527),
        secondaryContainer = Color(0xFF20262D), onSecondaryContainer = Color(0xFFE4E8ED),
        onBackground = Color(0xFFF4F4F4), onSurface = Color(0xFFF4F4F4),
        onSurfaceVariant = Color(0xFFB8BDC4), outline = Color(0xFF686D74),
    )
    MaterialTheme(colorScheme = colors) {
        BackHandler(page != "home") { page = "home" }
        Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("TESLA TEMPO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(when (page) { "relay" -> "Mon relais"; "demo", "plan" -> "Planification"; "profile" -> "Mon profil"; "tempo" -> "Tempo"; "history" -> "Historique"; else -> "Ma recharge" }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { page = if (page == "home") "relay" else "home" }) { Text(if (page != "home") "Accueil" else "Réglages") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(message, Modifier.padding(16.dp))
                    }
                }
                if (page == "demo") {
                    PlannerScreen(historyState, history::save)
                } else if (page == "history") {
                    HistoryScreen(historyState, history::delete, history::clear)
                } else if (page == "profile") {
                    ProfileScreen(personalState) { value -> personal.save(value) { page = "home" } }
                } else if (page == "tempo") {
                    TempoDetails(personalState, personal::refresh)
                } else if (page == "plan") {
                    PersonalPlannerScreen(personalState, historyState, history::save) { page = "profile" }
                } else if (page == "relay") {
                    Settings(state, { url, token -> model.connect(url, token) { page = "home" } },
                        { model.useDemo { page = "home" } })
                } else {
                    Button(onClick = { page = "plan" }, modifier = Modifier.fillMaxWidth()) { Text("Mon plan personnel") }
                    OutlinedButton(onClick = { page = "profile" }, modifier = Modifier.fillMaxWidth()) { Text("Mon profil et calendrier") }
                    OutlinedButton(onClick = { page = "history" }, modifier = Modifier.fillMaxWidth()) { Text("Historique des simulations") }
                    TextButton(onClick = { page = "demo" }) { Text("Simuler un plan F20") }
                    state.dashboard?.let { DashboardContent(it, state.relayUrl, state.loading, model::refresh) }
                    TempoSummary(personalState, personal::refresh) { page = "tempo" }
                    if (state.dashboard == null && !state.loading) {
                        Text("Aucune donnée disponible. Vérifiez la connexion au relais ou ouvrez les réglages.")
                    }
                    OutlinedButton(onClick = { model.refresh() }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Actualiser")
                    }
                    Text("Lecture seule · Aucune commande envoyée au véhicule", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(data: Dashboard, relayUrl: String?, loading: Boolean, select: (String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (data.simulated) "DÉMONSTRATION" else "RELAIS TESLA · LECTURE SEULE", fontWeight = FontWeight.Bold)
            Text(when {
                relayUrl == null -> "Véhicule fictif, disponible hors connexion."
                data.simulated -> "Données simulées reçues de votre relais."
                else -> "Données reçues de votre relais personnel."
            })
            relayUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (data.vehicles.size > 1) {
        Text("Véhicules · première page", style = MaterialTheme.typography.titleSmall)
        data.vehicles.forEach { vehicle ->
            FilterChip(selected = data.selected?.id == vehicle.id, onClick = { select(vehicle.id) },
                enabled = !loading, label = { Text(vehicle.name) })
        }
    }
    if (data.selected == null) {
        Text("Aucun véhicule renvoyé par le relais.")
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(data.selected.name, style = MaterialTheme.typography.titleLarge)
                Text(data.charge?.battery?.let { "$it %" } ?: "Batterie inconnue", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                data.charge?.battery?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                Text("Limite de charge : ${data.charge?.limit?.let { "$it %" } ?: "inconnue"}")
                Text(when (data.charge?.state) {
                    "Charging" -> "Recharge en cours"
                    "Stopped" -> "Recharge arrêtée"
                    "Disconnected" -> "Véhicule débranché"
                    "Complete" -> "Recharge terminée"
                    "Starting" -> "Démarrage de la recharge"
                    else -> "État de charge indisponible"
                }, style = MaterialTheme.typography.titleMedium)
                Text("Courant demandé : ${data.charge?.current?.let { "$it A" } ?: "inconnu"}")
                Text("Le branchement et la puissance réelle ne sont pas déduits du courant demandé.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("Reçu à ${DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(data.receivedAt)} · heure du téléphone\nLa date de mesure du véhicule n’est pas disponible ; le relais peut servir son cache de 30 s.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Settings(state: ScreenState, connect: (String, String) -> Unit, demo: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(state.relayUrl ?: "") }
    // Do not persist the typed token in saved instance state, logs or a Bundle.
    var token by remember { mutableStateOf("") }
    Text("Connectez l’application à votre relais personnel. Le jeton demandé ici est celui du relais, pas votre mot de passe Tesla.")
    OutlinedTextField(url, { url = it }, label = { Text("URL du relais") }, placeholder = { Text("https://relais.exemple.fr") },
        singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it }, label = { Text("Jeton du relais") }, visualTransformation = PasswordVisualTransformation(),
        singleLine = true, enabled = !state.loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Text("La connexion est vérifiée avant enregistrement. Le jeton est chiffré avec une clé Android Keystore et exclu des sauvegardes.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { connect(url, token) }, enabled = !state.loading && url.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Connecter le relais")
    }
    HorizontalDivider()
    Text("Mode démonstration", style = MaterialTheme.typography.titleLarge)
    Text("Explorez l’interface avec un véhicule fictif. Revenir à ce mode efface la connexion enregistrée sur ce téléphone.")
    OutlinedButton(onClick = demo, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Démonstration") }
    if (BuildConfig.DEBUG) {
        Text("Développement : HTTP autorisé uniquement vers localhost, 127.0.0.1 ou 10.0.2.2. Utilisez un jeton de test local.", style = MaterialTheme.typography.bodySmall)
    }
}
