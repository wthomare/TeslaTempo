package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.teslatempo.personal.*
import org.teslatempo.tempo.*
import java.time.*

data class PersonalState(
    val ready: Boolean = false, val settings: PersonalSettings = PersonalSettings(),
    val tempo: TempoCache = TempoCache(), val syncing: Boolean = false, val saving: Boolean = false,
    val error: String? = null, val syncMessage: String? = null, val now: Instant = Instant.now(),
)
class PersonalViewModel(private val store: PersonalStore, private val gateway: TempoGateway = TempoGateway(), private val clock: Clock = Clock.systemUTC()) : ViewModel() {
    private val mutable = MutableStateFlow(PersonalState())
    val state = mutable.asStateFlow()
    private var lastAttempt: Instant? = null
    init {
        viewModelScope.launch {
            var error: String? = null
            val settings = try { store.loadSettings() } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Paramètres locaux illisibles. Vérifiez et enregistrez à nouveau votre profil."; PersonalSettings()
            }
            val tempo = try { store.loadTempo() } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "Cache Tempo illisible. Actualisez les données."; TempoCache()
            }
            mutable.value = PersonalState(ready = true, settings = settings, tempo = tempo, error = error, now = clock.instant())
            onResume()
        }
    }
    fun onResume() {
        val now = clock.instant()
        mutable.value = mutable.value.copy(now = now)
        if (!mutable.value.ready) return
        val today = now.atZone(PARIS).toLocalDate()
        val checked = mutable.value.tempo.color(today)?.checkedAt?.let(Instant::parse)
        if (checked == null || now >= checked.plusSeconds(1800) || now < checked) refresh()
    }
    fun refresh() {
        val now = clock.instant()
        if (!mutable.value.ready || mutable.value.syncing) return
        if (lastAttempt?.let { now >= it && now < it.plusSeconds(120) } == true) {
            mutable.value = mutable.value.copy(now = now, syncMessage = "Attendez deux minutes entre deux actualisations Tempo.")
            return
        }
        lastAttempt = now
        mutable.value = mutable.value.copy(syncing = true, now = now, syncMessage = null)
        viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { gateway.fetch(now) }
                val merged = mutable.value.tempo.merge(update, now)
                store.saveTempo(merged)
                mutable.value = mutable.value.copy(tempo = merged, syncing = false, now = clock.instant(),
                    syncMessage = if (update.errors.isEmpty()) "Données Tempo actualisées." else update.errors.joinToString("\n"))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(syncing = false, syncMessage = "Actualisation impossible. Cache local conservé.")
            }
        }
    }
    fun save(value: PersonalSettings, onSuccess: () -> Unit) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val validated = value.copy(confirmed = true).validate()
                store.saveSettings(validated)
                mutable.value = mutable.value.copy(settings = validated, saving = false, now = clock.instant())
                onSuccess()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(saving = false, error = "Enregistrement impossible. Vérifiez les paramètres et le stockage local.")
            }
        }
    }
}
