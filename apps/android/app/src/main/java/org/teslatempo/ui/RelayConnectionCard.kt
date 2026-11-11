package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RelayConnectionCard(health: RelayHealth, url: String?, settings: () -> Unit, check: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val title = if (health.checking) "Vérification du relais…" else when (health.link) {
        RelayLink.NOT_CONFIGURED -> "Relais non configuré"
        RelayLink.UNKNOWN -> "Connexion à vérifier"
        RelayLink.CONNECTED -> "Relais connecté"
        RelayLink.UNAVAILABLE -> "Relais injoignable ou indisponible"
        RelayLink.REJECTED -> "Jeton du relais refusé"
    }
    val color = when {
        health.checking -> MaterialTheme.colorScheme.onSurfaceVariant
        health.link == RelayLink.CONNECTED -> Color(0xFF82D6A0)
        health.link in setOf(RelayLink.REJECTED, RelayLink.UNAVAILABLE) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("●", color = color)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (health.link == RelayLink.CONNECTED && !health.checking) Text(
                when { health.simulated -> "Relais en mode démonstration"
                    health.teslaConnected == true -> "Compte Tesla associé · véhicule à vérifier"
                    else -> "Compte Tesla à connecter" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                url?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                health.checkedAt?.let { Text("Dernier contrôle : " + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(it), style = MaterialTheme.typography.bodySmall) }
                health.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Contrôle chaque minute lorsque l’application est visible. Aucun appel à la voiture.", style = MaterialTheme.typography.bodySmall)
                if (url == null) TextButton(onClick = settings) { Text("Configurer le relais") }
                else TextButton(onClick = check, enabled = !health.checking) { Text("Vérifier maintenant") }
            }
        }
    }
}

@Composable
internal fun HomeRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
