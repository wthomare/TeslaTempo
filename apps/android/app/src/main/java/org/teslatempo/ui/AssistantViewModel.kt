package org.teslatempo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslatempo.BuildConfig
import org.teslatempo.history.SavedPlan
import org.teslatempo.history.assistantSummary
import org.teslatempo.network.*
import org.teslatempo.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

data class AssistantState(val busy: Boolean = false, val connection: AssistantConnection? = null,
    val models: List<AssistantModel> = emptyList(), val selected: String? = null,
    val planId: String? = null, val answer: String? = null, val error: String? = null)

class AssistantViewModel(private val credentials: CredentialStore) : ViewModel() {
    private val mutable = MutableStateFlow(AssistantState())
    val state = mutable.asStateFlow()
    private var reviewedCredentials: RelayCredentials? = null
    private fun gateway() = AssistantGateway(credentials.load()
        ?: throw RelayException("Configurez votre relais dans les réglages avant d’utiliser l’assistant."), BuildConfig.DEBUG)
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = if (e is RelayException) e.message else "Assistant indisponible. Vérifiez la configuration du relais.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun refresh() = action {
        mutable.value = AssistantState(busy = true)
        val result = withContext(Dispatchers.IO) {
            reviewedCredentials = credentials.load()
            val api = gateway(); val connection = api.status()
            connection to if (connection.enabled) api.models() else emptyList()
        }
        mutable.value = AssistantState(busy = true, connection = result.first, models = result.second)
    }
    fun select(id: String) {
        if (!mutable.value.busy && mutable.value.models.any { it.id == id })
            mutable.value = mutable.value.copy(selected = id, answer = null, planId = null)
    }
    fun explain(plan: SavedPlan) {
        val selected = mutable.value.selected ?: return
        val account = mutable.value.connection?.account ?: return
        action {
            mutable.value = mutable.value.copy(answer = null, planId = plan.id)
            val answer = withContext(Dispatchers.IO) {
                val current = credentials.load()
                if (current == null || current.url != reviewedCredentials?.url || current.token != reviewedCredentials?.token)
                    throw RelayException("Le relais a changé. Vérifiez à nouveau la connexion ChatGPT.")
                gateway().explain(selected, assistantSummary(plan), account)
            }
            mutable.value = mutable.value.copy(answer = answer)
        }
    }
    fun disconnect() = action {
        val confirmed = withContext(Dispatchers.IO) { gateway().disconnect() }
        mutable.value = AssistantState(busy = true, error = if (confirmed) "Déconnexion effectuée."
            else "Déconnexion locale effectuée. Révocation distante non confirmée : vérifiez les accès dans les réglages ChatGPT.")
    }
}
