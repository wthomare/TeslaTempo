package org.teslatempo.personal

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.teslatempo.tempo.TempoCache

private val Context.personalData by preferencesDataStore("personal_v1")

class PersonalStore(context: Context) {
    private val store = context.applicationContext.personalData
    private val settingsKey = stringPreferencesKey("settings")
    private val tempoKey = stringPreferencesKey("tempo")
    suspend fun loadSettings(): PersonalSettings = store.data.first()[settingsKey]?.let(PersonalCodec::settings) ?: PersonalSettings()
    suspend fun loadTempo(): TempoCache = store.data.first()[tempoKey]?.let(PersonalCodec::tempo) ?: TempoCache()
    suspend fun saveSettings(value: PersonalSettings) { value.validate(); store.edit { it[settingsKey] = PersonalCodec.encode(value) } }
    suspend fun saveTempo(value: TempoCache) { value.validate(); store.edit { it[tempoKey] = PersonalCodec.encode(value) } }
}
