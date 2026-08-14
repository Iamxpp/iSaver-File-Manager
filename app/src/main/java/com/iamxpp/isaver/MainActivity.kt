package com.iamxpp.isaver

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.local.BrowserSessionStore
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.export.ExternalOpenIntentFactory
import com.iamxpp.isaver.export.ExternalShareIntentFactory
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
import com.iamxpp.isaver.ui.canUseRealTarget
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.theme.ISaverTheme
import com.iamxpp.isaver.ui.archive.ArchiveBackResult
import com.iamxpp.isaver.ui.archive.ArchiveViewModel
import com.iamxpp.isaver.ui.recent.RecentOpenTarget
import com.iamxpp.isaver.ui.recent.RecentViewModel
import com.iamxpp.isaver.share.ShareSourceLocationResolver
import com.iamxpp.isaver.transfer.TransferUiState
import com.iamxpp.isaver.transfer.TransferViewModel
import com.iamxpp.isaver.preview.RootPreviewRepository
import com.iamxpp.isaver.ui.virtualviews.VirtualViewRepositoryStore
import com.iamxpp.isaver.ui.virtualviews.VirtualViewViewModel
import com.iamxpp.isaver.ui.dualpane.DualPaneBrowserCallbacks
import com.iamxpp.isaver.ui.dualpane.DualPaneViewModel
import com.iamxpp.isaver.ui.dualpane.PaneId
import com.iamxpp.isaver.ui.dualpane.other
import com.iamxpp.isaver.ui.device.DeviceOverviewRepository
import com.iamxpp.isaver.ui.device.DeviceSettingsScreen
import com.iamxpp.isaver.ui.device.DeviceSettingsViewModel
import com.iamxpp.isaver.data.access.FileAccessMode
import com.iamxpp.isaver.texteditor.TextEditorScreen
import com.iamxpp.isaver.texteditor.TextEditorViewModel
import com.iamxpp.isaver.filetools.FileToolsScreen
import com.iamxpp.isaver.filetools.FileToolsViewModel
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
    private val virtualViewViewModel by viewModels<VirtualViewViewModel> {
        val app = application as ISaverApplication
        VirtualViewViewModelFactory(VirtualViewRepositoryStore(app.virtualViewRepository), app.rootFileSystem)
    }
    private val browserViewModel by viewModels<BrowserViewModel> {
        val app = application as ISaverApplication
        BrowserViewModelFactory(
            app.rootFileSystem,
            app.browserPreferencesStore,
            app.archiveRepository,
            app.recentRepository,
            rootExportRepository = app.rootExportRepository,
            directoryShareRepository = app.directoryShareRepository,
            fileMoveRepository = app.fileMoveRepository,
            fileCopyRepository = app.fileCopyRepository,
            fileRenameRepository = app.fileRenameRepository,
            operationTaskStore = app.operationTaskRepository,
            trashRepository = app.trashRepository,
            checksumFile = app.fileChecksumRepository::sha256,
            checksumFileByAlgorithm = { entry, algorithm -> app.fileChecksumRepository.checksum(entry, algorithm) },
            virtualViewRepository = app.virtualViewRepository,
            browserSessionStore = app.browserSessionStore,
            previewRepository = RootPreviewRepository(app.rootFileSystem),
        )
    }
    private val secondaryBrowserViewModel by lazy {
        val app = application as ISaverApplication
        val factory = BrowserViewModelFactory(
            app.rootFileSystem,
            app.secondaryBrowserPreferencesStore,
            app.archiveRepository,
            app.recentRepository,
            rootExportRepository = app.rootExportRepository,
            directoryShareRepository = app.directoryShareRepository,
            fileMoveRepository = app.fileMoveRepository,
            fileCopyRepository = app.fileCopyRepository,
            fileRenameRepository = app.fileRenameRepository,
            operationTaskStore = app.operationTaskRepository,
            trashRepository = app.trashRepository,
            checksumFile = app.fileChecksumRepository::sha256,
            checksumFileByAlgorithm = { entry, algorithm -> app.fileChecksumRepository.checksum(entry, algorithm) },
            virtualViewRepository = app.virtualViewRepository,
            browserSessionStore = app.secondaryBrowserSessionStore,
            previewRepository = RootPreviewRepository(app.rootFileSystem),
        )
        ViewModelProvider(this, factory).get("secondary-browser", BrowserViewModel::class.java)
    }
    private val dualPaneViewModel by viewModels<DualPaneViewModel>()
    private val deviceSettingsViewModel by viewModels<DeviceSettingsViewModel> {
        DeviceSettingsViewModelFactory()
    }
    private val textEditorViewModel by viewModels<TextEditorViewModel> {
        val app = application as ISaverApplication
        TextEditorViewModelFactory(app.textEditorRepository, app.textDraftStore)
    }
    private val fileToolsViewModel by viewModels<FileToolsViewModel> {
        val app = application as ISaverApplication
        FileToolsViewModelFactory(app.hexViewerRepository, app.fileComparisonRepository)
    }
    private val recentViewModel by viewModels<RecentViewModel> {
        val app = application as ISaverApplication
        RecentViewModelFactory(app.recentRepository, app.rootFileSystem)
    }
    private val archiveViewModel by viewModels<ArchiveViewModel> {
        val app = application as ISaverApplication
        ArchiveViewModelFactory(app.archiveRepository, app.recentRepository, app.operationTaskRepository)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            ShareIntentDispatchPolicy.shouldHandleInitial(
                hasSavedState = savedInstanceState != null,
                flags = intent.flags,
            )
        ) {
            handleIncomingShareIntent(intent)
        }
        val rootGateViewModel = ViewModelProvider(
            this,
            RootGateViewModelFactory(
                rootSession = (application as ISaverApplication).rootSession,
                modeStore = (application as ISaverApplication).fileAccessModeStore,
                accessController = (application as ISaverApplication).fileAccessController,
            ),
        )[RootGateViewModel::class.java]

        setContent {
            val uiState by rootGateViewModel.state.collectAsStateWithLifecycle()

            ISaverTheme {
                if (
                    uiState == RootGateUiState.Granted ||
                    uiState == RootGateUiState.EnablingRoot ||
                    uiState is RootGateUiState.ReadOnly
                ) {
                    val transferState by transferViewModel.state.collectAsStateWithLifecycle()
                    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
                    val locationState by locationHomeViewModel.state.collectAsStateWithLifecycle()
                    val virtualViewState by virtualViewViewModel.state.collectAsStateWithLifecycle()
                    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
                    val secondaryBrowserState by secondaryBrowserViewModel.state.collectAsStateWithLifecycle()
                    val dualPaneState by dualPaneViewModel.state.collectAsStateWithLifecycle()
                    val textEditorState by textEditorViewModel.state.collectAsStateWithLifecycle()
                    val fileToolsState by fileToolsViewModel.state.collectAsStateWithLifecycle()
                    val recentState by recentViewModel.state.collectAsStateWithLifecycle()
                    val archiveState by archiveViewModel.state.collectAsStateWithLifecycle()
                    val deviceSettingsState by deviceSettingsViewModel.state.collectAsStateWithLifecycle()
                    val accessMode by (application as ISaverApplication)
                        .fileAccessController.mode.collectAsStateWithLifecycle()
                    val rootMode = accessMode == FileAccessMode.ROOT
                    val destination = homeState.destination
                    val pickerActive = transferState != TransferUiState.Idle
                    val movePickerActive = browserState.moveSelection != null
                    val copyPickerActive = browserState.copySelection != null
                    val fileOperationInFlight = browserState.movingFile || browserState.copyingFile || browserState.renamingFile ||
                        secondaryBrowserState.movingFile || secondaryBrowserState.copyingFile || secondaryBrowserState.renamingFile
                    var editorWasVisible by remember { mutableStateOf(false) }

                    LaunchedEffect(textEditorState.visible) {
                        if (textEditorState.visible) {
                            editorWasVisible = true
                        } else if (editorWasVisible) {
                            editorWasVisible = false
                            browserViewModel.retry()
                            if (dualPaneState.enabled) secondaryBrowserViewModel.retry()
                        }
                    }

                    LaunchedEffect(dualPaneState.enabled) {
                        if (dualPaneState.enabled) {
                            secondaryBrowserViewModel.restoreSessionOrOpenRoot(
                                browserState.currentPath,
                                browserState.title,
                                recordAccess = false,
                            )
                        }
                    }
                    LaunchedEffect(browserState.currentPath, browserState.title) {
                        dualPaneViewModel.update(PaneId.PRIMARY, browserState.currentPath, browserState.title)
                    }
                    LaunchedEffect(secondaryBrowserState.currentPath, secondaryBrowserState.title) {
                        dualPaneViewModel.update(PaneId.SECONDARY, secondaryBrowserState.currentPath, secondaryBrowserState.title)
                    }

                    LaunchedEffect(pickerActive) {
                        if (pickerActive) homeViewModel.selectTab(HomeTab.VIEWS)
                    }

                    LaunchedEffect(accessMode) {
                        locationHomeViewModel.refresh()
                        virtualViewViewModel.navigateTo(virtualViewState.currentFolderId)
                        recentViewModel.refresh()
                        if (destination is HomeDestination.Browser) browserViewModel.retry()
                        if (dualPaneState.enabled) secondaryBrowserViewModel.retry()
                    }

                    LaunchedEffect(destination) {
                        when (destination) {
                            HomeDestination.Device -> Unit
                            is HomeDestination.Browser -> if (
                                destination.source == HomeTab.BROWSE && destination.path.value == "/"
                            ) {
                                browserViewModel.restoreSessionOrOpenRoot(
                                    destination.path,
                                    destination.title,
                                    destination.recordAccess,
                                )
                            } else {
                                browserViewModel.openRoot(
                                    destination.path,
                                    destination.title,
                                    destination.recordAccess,
                                )
                            }
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
                            is HomeDestination.MoveTarget -> destination.targetBrowser?.let { browser ->
                                browserViewModel.openRoot(browser.path, browser.title, browser.recordAccess)
                            }
                            is HomeDestination.CopyTarget -> destination.targetBrowser?.let { browser ->
                                browserViewModel.openRoot(browser.path, browser.title, browser.recordAccess)
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

                    LaunchedEffect(secondaryBrowserState.archiveToOpen) {
                        val archive = secondaryBrowserState.archiveToOpen ?: return@LaunchedEffect
                        val sourceTab = (destination as? HomeDestination.Browser)?.source
                            ?: homeState.selectedTab
                        homeViewModel.openArchive(archive.path, archive.name, sourceTab)
                        secondaryBrowserViewModel.consumeArchiveOpen()
                    }

                    LaunchedEffect(virtualViewState.verifiedReference) {
                        val verified = virtualViewState.verifiedReference ?: return@LaunchedEffect
                        when (verified.entry.type) {
                            EntryType.DIRECTORY -> homeViewModel.openLocation(
                                verified.entry.path,
                                verified.displayName,
                                HomeTab.VIEWS,
                                recordAccess = false,
                            )
                            EntryType.FILE, EntryType.OTHER -> browserViewModel.openEntry(verified.entry)
                        }
                        virtualViewViewModel.consumeVerifiedReference()
                    }

                    LaunchedEffect(browserState.externalFileToOpen) {
                        val grant = browserState.externalFileToOpen ?: return@LaunchedEffect
                        val launched = try {
                            startActivity(
                                if (browserState.externalOpenChooser) {
                                    ExternalOpenIntentFactory.createChooser(grant)
                                } else {
                                    ExternalOpenIntentFactory.create(grant)
                                },
                            )
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                        browserViewModel.completeExternalOpen(grant, launched)
                    }

                    LaunchedEffect(secondaryBrowserState.externalFileToOpen) {
                        val grant = secondaryBrowserState.externalFileToOpen ?: return@LaunchedEffect
                        val launched = try {
                            startActivity(
                                if (secondaryBrowserState.externalOpenChooser) {
                                    ExternalOpenIntentFactory.createChooser(grant)
                                } else {
                                    ExternalOpenIntentFactory.create(grant)
                                },
                            )
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                        secondaryBrowserViewModel.completeExternalOpen(grant, launched)
                    }

                    LaunchedEffect(browserState.externalFilesToShare) {
                        val grants = browserState.externalFilesToShare.takeIf { it.isNotEmpty() }
                            ?: return@LaunchedEffect
                        val launched = try {
                            startActivity(
                                Intent.createChooser(
                                    if (grants.size == 1) {
                                        ExternalShareIntentFactory.create(grants.single())
                                    } else {
                                        ExternalShareIntentFactory.create(grants)
                                    },
                                    if (grants.size == 1) "分享文件" else "分享 ${grants.size} 个文件",
                                ),
                            )
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                        browserViewModel.completeExternalShare(grants, launched)
                    }

                    LaunchedEffect(browserState.movedOutput) {
                        val output = browserState.movedOutput ?: return@LaunchedEffect
                        homeViewModel.completeMove(browserState.currentPath, browserState.rootTitle)
                        browserViewModel.consumeMovedOutput()
                        if (dualPaneState.enabled) {
                            browserViewModel.retry()
                            secondaryBrowserViewModel.retry()
                        }
                        val message = if (browserState.moveTotalCount > 1) {
                            "已移动 ${browserState.moveCompletedCount}/${browserState.moveTotalCount} 项"
                        } else {
                            "已移动 ${output.name}"
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }

                    LaunchedEffect(browserState.copiedOutput) {
                        val output = browserState.copiedOutput ?: return@LaunchedEffect
                        homeViewModel.completeCopy(browserState.currentPath, browserState.rootTitle)
                        browserViewModel.consumeCopiedOutput()
                        if (dualPaneState.enabled) {
                            browserViewModel.retry()
                            secondaryBrowserViewModel.retry()
                        }
                        val message = if (browserState.copyTotalCount > 1) {
                            "已复制 ${browserState.copyCompletedCount}/${browserState.copyTotalCount} 项"
                        } else {
                            "已复制 ${output.name}"
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }

                    LaunchedEffect(browserState.renamedOutput) {
                        val output = browserState.renamedOutput ?: return@LaunchedEffect
                        browserViewModel.consumeRenamedOutput()
                        Toast.makeText(this@MainActivity, "已重命名为 ${output.name}", Toast.LENGTH_SHORT).show()
                    }

                    LaunchedEffect(browserState.createdFile) {
                        val output = browserState.createdFile ?: return@LaunchedEffect
                        browserViewModel.consumeCreatedFile()
                        Toast.makeText(this@MainActivity, "已新建 ${output.name}", Toast.LENGTH_SHORT).show()
                    }

                    LaunchedEffect(secondaryBrowserState.movedOutput) {
                        val output = secondaryBrowserState.movedOutput ?: return@LaunchedEffect
                        secondaryBrowserViewModel.consumeMovedOutput()
                        if (dualPaneState.enabled) {
                            browserViewModel.retry()
                            secondaryBrowserViewModel.retry()
                        }
                        val message = if (secondaryBrowserState.moveTotalCount > 1) {
                            "已移动 ${secondaryBrowserState.moveCompletedCount}/${secondaryBrowserState.moveTotalCount} 项"
                        } else {
                            "已移动 ${output.name}"
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }

                    LaunchedEffect(secondaryBrowserState.copiedOutput) {
                        val output = secondaryBrowserState.copiedOutput ?: return@LaunchedEffect
                        secondaryBrowserViewModel.consumeCopiedOutput()
                        if (dualPaneState.enabled) {
                            browserViewModel.retry()
                            secondaryBrowserViewModel.retry()
                        }
                        val message = if (secondaryBrowserState.copyTotalCount > 1) {
                            "已复制 ${secondaryBrowserState.copyCompletedCount}/${secondaryBrowserState.copyTotalCount} 项"
                        } else {
                            "已复制 ${output.name}"
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
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
                                HomeDestination.Device -> transferViewModel.clearTarget()
                                is HomeDestination.Browser -> {
                                    if (destination.path == browserState.currentPath) {
                                        transferViewModel.selectTarget(browserState.currentPath)
                                    } else {
                                        transferViewModel.clearTarget()
                                    }
                                }
                                is HomeDestination.Tab,
                                is HomeDestination.Archive,
                                is HomeDestination.ExtractionTarget,
                                is HomeDestination.MoveTarget,
                                is HomeDestination.CopyTarget -> transferViewModel.clearTarget()
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
                        if (fileOperationInFlight) return
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
                            is RecentOpenTarget.File -> browserViewModel.openEntry(target.entry)
                            null -> Unit
                        }
                    }

                    fun cancelMovePicker() {
                        if (browserViewModel.cancelMove(restoreSelection = true)) {
                            homeViewModel.returnFromMove()
                        }
                    }

                    fun cancelCopyPicker() {
                        if (browserViewModel.cancelCopy(restoreSelection = true)) {
                            homeViewModel.returnFromCopy()
                        }
                    }

                    BackHandler(
                        enabled = !textEditorState.visible && !fileToolsState.visible && (
                            pickerActive || movePickerActive || copyPickerActive || destination !is HomeDestination.Tab
                        ),
                    ) {
                        when {
                            fileOperationInFlight -> Unit
                            copyPickerActive && destination is HomeDestination.CopyTarget &&
                                destination.targetBrowser == null -> cancelCopyPicker()
                            movePickerActive && destination is HomeDestination.MoveTarget &&
                                destination.targetBrowser == null -> cancelMovePicker()
                            pickerActive && destination !is HomeDestination.Browser -> cancelPicker()
                            destination is HomeDestination.Browser -> handleBrowserBack()
                            destination is HomeDestination.Archive -> handleArchiveBack()
                            destination is HomeDestination.ExtractionTarget && destination.targetBrowser != null ->
                                handleBrowserBack()
                            destination is HomeDestination.ExtractionTarget -> homeViewModel.returnToArchive()
                            destination is HomeDestination.MoveTarget && destination.targetBrowser != null ->
                                handleBrowserBack()
                            destination is HomeDestination.MoveTarget -> cancelMovePicker()
                            destination is HomeDestination.CopyTarget && destination.targetBrowser != null ->
                                handleBrowserBack()
                            destination is HomeDestination.CopyTarget -> cancelCopyPicker()
                            destination == HomeDestination.Device -> homeViewModel.closeDevice()
                        }
                    }
                    if (fileToolsState.visible) {
                        FileToolsScreen(
                            state = fileToolsState,
                            onBack = fileToolsViewModel::close,
                            onPreviousPage = fileToolsViewModel::previousHexPage,
                            onNextPage = fileToolsViewModel::nextHexPage,
                            onJumpToOffset = fileToolsViewModel::jumpToOffset,
                            onAlgorithmChange = fileToolsViewModel::setChecksumAlgorithm,
                            onRetry = fileToolsViewModel::retry,
                        )
                    } else if (textEditorState.visible) {
                        TextEditorScreen(
                            state = textEditorState,
                            onTextChange = textEditorViewModel::updateText,
                            onEncodingChange = textEditorViewModel::setEncoding,
                            onLineEndingChange = textEditorViewModel::setLineEnding,
                            onBomChange = textEditorViewModel::setBom,
                            onReplaceAll = textEditorViewModel::replaceAll,
                            onSave = textEditorViewModel::save,
                            onReload = textEditorViewModel::reload,
                            onBack = textEditorViewModel::requestClose,
                            onDiscard = textEditorViewModel::discardAndClose,
                            onCancelClose = textEditorViewModel::cancelClose,
                            onDismissError = textEditorViewModel::dismissError,
                        )
                    } else if (destination == HomeDestination.Device) {
                        DeviceSettingsScreen(
                            state = deviceSettingsState,
                            rootState = uiState,
                            onRootModeChange = { enabled ->
                                if (!enabled) dualPaneViewModel.setEnabled(false)
                                rootGateViewModel.setRootEnabled(enabled)
                            },
                            onBack = homeViewModel::closeDevice,
                            onRetryStorage = deviceSettingsViewModel::refresh,
                        )
                    } else ISaverHomeScreen(
                        homeState = homeState,
                        locationState = locationState,
                        browserState = browserState,
                        secondaryBrowserState = secondaryBrowserState,
                        dualPaneState = dualPaneState,
                        primaryDualPaneCallbacks = DualPaneBrowserCallbacks(
                            enterDirectory = { entry ->
                                if (dualPaneState.lockedPane != PaneId.PRIMARY) browserViewModel.enterDirectory(entry)
                            },
                            back = { if (dualPaneState.lockedPane != PaneId.PRIMARY) browserViewModel.back() },
                            forward = { if (dualPaneState.lockedPane != PaneId.PRIMARY) browserViewModel.forward() },
                            retry = browserViewModel::retry,
                            loadMore = browserViewModel::loadMore,
                            query = browserViewModel::setSearchQuery,
                            toggleSelection = browserViewModel::toggleSelection,
                            clearSelection = browserViewModel::clearSelection,
                            openEntry = browserViewModel::openEntry,
                            resolveConflict = browserViewModel::resolveConflict,
                            dismissMoveError = browserViewModel::dismissFileMoveError,
                            dismissCopyError = browserViewModel::dismissFileCopyError,
                            dismissOpenError = browserViewModel::dismissFileOpenError,
                            dismissPreview = browserViewModel::dismissPreview,
                            editPreview = { entry ->
                                browserViewModel.dismissPreview()
                                textEditorViewModel.open(entry, browserState.currentPath)
                            },
                            compareSelection = { entries ->
                                browserViewModel.clearSelection()
                                fileToolsViewModel.openComparison(entries)
                            },
                        ),
                        secondaryDualPaneCallbacks = DualPaneBrowserCallbacks(
                            enterDirectory = { entry ->
                                if (dualPaneState.lockedPane != PaneId.SECONDARY) secondaryBrowserViewModel.enterDirectory(entry)
                            },
                            back = { if (dualPaneState.lockedPane != PaneId.SECONDARY) secondaryBrowserViewModel.back() },
                            forward = { if (dualPaneState.lockedPane != PaneId.SECONDARY) secondaryBrowserViewModel.forward() },
                            retry = secondaryBrowserViewModel::retry,
                            loadMore = secondaryBrowserViewModel::loadMore,
                            query = secondaryBrowserViewModel::setSearchQuery,
                            toggleSelection = secondaryBrowserViewModel::toggleSelection,
                            clearSelection = secondaryBrowserViewModel::clearSelection,
                            openEntry = secondaryBrowserViewModel::openEntry,
                            resolveConflict = secondaryBrowserViewModel::resolveConflict,
                            dismissMoveError = secondaryBrowserViewModel::dismissFileMoveError,
                            dismissCopyError = secondaryBrowserViewModel::dismissFileCopyError,
                            dismissOpenError = secondaryBrowserViewModel::dismissFileOpenError,
                            dismissPreview = secondaryBrowserViewModel::dismissPreview,
                            editPreview = { entry ->
                                secondaryBrowserViewModel.dismissPreview()
                                textEditorViewModel.open(entry, secondaryBrowserState.currentPath)
                            },
                            compareSelection = { entries ->
                                secondaryBrowserViewModel.clearSelection()
                                fileToolsViewModel.openComparison(entries)
                            },
                        ),
                        onActivatePane = dualPaneViewModel::activate,
                        onCloseDualPane = { dualPaneViewModel.setEnabled(false) },
                        onOpenDualPane = if (rootMode) {
                            { dualPaneViewModel.setEnabled(true) }
                        } else {
                            null
                        },
                        onOpenDevice = homeViewModel::openDevice,
                        onSyncDualPane = {
                            val source = if (dualPaneState.activePane == PaneId.PRIMARY) browserState else secondaryBrowserState
                            val targetPane = dualPaneState.activePane.other()
                            if (targetPane == PaneId.PRIMARY) {
                                browserViewModel.openRoot(source.currentPath, source.title, recordAccess = false)
                            } else {
                                secondaryBrowserViewModel.openRoot(source.currentPath, source.title, recordAccess = false)
                            }
                            dualPaneViewModel.syncToOther()
                        },
                        onSwapDualPane = {
                            val primaryPath = browserState.currentPath
                            val primaryTitle = browserState.title
                            browserViewModel.openRoot(
                                secondaryBrowserState.currentPath,
                                secondaryBrowserState.title,
                                recordAccess = false,
                            )
                            secondaryBrowserViewModel.openRoot(primaryPath, primaryTitle, recordAccess = false)
                            dualPaneViewModel.swap()
                        },
                        onTogglePaneLock = dualPaneViewModel::toggleLock,
                        onCopyToOtherPane = {
                            if (dualPaneState.activePane == PaneId.PRIMARY) {
                                if (
                                    secondaryBrowserState.canCreateDirectory &&
                                    secondaryBrowserState.currentPath != browserState.currentPath &&
                                    browserViewModel.beginCopySelection()
                                ) browserViewModel.copyTo(secondaryBrowserState.currentPath)
                            } else if (
                                browserState.canCreateDirectory &&
                                browserState.currentPath != secondaryBrowserState.currentPath &&
                                secondaryBrowserViewModel.beginCopySelection()
                            ) secondaryBrowserViewModel.copyTo(browserState.currentPath)
                        },
                        onMoveToOtherPane = {
                            if (dualPaneState.activePane == PaneId.PRIMARY) {
                                if (
                                    secondaryBrowserState.canCreateDirectory &&
                                    secondaryBrowserState.currentPath != browserState.currentPath &&
                                    browserViewModel.beginMoveSelection()
                                ) browserViewModel.moveTo(secondaryBrowserState.currentPath)
                            } else if (
                                browserState.canCreateDirectory &&
                                browserState.currentPath != secondaryBrowserState.currentPath &&
                                secondaryBrowserViewModel.beginMoveSelection()
                            ) secondaryBrowserViewModel.moveTo(browserState.currentPath)
                        },
                        displayMode = browserState.displayMode,
                        sortSpec = browserState.sortSpec,
                        onSelectTab = { tab ->
                            if (!fileOperationInFlight) homeViewModel.selectTab(tab)
                        },
                        onOpenLocation = { path, title ->
                            if (!fileOperationInFlight) {
                                homeViewModel.openLocation(path, title)
                            }
                        },
                        onAddCustomLocation = locationHomeViewModel::addCustomLocation,
                        onEditCustomLocation = locationHomeViewModel::editCustomLocation,
                        onRemoveCustomLocation = locationHomeViewModel::removeCustomLocation,
                        onRetryLocations = locationHomeViewModel::refresh,
                        onClearLocationError = locationHomeViewModel::clearAddError,
                        onRevalidateCustomLocation = locationHomeViewModel::revalidateCustomLocation,
                        virtualViewState = virtualViewState,
                        onOpenVirtualFolder = virtualViewViewModel::openFolder,
                        onOpenVirtualReference = virtualViewViewModel::openReference,
                        onRetryVirtualReference = virtualViewViewModel::openReference,
                        onAddVirtualReferenceAgain = { reference ->
                            virtualViewViewModel.beginAddReference(
                                reference.targetPath,
                                reference.displayName,
                                reference.entryType,
                            )
                        },
                        onRebindVirtualReference = { reference ->
                            virtualViewViewModel.beginRebind(reference)
                            val parentValue = reference.targetPath.value.substringBeforeLast('/', "").ifEmpty { "/" }
                            val parent = RootPath.parse(parentValue).getOrNull() ?: RootPath.parse("/").getOrThrow()
                            homeViewModel.openLocation(parent, "重新定位", HomeTab.VIEWS, recordAccess = false)
                        },
                        onConfirmRebindVirtualReference = { entry ->
                            virtualViewViewModel.confirmRebind(entry)
                            homeViewModel.selectTab(HomeTab.VIEWS)
                        },
                        onNavigateVirtual = virtualViewViewModel::navigateTo,
                        onCreateVirtualFolder = virtualViewViewModel::createFolder,
                        onRenameVirtualNode = virtualViewViewModel::renameNode,
                        onMoveVirtualNode = virtualViewViewModel::moveNode,
                        onDeleteVirtualFolder = virtualViewViewModel::deleteFolder,
                        onDismissVirtualDelete = virtualViewViewModel::dismissDeleteConfirmation,
                        onRemoveVirtualReference = virtualViewViewModel::removeReference,
                        onAddCurrentToVirtualView = {
                            virtualViewViewModel.beginAddReference(
                                browserState.currentPath,
                                browserState.title,
                                EntryType.DIRECTORY,
                            )
                        },
                        onAddEntryToVirtualView = { entry ->
                            virtualViewViewModel.beginAddReference(entry.path, entry.name, entry.type)
                        },
                        onOpenVirtualPickerFolder = virtualViewViewModel::openPickerFolder,
                        onCreateVirtualPickerFolder = virtualViewViewModel::createPickerFolder,
                        onConfirmAddVirtualReference = virtualViewViewModel::confirmAddReference,
                        onDismissAddVirtualReference = virtualViewViewModel::dismissAddReference,
                        onClearVirtualMessage = virtualViewViewModel::clearMessage,
                        onEnterDirectory = { entry ->
                            !fileOperationInFlight && browserViewModel.enterDirectory(entry)
                        },
                        onBrowserBack = ::handleBrowserBack,
                        onBrowserForward = { browserViewModel.forward() },
                        onStartDeepSearch = browserViewModel::startDeepSearch,
                        onCancelDeepSearch = browserViewModel::cancelDeepSearch,
                        onClearDeepSearch = browserViewModel::clearDeepSearch,
                        onOpenDeepSearchResultLocation = browserViewModel::openDeepSearchResultLocation,
                        onRetryBrowser = browserViewModel::retry,
                        onLoadMore = browserViewModel::loadMore,
                        onSearchQueryChange = browserViewModel::setSearchQuery,
                        onDisplayModeChange = browserViewModel::setDisplayMode,
                        onSortChange = browserViewModel::setSort,
                        onCreateDirectory = browserViewModel::createDirectory,
                        onCreateFile = browserViewModel::createFile,
                        onDismissCreateDirectoryError = browserViewModel::dismissCreateDirectoryError,
                        onDismissCreateFileError = browserViewModel::dismissCreateFileError,
                        onToggleSelection = browserViewModel::toggleSelection,
                        onSelectAllVisible = browserViewModel::selectAllVisible,
                        onInvertVisibleSelection = browserViewModel::invertVisibleSelection,
                        onSelectSameType = browserViewModel::selectSameType,
                        onOpenBrowserEntry = browserViewModel::openEntry,
                        onOpenWithBrowserEntry = browserViewModel::openWith,
                        onClearBrowserSelection = browserViewModel::clearSelection,
                        onDismissFileInfo = browserViewModel::dismissFileInfo,
                        onShowFileInfo = browserViewModel::showFileInfo,
                        onCalculateChecksum = browserViewModel::calculateSelectedChecksum,
                        onChecksumAlgorithmChange = browserViewModel::setChecksumAlgorithm,
                        onChangePermissions = browserViewModel::changePermissions,
                        onConfirmPermissionChange = browserViewModel::confirmPermissionChange,
                        onDismissPermissionConfirmation = browserViewModel::dismissPermissionConfirmation,
                        onDismissFileOpenError = browserViewModel::dismissFileOpenError,
                        onDismissPreview = browserViewModel::dismissPreview,
                        onEditPreview = if (rootMode) {
                            { entry ->
                                browserViewModel.dismissPreview()
                                textEditorViewModel.open(entry, browserState.currentPath)
                            }
                        } else {
                            null
                        },
                        onOpenHex = fileToolsViewModel::openHex,
                        onCompareSelection = { entries ->
                            browserViewModel.clearSelection()
                            fileToolsViewModel.openComparison(entries)
                        },
                        onShareBrowserEntry = browserViewModel::shareEntry,
                        onShareBrowserSelection = browserViewModel::shareSelection,
                        onRecycleBrowserSelection = if (rootMode) browserViewModel::recycleSelection else null,
                        onDismissFileShareError = browserViewModel::dismissFileShareError,
                        onMoveBrowserEntry = if (rootMode) {
                            { entry ->
                                if (!pickerActive && browserViewModel.beginMove(entry)) {
                                    homeViewModel.chooseMoveTarget()
                                }
                            }
                        } else null,
                        onMoveBrowserSelection = if (rootMode) {
                            {
                                if (!pickerActive && browserViewModel.beginMoveSelection()) {
                                    homeViewModel.chooseMoveTarget()
                                }
                            }
                        } else null,
                        onMoveHere = {
                            val move = homeState.destination as? HomeDestination.MoveTarget
                            if (
                                canUseRealTarget(move?.targetBrowser, browserState) &&
                                browserState.currentPath != move?.sourceBrowser?.path
                            ) {
                                browserViewModel.moveTo(browserState.currentPath)
                            }
                        },
                        onDismissFileMoveError = browserViewModel::dismissFileMoveError,
                        onCopyBrowserEntry = if (rootMode) {
                            { entry ->
                                if (!pickerActive && !movePickerActive && browserViewModel.beginCopy(entry)) {
                                    homeViewModel.chooseCopyTarget()
                                }
                            }
                        } else null,
                        onCopyBrowserSelection = if (rootMode) {
                            {
                                if (!pickerActive && !movePickerActive && browserViewModel.beginCopySelection()) {
                                    homeViewModel.chooseCopyTarget()
                                }
                            }
                        } else null,
                        onCopyHere = {
                            val copy = homeState.destination as? HomeDestination.CopyTarget
                            if (
                                canUseRealTarget(copy?.targetBrowser, browserState) &&
                                browserState.currentPath != copy?.sourceBrowser?.path
                            ) {
                                browserViewModel.copyTo(browserState.currentPath)
                            }
                        },
                        onDismissFileCopyError = browserViewModel::dismissFileCopyError,
                        onResolveBrowserConflict = browserViewModel::resolveConflict,
                        onRenameBrowserEntry = if (rootMode) browserViewModel::renameEntry else null,
                        onPreviewBatchRename = if (rootMode) browserViewModel::previewBatchRename else null,
                        onExecuteBatchRename = if (rootMode) browserViewModel::executeBatchRename else null,
                        onDismissBatchRename = browserViewModel::dismissBatchRename,
                        onClearFinishedTasks = browserViewModel::clearFinishedTasks,
                        onPauseTask = browserViewModel::pauseTask,
                        onResumeTask = browserViewModel::resumeTask,
                        onCancelTask = browserViewModel::cancelTask,
                        onRecycleEntry = if (rootMode) browserViewModel::recycleEntry else null,
                        onDeleteEntryPermanently = if (rootMode) browserViewModel::deleteEntryPermanently else null,
                        onRestoreTrashItem = browserViewModel::restoreTrashItem,
                        onRestoreTrashItemWithAction = browserViewModel::restoreTrashItem,
                        onDismissRestoreConflict = browserViewModel::dismissRestoreConflict,
                        onDeleteTrashItemPermanently = browserViewModel::deleteTrashItemPermanently,
                        onRestoreAllTrashItems = browserViewModel::restoreTrashItems,
                        onClearTrash = browserViewModel::clearTrash,
                        onDismissTrashError = browserViewModel::dismissTrashError,
                        onDismissFileRenameError = browserViewModel::dismissFileRenameError,
                        onCompress = if (rootMode) browserViewModel::compress else null,
                        onDismissCompressionMessage = browserViewModel::clearCompressionMessage,
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
                            if (
                                canUseRealTarget(extraction?.targetBrowser, browserState)
                            ) {
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
            handleIncomingShareIntent(intent)
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        when (ShareTarget.fromIntent(intent)) {
            ShareTarget.SAVE -> transferViewModel.handleIntent(intent)
            ShareTarget.OPEN_LOCATION -> openSharedFileLocation(requireNotNull(intent))
            null -> Unit
        }
    }

    private fun openSharedFileLocation(intent: Intent) {
        transferViewModel.exitRootGate()
        val location = ShareSourceLocationResolver.resolve(intent)
        if (location == null) {
            homeViewModel.selectTab(HomeTab.VIEWS)
            Toast.makeText(this, "无法识别来源目录", Toast.LENGTH_SHORT).show()
            return
        }
        homeViewModel.openLocation(
            path = location.directory,
            displayName = location.title,
            source = HomeTab.VIEWS,
            recordAccess = false,
        )
    }
}

internal class TextEditorViewModelFactory(
    private val repository: com.iamxpp.isaver.texteditor.TextEditorRepository,
    private val drafts: com.iamxpp.isaver.texteditor.TextDraftStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TextEditorViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return TextEditorViewModel(repository, drafts) as T
    }
}

internal class FileToolsViewModelFactory(
    private val hexRepository: com.iamxpp.isaver.filetools.HexViewerRepository,
    private val comparisonRepository: com.iamxpp.isaver.filetools.FileComparisonRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FileToolsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return FileToolsViewModel(hexRepository, comparisonRepository) as T
    }
}

internal class BrowserViewModelFactory(
    private val fileSystem: RootFileSystem,
    private val preferencesStore: BrowserPreferencesStore,
    private val archiveRepository: com.iamxpp.isaver.archive.ArchiveRepository? = null,
    private val recentRepository: com.iamxpp.isaver.recent.RecentRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rootExportRepository: com.iamxpp.isaver.export.RootExportRepository? = null,
    private val directoryShareRepository: com.iamxpp.isaver.archive.DirectoryShareRepository? = null,
    private val fileMoveRepository: com.iamxpp.isaver.fileops.FileMoveRepository? = null,
    private val fileCopyRepository: com.iamxpp.isaver.fileops.FileCopyRepository? = null,
    private val fileRenameRepository: com.iamxpp.isaver.fileops.FileRenameRepository? = null,
    private val operationTaskStore: com.iamxpp.isaver.tasks.OperationTaskStore? = null,
    private val trashRepository: com.iamxpp.isaver.trash.TrashRepository? = null,
    private val bookmarkRepository: com.iamxpp.isaver.bookmarks.BookmarkRepository? = null,
    private val virtualViewRepository: com.iamxpp.isaver.virtualviews.VirtualViewRepository? = null,
    private val browserSessionStore: BrowserSessionStore? = null,
    private val previewRepository: RootPreviewRepository? = null,
    private val checksumFile: suspend (com.iamxpp.isaver.domain.DirectoryEntry) -> OperationResult<String> = {
        OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法计算校验和")
    },
    private val checksumFileByAlgorithm: suspend (com.iamxpp.isaver.domain.DirectoryEntry, com.iamxpp.isaver.fileops.ChecksumAlgorithm) -> OperationResult<String> = { entry, algorithm ->
        if (algorithm == com.iamxpp.isaver.fileops.ChecksumAlgorithm.SHA256) checksumFile(entry)
        else OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "不支持此校验算法")
    },
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
            exportFile = rootExportRepository?.let { repository -> repository::export } ?: { entry ->
                OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法打开文件")
            },
            shareFile = rootExportRepository?.let { repository -> repository::share } ?: { entry ->
                OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法分享文件")
            },
            shareDirectory = directoryShareRepository?.let { repository -> repository::share },
            moveFile = fileMoveRepository?.let { repository ->
                { entry, sourceDirectory, targetDirectory, conflictAction ->
                    repository.move(entry, sourceDirectory, targetDirectory, conflictAction)
                }
            } ?: { _, _, _, _ ->
                OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法移动文件")
            },
            copyFile = fileCopyRepository?.let { repository ->
                { entry, sourceDirectory, targetDirectory, conflictAction ->
                    repository.copy(entry, sourceDirectory, targetDirectory, conflictAction)
                }
            } ?: { _, _, _, _ ->
                OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法复制文件")
            },
            renameFile = fileRenameRepository?.let { repository -> repository::rename } ?: { _, _, _ ->
                OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法重命名文件")
            },
            revokeExport = rootExportRepository?.let { repository -> repository::revoke } ?: {},
            operationTaskStore = operationTaskStore,
            trashRepository = trashRepository,
            checksumFile = checksumFile,
            checksumFileByAlgorithm = checksumFileByAlgorithm,
            bookmarkRepository = bookmarkRepository,
            browserSessionStore = browserSessionStore,
            previewRepository = previewRepository ?: RootPreviewRepository(fileSystem),
            relocateVirtualReferences = { oldIdentity, output ->
                val newIdentity = (fileSystem.identity(output.path) as? OperationResult.Success)?.value
                    ?: return@BrowserViewModel
                virtualViewRepository?.relocateReferences(oldIdentity, output.path, output.type, newIdentity)
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
    private val operationTaskStore: com.iamxpp.isaver.tasks.OperationTaskStore? = null,
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
            operationTaskStore = operationTaskStore,
        ) as T
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

internal class VirtualViewViewModelFactory(
    private val store: com.iamxpp.isaver.ui.virtualviews.VirtualViewStore,
    private val rootFileSystem: RootFileSystem? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(VirtualViewViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return VirtualViewViewModel(store, rootFileSystem, ioDispatcher) as T
    }
}

private class RootGateViewModelFactory(
    private val rootSession: RootSession,
    private val modeStore: com.iamxpp.isaver.data.access.FileAccessModeStore,
    private val accessController: com.iamxpp.isaver.data.access.FileAccessController,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RootGateViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }

        @Suppress("UNCHECKED_CAST")
        return RootGateViewModel(
            rootSession = rootSession,
            checkDispatcher = Dispatchers.IO,
            modeStore = modeStore,
            accessController = accessController,
        ) as T
    }
}

private class DeviceSettingsViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DeviceSettingsViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return DeviceSettingsViewModel(DeviceOverviewRepository()) as T
    }
}
