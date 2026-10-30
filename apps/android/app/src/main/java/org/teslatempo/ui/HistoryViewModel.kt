package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.teslatempo.history.*

data class HistoryState(val ready: Boolean = false, val busy: Boolean = false,
    val plans: List<SavedPlan> = emptyList(), val unreadable: Boolean = false, val error: String? = null)

class HistoryViewModel(private val store: HistoryStore) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try { mutable.value = HistoryState(ready = true, plans = store.load()) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = HistoryState(ready = true, unreadable = true,
                    error = "Historique illisible ou version non prise en charge. Les données sont conservées. Vous pouvez les effacer explicitement.")
            }
        }
    }
    private fun update(action: suspend () -> List<SavedPlan>) {
        if (!mutable.value.ready || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { mutable.value = HistoryState(ready = true, plans = action()) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(busy = false, error = "Modification impossible. Les données précédentes sont conservées ; vérifiez le stockage et la capacité de l’historique.")
            }
        }
    }
    fun save(plan: SavedPlan) { if (!mutable.value.unreadable) update { store.save(plan) } }
    fun delete(id: String) { if (!mutable.value.unreadable) update { store.delete(id) } }
    fun clear() = update { store.clear(); emptyList() }
}
