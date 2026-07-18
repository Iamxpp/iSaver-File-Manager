package com.iamxpp.isaver

import android.content.Intent
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
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.theme.ISaverTheme
import com.iamxpp.isaver.ui.archive.ArchiveBackResult
import com.iamxpp.isaver.ui.archive.ArchiveViewModel
import com.iamxpp.isaver.ui.recent.RecentOpenTarget
import com.iamxpp.isaver.ui.recent.RecentViewModel
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.transfer.TransferViewModel
import com.iamxpp.isaver.remote.RemoteConnectionViewModel
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var finishAfterTransferCancel = false
    private val transferViewModel by viewModels<TransferViewModel>()
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
        BrowserViewModelFactory(
            app.rootFileSystem,
            app.browserPreferencesStore,
            app.archiveRepository,
            app.recentRepository,
        )
    }
    private val recentViewModel by viewModels<RecentViewModel> {
        val app = application as ISaverApplication
        RecentViewModelFactory(app.recentRepository, app.rootFileSystem)
    }
    private val archiveViewModel by viewModels<ArchiveViewModel> {
        val app = application as ISaverApplication
        ArchiveViewModelFactory(app.archiveRepository, app.recentRepository)
    }
    private val remoteConnectionViewModel by viewModels<RemoteConnectionViewModel> {
        val app = application as ISaverApplication
        RemoteConnectionViewModelFactory(app.remoteCredentialStore, app.remoteFileSystemFactory)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            ShareIntentDispatchPolicy.shouldHandleInitial(
                hasSavedState = savedInstanceState != null,
                flags = intent.flags,
            )
        ) {
            transferViewModel.handleIntent(intent)
        }
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
                    val transferState by transferViewModel.state.collectAsStateWithLifecycle()
                    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
                    val locationState by locationHomeViewModel.state.collectAsStateWithLifecycle()
                    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
                    val recentState by recentViewModel.state.collectAsStateWithLifecycle()
                    val archiveState by archiveViewModel.state.collectAsStateWithLifecycle()
                    val remoteConnectionState by remoteConnectionViewModel.state.collectAsStateWithLifecycle()
                    val destination = homeState.destination
                    val pickerActive = transferState != TransferUiState.Idle

                    LaunchedEffect(pickerActive) {
                        if (pickerActive) homeViewModel.selectTab(HomeTab.VIEWS)
                    }

                    LaunchedEffect(destination) {
                        when (destination) {
                            is HomeDestination.Browser -> browserViewModel.openRoot(
                                destination.path,
                                destination.title,
                                destination.recordAccess,
                            )
                            is HomeDestination.Archive -> if (
                                archiveState.source != destination.source || archiveState.listing == null
                            ) {
                                archiveViewModel.open(
                                    destination.source,
                                    destination.sourceName,
                                    destination.sourceTab,
                                )
                            }
                            is HomeDestination.ExtractionTarget -> {
                                if (archiveState.source != destination.source || archiveState.listing == null) {
                                    archiveViewModel.open(
                                        destination.source,
                                        destination.sourceName,
                                        destination.sourceTab,
                                    )
                                }
                                destination.targetBrowser?.let { browser ->
                                    browserViewModel.openRoot(browser.path, browser.title, browser.recordAccess)
                                }
                            }
                            is HomeDestination.Tab -> Unit
                        }
                    }

                    LaunchedEffect(browserState.archiveToOpen) {
                        val archive = browserState.archiveToOpen ?: return@LaunchedEffect
                        val sourceTab = (destination as? HomeDestination.Browser)?.source
                            ?: homeState.selectedTab
                        homeViewModel.openArchive(archive.path, archive.name, sourceTab)
                        browserViewModel.consumeArchiveOpen()
                    }

                    LaunchedEffect(archiveState.operation) {
                        val success = archiveState.operation as? com.iamxpp.isaver.archive.ArchiveState.Success
                            ?: return@LaunchedEffect
                        archiveViewModel.dismissOperation()
                        homeViewModel.openLocation(
                            success.output.path,
                            success.output.name,
                            archiveState.sourceTab,
                            recordAccess = false,
                        )
                    }

                    LaunchedEffect(pickerActive, destination, browserState.currentPath) {
                        if (pickerActive) {
                            when (destination) {
                                is HomeDestination.Browser -> transferViewModel.selectTarget(browserState.currentPath)
                                is HomeDestination.Tab,
                                is HomeDestination.Archive,
                                is HomeDestination.ExtractionTarget -> transferViewModel.clearTarget()
                            }
                        }
                    }

                    LaunchedEffect(transferState) {
                        if (transferState is TransferUiState.Success ||
                            (finishAfterTransferCancel && transferState == TransferUiState.Idle)
                        ) {
                            finish()
                        }
                    }

                    fun cancelPicker() {
                        finishAfterTransferCancel = true
                        transferViewModel.exitRootGate()
                    }

                    fun handleBrowserBack() {
                        if (homeViewModel.onBrowserBack(browserViewModel.back()) == HomeBackResult.EXIT_APP) {
                            if (pickerActive) cancelPicker() else finish()
                        }
                    }

                    fun handleArchiveBack() {
                        if (archiveViewModel.back() == ArchiveBackResult.CLOSE_ARCHIVE) {
                            homeViewModel.closeArchive()
                        }
                    }

                    fun handleRecentOpen(item: com.iamxpp.isaver.ui.recent.RecentUiItem) {
                        when (val target = recentViewModel.open(item)) {
                            is RecentOpenTarget.Directory -> homeViewModel.openLocation(
                                target.path,
                                target.title,
                                HomeTab.RECENT,
                            )
                            is RecentOpenTarget.Archive -> homeViewModel.openArchive(
                                target.path,
                                target.title,
                                HomeTab.RECENT,
                            )
                            is RecentOpenTarget.File, null -> Unit
                        }
                    }

                    BackHandler(
                        enabled = pickerActive || destination !is HomeDestination.Tab,
                    ) {
                        when {
                            pickerActive && destination !is HomeDestination.Browser -> cancelPicker()
                            destination is HomeDestination.Browser -> handleBrowserBack()
                            destination is HomeDestination.Archive -> handleArchiveBack()
                            destination is HomeDestination.ExtractionTarget && destination.targetBrowser != null ->
                                handleBrowserBack()
                            destination is HomeDestination.ExtractionTarget -> homeViewModel.returnToArchive()
                        }
                    }
                    ISaverHomeScreen(
                        homeState = homeState,
                        locationState = locationState,
                        browserState = browserState,
                        displayMode = browserState.displayMode,
                        sortSpec = browserState.sortSpec,
                        onSelectTab = homeViewModel::selectTab,
                        onOpenLocation = homeViewModel::openLocation,
                        onAddCustomLocation = locationHomeViewModel::addCustomLocation,
                        onEditCustomLocation = locationHomeViewModel::editCustomLocation,
                        onRemoveCustomLocation = locationHomeViewModel::removeCustomLocation,
                        onRetryLocations = locationHomeViewModel::refresh,
                        onClearLocationError = locationHomeViewModel::clearAddError,
                        onRevalidateCustomLocation = locationHomeViewModel::revalidateCustomLocation,
                        onEnterDirectory = { browserViewModel.enterDirectory(it) },
                        onBrowserBack = ::handleBrowserBack,
                        onRetryBrowser = browserViewModel::retry,
                        onLoadMore = browserViewModel::loadMore,
                        onSearchQueryChange = browserViewModel::setSearchQuery,
                        onDisplayModeChange = browserViewModel::setDisplayMode,
                        onSortChange = browserViewModel::setSort,
                        onCreateDirectory = browserViewModel::createDirectory,
                        onToggleSelection = browserViewModel::toggleSelection,
                        onOpenBrowserEntry = browserViewModel::openEntry,
                        onClearBrowserSelection = browserViewModel::clearSelection,
                        onDismissFileInfo = browserViewModel::dismissFileInfo,
                        onCompress = browserViewModel::compress,
                        onDismissCompressionMessage = browserViewModel::clearCompressionMessage,
                        onConnectServer = remoteConnectionViewModel::connect,
                        remoteConnectionState = remoteConnectionState,
                        onDismissRemoteMessage = remoteConnectionViewModel::clearMessage,
                        onRefreshRemote = remoteConnectionViewModel::refreshRemote,
                        onCreateRemoteDirectory = remoteConnectionViewModel::createRemoteDirectory,
                        transferState = transferState,
                        onSave = transferViewModel::save,
                        onStemChange = transferViewModel::setStem,
                        onExtensionChange = transferViewModel::setExtension,
                        onRetryTransfer = transferViewModel::retry,
                        onAcknowledgeUncertain = transferViewModel::acknowledgeUncertain,
                        onContinueQueued = transferViewModel::continueWithQueued,
                        recentState = recentState,
                        onOpenRecent = ::handleRecentOpen,
                        onRefreshRecent = recentViewModel::refresh,
                        onDismissRecentFileInfo = recentViewModel::dismissFileInfo,
                        archiveState = archiveState,
                        onArchiveBack = ::handleArchiveBack,
                        onEnterArchiveDirectory = archiveViewModel::enter,
                        onArchiveQueryChange = archiveViewModel::setSearchQuery,
                        onArchiveDisplayModeChange = archiveViewModel::setDisplayMode,
                        onChooseExtractionTarget = homeViewModel::chooseExtractionTarget,
                        onRetryArchive = archiveViewModel::retry,
                        onCancelExtraction = archiveViewModel::cancelExtraction,
                        onDismissArchiveOperation = archiveViewModel::dismissOperation,
                        onExtractHere = {
                            val extraction = homeState.destination as? HomeDestination.ExtractionTarget
                            if (extraction?.targetBrowser != null && browserState.canCreateDirectory) {
                                val target = browserState.currentPath
                                homeViewModel.returnToArchive()
                                archiveViewModel.extractTo(target)
                            }
                        },
                    )
                } else {
                    RootGateScreen(
                        uiState = uiState,
                        onRetry = rootGateViewModel::retry,
                        onExit = {
                            transferViewModel.exitRootGate()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ShareIntentDispatchPolicy.shouldHandleNewIntent(intent.flags)) {
            transferViewModel.handleIntent(intent)
        }
    }
}

internal class BrowserViewModelFactory(
    private val fileSystem: RootFileSystem,
    private val preferencesStore: BrowserPreferencesStore,
    private val archiveRepository: com.iamxpp.isaver.archive.ArchiveRepository? = null,
    private val recentRepository: com.iamxpp.isaver.recent.RecentRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BrowserViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return BrowserViewModel(
            fileSystem,
            ioDispatcher,
            preferencesStore,
            archiveRepository,
            recordDirectoryAccess = { path, title ->
                recentRepository?.recordAccess(
                    path,
                    title,
                    null,
                    com.iamxpp.isaver.recent.RecentItemType.DIRECTORY,
                )
            },
            recordFileAccess = { path, title ->
                recentRepository?.recordAccess(
                    path,
                    title,
                    null,
                    com.iamxpp.isaver.recent.RecentItemType.FILE,
                )
            },
        ) as T
    }
}

internal class RecentViewModelFactory(
    private val repository: com.iamxpp.isaver.recent.RecentRepository,
    private val fileSystem: RootFileSystem,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RecentViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return RecentViewModel(repository, fileSystem, ioDispatcher) as T
    }
}

internal class ArchiveViewModelFactory(
    private val repository: com.iamxpp.isaver.archive.ArchiveRepository,
    private val recentRepository: com.iamxpp.isaver.recent.RecentRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ArchiveViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return ArchiveViewModel(
            inspectArchive = repository::inspect,
            extractArchive = repository::extract,
            recordAccess = { path, title ->
                recentRepository.recordAccess(
                    path,
                    title,
                    null,
                    com.iamxpp.isaver.recent.RecentItemType.ARCHIVE,
                )
            },
            ioDispatcher = ioDispatcher,
        ) as T
    }
}

internal class RemoteConnectionViewModelFactory(
    private val credentialStore: com.iamxpp.isaver.remote.CredentialStore,
    private val connector: com.iamxpp.isaver.remote.RemoteConnector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RemoteConnectionViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return RemoteConnectionViewModel(credentialStore, connector, ioDispatcher) as T
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
