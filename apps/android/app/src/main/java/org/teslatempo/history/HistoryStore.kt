package org.teslatempo.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.planHistory by preferencesDataStore("plan_history_v1")

class HistoryStore(context: Context) {
    private val store = context.applicationContext.planHistory
    private val key = stringPreferencesKey("snapshots")
    suspend fun load(): List<SavedPlan> = store.data.first()[key]?.let(HistoryCodec::decode) ?: emptyList()
    suspend fun save(plan: SavedPlan): List<SavedPlan> {
        var result = emptyList<SavedPlan>()
        store.edit {
            result = HistoryCodec.append(it[key]?.let(HistoryCodec::decode) ?: emptyList(), plan)
            it[key] = HistoryCodec.encode(result)
        }
        return result
    }
    suspend fun delete(id: String): List<SavedPlan> {
        var result = emptyList<SavedPlan>()
        store.edit {
            result = (it[key]?.let(HistoryCodec::decode) ?: emptyList()).filterNot { p -> p.id == id }
            it[key] = HistoryCodec.encode(result)
        }
        return result
    }
    // Clearing is explicit; do not decode a damaged/unsupported payload before removal.
    suspend fun clear() { store.edit { it.remove(key) } }
}
