package com.iamxpp.isaver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.RootFileSystem
import kotlinx.coroutines.CoroutineDispatcher
import com.iamxpp.isaver.ui.RootGateScreen
import com.iamxpp.isaver.ui.RootGateViewModel
import com.iamxpp.isaver.ui.RootGateUiState
import com.iamxpp.isaver.ui.BrowserViewModel
import com.iamxpp.isaver.ui.HomeBackResult
import com.iamxpp.isaver.ui.HomeDestination
import com.iamxpp.isaver.ui.ISaverHomeScreen
import com.iamxpp.isaver.ui.ISaverHomeViewModel
import com.iamxpp.isaver.ui.LocationHomeAppResolver
import com.iamxpp.isaver.ui.LocationHomeCustomStore
import com.iamxpp.isaver.ui.LocationHomeViewModel
import com.iamxpp.isaver.ui.theme.ISaverTheme
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val homeViewModel by viewModels<ISaverHomeViewModel>()
    private val locationHomeViewModel by viewModels<LocationHomeViewModel> {
        val app = application as ISaverApplication
        LocationHomeViewModelFactory(
            resolver = app.locationHomeAppResolver,
            store = app.locationHomeCustomStore,
            fileSystem = app.rootFileSystem,
        )
    }
    private val browserViewModel by viewModels<BrowserViewModel> {
        val app = application as ISaverApplication
        BrowserViewModelFactory(app.rootFileSystem, app.browserPreferencesStore)
    }

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
                    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
                    val locationState by locationHomeViewModel.state.collectAsStateWithLifecycle()
                    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
                    val destination = homeState.destination

                    LaunchedEffect(destination) {
                        if (destination is HomeDestination.Browser) {
                            browserViewModel.openRoot(destination.path, destination.title)
                        }
                    }

                    fun handleBrowserBack() {
                        if (homeViewModel.onBrowserBack(browserViewModel.back()) == HomeBackResult.EXIT_APP) finish()
                    }

                    BackHandler(enabled = destination is HomeDestination.Browser) { handleBrowserBack() }
                    ISaverHomeScreen(
                        homeState = homeState,
                        locationState = locationState,
                        browserState = browserState,
                        displayMode = browserState.displayMode,
                        onSelectTab = homeViewModel::selectTab,
                        onOpenLocation = homeViewModel::openLocation,
                        onAddCustomLocation = locationHomeViewModel::addCustomLocation,
                        onEditCustomLocation = locationHomeViewModel::editCustomLocation,
                        onRemoveCustomLocation = locationHomeViewModel::removeCustomLocation,
                        onRetryLocations = locationHomeViewModel::refresh,
                        onClearLocationError = locationHomeViewModel::clearAddError,
                        onEnterDirectory = { browserViewModel.enterDirectory(it) },
                        onBrowserBack = ::handleBrowserBack,
                        onRetryBrowser = browserViewModel::retry,
                        onLoadMore = browserViewModel::loadMore,
                        onSearchQueryChange = browserViewModel::setSearchQuery,
                        onDisplayModeChange = browserViewModel::setDisplayMode,
                        onSortChange = browserViewModel::setSort,
                        onCreateDirectory = browserViewModel::createDirectory,
                    )
                } else RootGateScreen(uiState, rootGateViewModel::retry, ::finish)
            }
        }
    }
}

internal class BrowserViewModelFactory(
    private val fileSystem: RootFileSystem,
    private val preferencesStore: BrowserPreferencesStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BrowserViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return BrowserViewModel(fileSystem, ioDispatcher, preferencesStore) as T
    }
}

internal class LocationHomeViewModelFactory(
    private val resolver: LocationHomeAppResolver,
    private val store: LocationHomeCustomStore,
    private val fileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LocationHomeViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return LocationHomeViewModel(resolver, store, fileSystem, ioDispatcher) as T
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
