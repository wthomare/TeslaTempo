package org.teslatempo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.launch
import org.teslatempo.BuildConfig
import org.teslatempo.domain.Dashboard
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TeslaTempoApp(model: DashboardViewModel, personal: PersonalViewModel, history: HistoryViewModel, assistant: AssistantViewModel, observations: ObservationsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val health by model.health.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val personalState by personal.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, personal) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { personal.onResume(); delay(60_000) }
        }
    }
    LaunchedEffect(lifecycle, state.relayUrl) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) { model.checkRelay(); delay(60_000) }
            } finally { model.pauseHealth() }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    val colors = darkColorScheme(
        primary = Color(0xFFF0F0F0), onPrimary = Color(0xFF171717),
        background = Color(0xFF101010), surface = Color(0xFF191919),
        surfaceVariant = Color(0xFF26282A), surfaceContainerHighest = Color(0xFF232527),
        secondaryContainer = Color(0xFF1D1D1D), onSecondaryContainer = Color(0xFFE4E8ED),
        onBackground = Color(0xFFF4F4F4), onSurface = Color(0xFFF4F4F4),
        onSurfaceVariant = Color(0xFFB8BDC4), outline = Color(0xFF686D74),
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp))) {
        BackHandler(page != "home") { page = "home" }
        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                listOf(Triple("home", "Recharge", "◉"), Triple("plan", "Plan", "≡"), Triple("drive", "Conduite", "◇"), Triple("relay", "Réglages", "⚙")).forEach { (target, label, symbol) ->
                    NavigationBarItem(selected = page == target, onClick = { page = target },
                        icon = { Text(symbol, fontSize = 22.sp) }, label = { Text(label) })
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("TESLA TEMPO", style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when (page) { "drive" -> "Conduite"; "relay" -> "Mon relais"; "demo", "plan" -> "Planification"; "profile" -> "Mon profil"; "tempo" -> "Tempo"; "history" -> "Historique"; "observations" -> "Relevés"; else -> "Ma recharge" }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                    if (page != "home") TextButton(onClick = { page = "home" }) { Text("Accueil") }
                }
                if (page == "home" || page == "relay") {
                    RelayConnectionCard(health, state.relayUrl, { page = "relay" }) { scope.launch { model.checkRelay() } }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(message, Modifier.padding(16.dp))
                    }
                }
                if (page == "drive") {
                    org.teslatempo.drive.DriveScreen()
                } else if (page == "demo") {
                    PlannerScreen(historyState, history::save)
                } else if (page == "observations") {
                    ObservationsScreen(state.dashboard?.selected, observations)
                } else if (page == "history") {
                    HistoryScreen(historyState, history::delete, history::clear, assistant)
                } else if (page == "profile") {
                    ProfileScreen(personalState) { value -> personal.save(value) { page = "home" } }
                } else if (page == "tempo") {
                    TempoDetails(personalState, personal::refresh)
                } else if (page == "plan") {
                    PersonalPlannerScreen(personalState, historyState, history::save) { page = "profile" }
                } else if (page == "relay") {
                    Settings(state, { url, token -> model.connect(url, token) { page = "home" } },
                        { model.useDemo { page = "home" } }, model::diagnose)
                } else {
                    state.dashboard?.let { DashboardContent(it, state.loading, model::refresh) }
                    TempoSummary(personalState, personal::refresh) { page = "tempo" }
                    if (state.dashboard == null && !state.loading) {
                        Text("Aucune donnée disponible. Vérifiez la connexion au relais ou ouvrez les réglages.")
                    }
                    Button(onClick = { page = "plan" }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mon plan personnel") }
                    Column(Modifier.fillMaxWidth()) {
                        HomeRow("Mon profil et calendrier", "Vos habitudes et votre batterie") { page = "profile" }
                        HomeRow("Relevés de recharge", "Suivre les mesures disponibles") { page = "observations" }
                        HomeRow("Historique des simulations", "Retrouver vos plans") { page = "history" }
                        HomeRow("Simuler un plan F20", "Explorer avec des données fictives") { page = "demo" }
                    }
                    TextButton(onClick = { model.refresh() }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Actualiser les données du véhicule") }
                    Text("Lecture seule · Aucune commande envoyée au véhicule", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(data: Dashboard, loading: Boolean, select: (String?) -> Unit) {
    Text(if (data.simulated) "DÉMONSTRATION" else "VOTRE VÉHICULE", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            }
        }
    }
    var showDetails by remember { mutableStateOf(false) }
    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Masquer les détails" else "Détails des données") }
    if (showDetails) Text("Reçu à ${DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(data.receivedAt)} · heure du téléphone\nLa date de mesure du véhicule n’est pas disponible ; le relais peut servir son cache de 30 s.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Settings(state: ScreenState, connect: (String, String) -> Unit, demo: () -> Unit, diagnose: (String, String) -> Unit) {
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
    OutlinedButton(onClick = { diagnose(url, token) }, enabled = !state.loading && url.isNotBlank()) { Text("Diagnostiquer la connexion") }
    Text("Vérifie uniquement le relais et son statut, sans réveiller ni interroger la voiture. Laissez le jeton vide pour utiliser celui enregistré à la même adresse.", style = MaterialTheme.typography.bodySmall)
    state.diagnostic?.let { Text(it) }
    Text("Application ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodySmall)
    var diagnosticsOpen by remember { mutableStateOf(false) }
    TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) { Text(if (diagnosticsOpen) "Masquer les diagnostics privés" else "Diagnostics privés") }
    if (diagnosticsOpen) DiagnosticsCard()
    HorizontalDivider()
    Text("Mode démonstration", style = MaterialTheme.typography.titleLarge)
    Text("Explorez l’interface avec un véhicule fictif. Revenir à ce mode efface la connexion enregistrée sur ce téléphone.")
    OutlinedButton(onClick = demo, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Démonstration") }
    if (BuildConfig.DEBUG) {
        Text("Développement : HTTP autorisé uniquement vers localhost, 127.0.0.1 ou 10.0.2.2. Utilisez un jeton de test local.", style = MaterialTheme.typography.bodySmall)
    }
}
