package com.iamxpp.isaver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.ui.RootGateScreen
import com.iamxpp.isaver.ui.RootGateViewModel
import com.iamxpp.isaver.ui.theme.ISaverTheme
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootGateViewModel = ViewModelProvider(
            this,
            RootGateViewModelFactory(
                rootSession = (application as ISaverApplication).rootSession,
            ),
        )[RootGateViewModel::class.java]

        setContent {
            val uiState by rootGateViewModel.state.collectAsStateWithLifecycle()

            ISaverTheme {
                RootGateScreen(
                    uiState = uiState,
                    onRetry = rootGateViewModel::retry,
                    onExit = ::finish,
                )
            }
        }
    }
}

private class RootGateViewModelFactory(
    private val rootSession: RootSession,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RootGateViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return RootGateViewModel(
            rootSession = rootSession,
            checkDispatcher = Dispatchers.IO,
        ) as T
    }
}
