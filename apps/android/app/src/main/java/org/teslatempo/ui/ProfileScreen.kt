package org.teslatempo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.teslatempo.personal.*
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ProfileScreen(state: PersonalState, save: (PersonalSettings) -> Unit) {
    if (!state.ready) { Text("Chargement des paramètres…"); return }
    val original = state.settings
    var capacity by rememberSaveable { mutableStateOf(if (original.confirmed) original.capacityKwh.toString() else "") }
    var target by rememberSaveable { mutableStateOf(original.targetPercent.toString()) }
    var amps by rememberSaveable { mutableStateOf(original.currentAmps.toString()) }
    var efficiency by rememberSaveable { mutableStateOf((original.efficiency * 100).toString()) }
    var departure by rememberSaveable { mutableStateOf(original.indicativeDeparture) }
    // A JSON string keeps the complete unsaved draft across rotation, without saving it to disk.
    var draft by rememberSaveable { mutableStateOf(PersonalCodec.encode(original)) }
    val current = PersonalCodec.draft(draft)
    var selectedDay by rememberSaveable { mutableStateOf(0) }
    var from by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var exceptionKind by rememberSaveable { mutableStateOf(DayKind.VACATION.name) }
    var exceptionLeave by rememberSaveable { mutableStateOf("08:00") }
    var exceptionBack by rememberSaveable { mutableStateOf("18:00") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Mon véhicule et ma semaine", style = MaterialTheme.typography.titleLarge)
    Text("La capacité utile de votre Model Y doit être renseignée : le nom du modèle ne suffit pas. Une estimation reste modifiable. La semaine préremplie est un exemple à adapter avant enregistrement.")
    NumberField("Capacité utile estimée (kWh)", capacity) { capacity = it }
    NumberField("Cible de batterie (%)", target) { target = it }
    NumberField("Courant maximal (A, 1 à 10)", amps) { amps = it }
    NumberField("Rendement estimé (%)", efficiency) { efficiency = it }
    OutlinedTextField(departure, { departure = it }, label = { Text("Départ indicatif (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Ce départ ne coupe pas la recharge. Les absences du calendrier, elles, retirent réellement des périodes de disponibilité.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Semaine type · Europe/Paris", style = MaterialTheme.typography.titleLarge)
    Choice(DayOfWeek.of(selectedDay + 1).getDisplayName(TextStyle.FULL, Locale.FRENCH), DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.FRENCH) }) { selectedDay = it }
    val day = current.week[selectedDay]
    Choice(day.kind.label, DayKind.entries.map { it.label }) { index ->
        draft = PersonalCodec.encode(current.copy(week = current.week.toMutableList().also { it[selectedDay] = day.copy(kind = DayKind.entries[index]) }))
    }
    if (day.kind == DayKind.OFFICE || day.kind == DayKind.CUSTOM) {
        fun editDay(value: DaySchedule) {
            draft = PersonalCodec.encode(current.copy(week = current.week.toMutableList().also { it[selectedDay] = value }))
        }
        OutlinedTextField(day.leave, { editDay(day.copy(leave = it)) }, label = { Text("Départ réel (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(day.back, { editDay(day.copy(back = it)) }, label = { Text("Retour réel (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Un retour avant le départ signifie un retour le lendemain.", style = MaterialTheme.typography.bodySmall)
    }
    current.week.forEachIndexed { i, row ->
        Text("${DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.FRENCH)} : ${row.kind.label}" +
            if (row.kind == DayKind.OFFICE || row.kind == DayKind.CUSTOM) " · absent ${row.leave}–${row.back}" else "", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Text("Exceptions et vacances", style = MaterialTheme.typography.titleLarge)
    Text("Une exception remplace la semaine type sur les dates incluses. Vacances signifie absent toute la journée ; choisissez Domicile pour des congés à la maison.")
    OutlinedTextField(from, { from = it }, label = { Text("Du (AAAA-MM-JJ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(until, { until = it }, label = { Text("Au inclus (AAAA-MM-JJ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Choice(DayKind.valueOf(exceptionKind).label, DayKind.entries.map { it.label }) { exceptionKind = DayKind.entries[it].name }
    if (DayKind.valueOf(exceptionKind) in listOf(DayKind.OFFICE, DayKind.CUSTOM)) {
        OutlinedTextField(exceptionLeave, { exceptionLeave = it }, label = { Text("Départ exception (HH:mm)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exceptionBack, { exceptionBack = it }, label = { Text("Retour exception (HH:mm)") }, modifier = Modifier.fillMaxWidth())
    }
    OutlinedButton(onClick = {
        try {
            val row = ScheduleException(from, until, DaySchedule(DayKind.valueOf(exceptionKind), exceptionLeave, exceptionBack))
            val next = current.copy(exceptions = current.exceptions + row).validate()
            draft = PersonalCodec.encode(next); from = ""; until = ""; error = null
        } catch (_: Exception) { error = "Exception invalide : vérifiez dates, horaires et absence de chevauchement." }
    }) { Text("Ajouter l’exception au brouillon") }
    current.exceptions.forEachIndexed { index, row ->
        Text("${row.from} → ${row.until} · ${row.day.kind.label} · ${row.day.leave}–${row.day.back}")
        TextButton(onClick = { draft = PersonalCodec.encode(current.copy(exceptions = current.exceptions.filterIndexed { i, _ -> i != index })) }) { Text("Supprimer cette exception") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        try {
            fun number(s: String) = s.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("Renseignez tous les nombres, dont la capacité utile estimée.")
            val value = current.copy(capacityKwh = number(capacity), targetPercent = number(target), currentAmps = amps.toIntOrNull() ?: throw IllegalArgumentException("Le courant doit être un entier entre 1 et 10 A."),
                efficiency = number(efficiency) / 100, indicativeDeparture = departure).validate()
            error = null; save(value)
        } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "Paramètres invalides." else "Paramètres invalides." }
    }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer le profil") }
    Text("Sauvegarde sur ce téléphone uniquement. Aucun horaire ni paramètre personnel n’est transmis au service Tempo.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun NumberField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
@Composable
internal fun Choice(label: String, items: List<String>, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, item -> DropdownMenuItem(text = { Text(item) }, onClick = { choose(i); expanded = false }) }
        }
    }
}
