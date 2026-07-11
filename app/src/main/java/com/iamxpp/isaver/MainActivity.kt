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
import com.iamxpp.isaver.ui.RootGateUiState
import com.iamxpp.isaver.ui.BrowserScreen
import com.iamxpp.isaver.ui.BrowserViewModel
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
                if (uiState == RootGateUiState.Granted) {
                    val browserViewModel = androidx.compose.runtime.remember {
                        ViewModelProvider(this, BrowserViewModelFactory((application as ISaverApplication).rootFileSystem))[BrowserViewModel::class.java]
                    }
                    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
                    BrowserScreen(browserState, { browserViewModel.enterDirectory(it) }, { browserViewModel.back() }, browserViewModel::retry, browserViewModel::loadMore)
                } else RootGateScreen(uiState, rootGateViewModel::retry, ::finish)
            }
        }
    }
}

private class BrowserViewModelFactory(private val fileSystem: com.iamxpp.isaver.data.root.RootFileSystem) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BrowserViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return BrowserViewModel(fileSystem, Dispatchers.IO) as T
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
