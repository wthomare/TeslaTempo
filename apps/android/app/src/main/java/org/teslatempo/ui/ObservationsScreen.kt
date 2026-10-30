package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.teslatempo.domain.Vehicle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ObservationsScreen(vehicle: Vehicle?, model: ObservationsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var shown by remember(vehicle?.id) { mutableStateOf(30) }
    var clear by remember(vehicle?.id) { mutableStateOf(false) }
    Text("Relevés de recharge", style=MaterialTheme.typography.titleLarge)
    Text("Instantanés reçus lors des actualisations du véhicule, conservés chiffrés sur votre relais. Aucune surveillance continue. Maximum 300 relevés par véhicule, les plus anciens sont supprimés automatiquement.")
    Text("Le compteur d’énergie appartient à la session signalée par Tesla. Il peut se répéter ou repartir à zéro : les relevés ne sont jamais additionnés. Aucun coût réel ni historique complet de sessions n’est déduit.",style=MaterialTheme.typography.bodySmall)
    if(vehicle==null){Text("Sélectionnez d’abord un véhicule depuis le tableau de bord connecté au relais.");return}
    Text(vehicle.name)
    OutlinedButton(onClick={model.load(vehicle.id)},enabled=!state.busy){Text("Charger les relevés")}
    if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(state.vehicleId==vehicle.id){
        state.message?.let{Text(it)}
        state.history?.let{history ->
            if(history.simulated)Text("Mode démonstration : aucun relevé réel.")
            else if(history.samples.isEmpty())Text("Aucun relevé. Les prochaines lectures réelles pourront être conservées si leur horodatage est valide et récent.")
            if (history.samples.isNotEmpty()) {
                val coverage = org.teslatempo.history.observationCoverage(history)
                Text("${coverage.count} relevés · plus grand intervalle sans mesure : ${coverage.largestGapSeconds?.let { java.time.Duration.ofSeconds(it).toMinutes().toString() + " min" } ?: "inconnu"}.")
                if (coverage.counterDecreases > 0) Text("${coverage.counterDecreases} baisse(s) du compteur observée(s). Une remise à zéro ne suffit pas à identifier une session complète.")
                ObservationExportButton(history)
            }
            fun number(n: Double?,unit: String)=n?.let{String.format(Locale.FRANCE,"%.2f",it)+" "+unit}?:"Inconnu"
            val formatter=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss xxx").withZone(ZoneId.of("Europe/Paris"))
            history.samples.take(shown).forEach { sample ->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(formatter.format(sample.at))
                        Text(when(sample.state){"Charging"->"En charge";"Complete"->"Charge terminée selon le véhicule";"Disconnected"->"Débranchée";"Stopped"->"Arrêtée";"Starting"->"Démarrage";"NoPower"->"Pas d’alimentation";else->"État inconnu"})
                        Text("Batterie : ${number(sample.battery,"%")}\nCompteur de session : ${number(sample.sessionCounter,"kWh")}\nCourant relevé : ${number(sample.current,"A")}")
                        Text("Coût : inconnu",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(history.samples.size>shown)TextButton(onClick={shown+=30}){Text("Afficher 30 relevés de plus")}
            if(history.samples.isNotEmpty())OutlinedButton(onClick={clear=true},enabled=!state.busy){Text("Effacer les relevés de ce véhicule")}
        }
    }
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("Effacer les relevés ?")},
        text={Text("Les relevés conservés sur le relais pour ce véhicule seront supprimés. Les simulations locales et les données chez Tesla sont conservées.")},
        confirmButton={TextButton(onClick={clear=false;model.clear(vehicle.id)}){Text("Effacer")}},
        dismissButton={TextButton(onClick={clear=false}){Text("Annuler")}})
}
