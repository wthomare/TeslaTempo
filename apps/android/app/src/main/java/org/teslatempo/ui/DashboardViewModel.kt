package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslatempo.BuildConfig
import org.teslatempo.domain.Dashboard
import org.teslatempo.domain.DemoGateway
import org.teslatempo.network.RelayCredentials
import org.teslatempo.network.RelayException
import org.teslatempo.network.RelayGateway
import org.teslatempo.security.CredentialStore

data class ScreenState(
    val loading: Boolean = true,
    val dashboard: Dashboard? = null,
    val relayUrl: String? = null,
    val error: String? = null,
)

class DashboardViewModel(private val store: CredentialStore) : ViewModel() {
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var credentials: RelayCredentials? = null

    init {
        viewModelScope.launch {
            try {
                credentials = withContext(Dispatchers.IO) { store.load() }
                mutable.value = ScreenState(loading = false, relayUrl = credentials?.url)
                refresh()
            } catch (_: Exception) {
                mutable.value = ScreenState(loading = false, error = "Configuration locale illisible. Effacez-la avec « Démonstration » puis reconnectez le relais.")
            }
        }
    }

    fun refresh(selectedId: String? = mutable.value.dashboard?.selected?.id) {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val dashboard = withContext(Dispatchers.IO) {
                    credentials?.let { RelayGateway(it, BuildConfig.DEBUG).load(selectedId) } ?: DemoGateway().load()
                }
                mutable.value = ScreenState(false, dashboard, credentials?.url)
            } catch (e: RelayException) {
                mutable.value = ScreenState(false, relayUrl = credentials?.url, error = e.message)
            }
        }
    }

    fun connect(url: String, token: String, onSuccess: () -> Unit) {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true, error = null, dashboard = null)
        viewModelScope.launch {
            try {
                // Always require a token for a new address; never reuse one across hosts.
                val candidate = RelayCredentials(url.trim(), token.trim())
                val dashboard = withContext(Dispatchers.IO) {
                    val result = RelayGateway(candidate, BuildConfig.DEBUG).load()
                    store.save(candidate)
                    result
                }
                credentials = candidate
                mutable.value = ScreenState(false, dashboard, candidate.url)
                onSuccess()
            } catch (e: RelayException) {
                mutable.value = ScreenState(false, relayUrl = credentials?.url, error = e.message)
            } catch (_: Exception) {
                mutable.value = ScreenState(false, relayUrl = credentials?.url, error = "Impossible de protéger la configuration sur cet appareil.")
            }
        }
    }

    fun useDemo(onSuccess: () -> Unit) {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                credentials = null
                mutable.value = ScreenState(false, DemoGateway().load())
                onSuccess()
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = "Impossible d’effacer la configuration locale.")
            }
        }
    }
}
