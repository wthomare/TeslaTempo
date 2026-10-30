package org.teslatempo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.teslatempo.security.CredentialStore
import org.teslatempo.ui.DashboardViewModel
import org.teslatempo.ui.PersonalViewModel
import org.teslatempo.personal.PersonalStore
import org.teslatempo.history.HistoryStore
import org.teslatempo.ui.ObservationsViewModel
import org.teslatempo.ui.AssistantViewModel
import org.teslatempo.ui.HistoryViewModel
import org.teslatempo.ui.TeslaTempoApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                when (modelClass) {
                    ObservationsViewModel::class.java -> ObservationsViewModel(CredentialStore(applicationContext))
                    AssistantViewModel::class.java -> AssistantViewModel(CredentialStore(applicationContext))
                    HistoryViewModel::class.java -> HistoryViewModel(HistoryStore(applicationContext))
                    PersonalViewModel::class.java -> PersonalViewModel(PersonalStore(applicationContext))
                    else -> DashboardViewModel(CredentialStore(applicationContext))
                } as T
        }
        setContent { TeslaTempoApp(viewModel(factory = factory), viewModel(factory = factory), viewModel(factory = factory), viewModel(factory = factory), viewModel(factory = factory)) }
    }
}
