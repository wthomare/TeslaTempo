package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.teslatempo.BuildConfig
import org.teslatempo.network.*
import org.teslatempo.security.CredentialStore

data class ObservationsState(val vehicleId: String? = null, val busy: Boolean = false,
    val history: ObservationHistory? = null, val message: String? = null)
class ObservationsViewModel(private val credentials: CredentialStore): ViewModel() {
    private val mutable = MutableStateFlow(ObservationsState())
    val state = mutable.asStateFlow()
    private fun api() = RelayGateway(credentials.load()
        ?: throw RelayException("Configurez votre relais pour consulter ses relevés."), BuildConfig.DEBUG)
    fun load(id: String) = action(id) {
        val history=withContext(Dispatchers.IO) { api().observations(id) }
        mutable.value=ObservationsState(id, history=history)
    }
    fun clear(id: String) = action(id) {
        withContext(Dispatchers.IO) { api().clearObservations(id) }
        mutable.value=ObservationsState(id,history=ObservationHistory(false,emptyList()),message="Relevés effacés du relais. Les prochains relevés pourront être enregistrés lors des actualisations.")
    }
    private fun action(id: String, block: suspend () -> Unit) {
        if(mutable.value.busy)return
        mutable.value=ObservationsState(id,busy=true)
        viewModelScope.launch {
            try { block() }
            catch(e: CancellationException){throw e}
            catch(e: Exception){mutable.value=ObservationsState(id,message=if(e is RelayException)e.message else "Relevés indisponibles.")}
            finally{mutable.value=mutable.value.copy(busy=false)}
        }
    }
}
