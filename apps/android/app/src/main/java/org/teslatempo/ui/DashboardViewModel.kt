package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

enum class RelayLink { NOT_CONFIGURED, UNKNOWN, CONNECTED, UNAVAILABLE, REJECTED }
data class RelayHealth(
    val link: RelayLink = RelayLink.NOT_CONFIGURED,
    val checking: Boolean = false,
    val checkedAt: java.time.Instant? = null,
    val teslaConnected: Boolean? = null,
    val simulated: Boolean = false,
    val detail: String? = null,
)

data class ScreenState(
    val loading: Boolean = true,
    val dashboard: Dashboard? = null,
    val relayUrl: String? = null,
    val error: String? = null,
    val diagnostic: String? = null,
)

class DashboardViewModel(private val store: CredentialStore) : ViewModel() {
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var credentials: RelayCredentials? = null
    private val healthMutable = MutableStateFlow(RelayHealth())
    val health = healthMutable.asStateFlow()
    private val healthMutex = kotlinx.coroutines.sync.Mutex()

    // Only /v1/status: validates the saved token without calling Tesla.
    suspend fun checkRelay() {
        if (!healthMutex.tryLock()) return
        val candidate = credentials
        try {
            if (candidate == null) return
            healthMutable.value = healthMutable.value.copy(checking = true)
            try {
                val result = withContext(Dispatchers.IO) { RelayGateway(candidate, BuildConfig.DEBUG).diagnose() }
                if (credentials === candidate) healthMutable.value = RelayHealth(
                    RelayLink.CONNECTED, checkedAt = java.time.Instant.now(),
                    teslaConnected = result.teslaConnected, simulated = result.simulated)
            } catch (e: RelayException) {
                if (credentials === candidate) healthMutable.value = RelayHealth(
                    if (e.authenticationRejected) RelayLink.REJECTED else RelayLink.UNAVAILABLE,
                    checkedAt = java.time.Instant.now(), detail = e.message)
            }
        } finally {
            healthMutable.value = healthMutable.value.copy(checking = false)
            healthMutex.unlock()
        }
    }

    fun pauseHealth() {
        if (credentials != null) healthMutable.value = healthMutable.value.copy(link = RelayLink.UNKNOWN, checking = false)
    }

    init {
        viewModelScope.launch {
            try {
                credentials = withContext(Dispatchers.IO) { store.load() }
                healthMutable.value = RelayHealth(if (credentials == null) RelayLink.NOT_CONFIGURED else RelayLink.UNKNOWN)
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

    fun diagnose(url: String, token: String) {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true, error = null, diagnostic = null)
        viewModelScope.launch {
            try {
                val candidate = if (token.isBlank()) {
                    credentials?.takeIf { it.url == url.trim() }
                        ?: throw RelayException("Saisissez le jeton pour vérifier cette adresse.")
                } else RelayCredentials(url.trim(), token.trim())
                val d = withContext(Dispatchers.IO) { RelayGateway(candidate, BuildConfig.DEBUG).diagnose() }
                mutable.value = mutable.value.copy(diagnostic = "Relais ${d.version} accessible · jeton accepté.\n" +
                    (if (d.simulated) "Démonstration : aucune donnée Tesla réelle." else if (d.teslaConnected) "Session Tesla enregistrée. Les droits et le véhicule seront vérifiés à l’actualisation." else "Connexion Tesla à terminer sur le relais.") +
                    "\nCommandes réelles : " + (if (d.commandsEnabled) "annoncées actives par le relais." else "désactivées."))
            } catch (e: RelayException) { mutable.value = mutable.value.copy(error = e.message) }
            finally { mutable.value = mutable.value.copy(loading = false) }
        }
    }

    fun connect(url: String, token: String, onSuccess: () -> Unit) {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(loading = true, error = null, dashboard = null)
        viewModelScope.launch {
            try {
                // Always require a token for a new address; never reuse one across hosts.
                val candidate = RelayCredentials(url.trim(), token.trim())
                val result = withContext(Dispatchers.IO) {
                    // Save a valid relay even before delivery, or while the car is asleep.
                    val result = RelayGateway(candidate, BuildConfig.DEBUG).diagnose()
                    store.save(candidate)
                    result
                }
                credentials = candidate
                healthMutable.value = RelayHealth(RelayLink.CONNECTED, checkedAt = java.time.Instant.now(),
                    teslaConnected = result.teslaConnected, simulated = result.simulated)
                mutable.value = ScreenState(false, relayUrl = candidate.url)
                onSuccess()
                refresh()
            } catch (e: RelayException) {
                mutable.value = ScreenState(false, relayUrl = credentials?.url, error = e.message)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                healthMutable.value = RelayHealth()
                mutable.value = ScreenState(false, DemoGateway().load())
                onSuccess()
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = "Impossible d’effacer la configuration locale.")
            }
        }
    }
}
