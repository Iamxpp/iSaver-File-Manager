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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.OperationResult
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
import com.iamxpp.isaver.ui.files.HomeTab
import com.iamxpp.isaver.ui.theme.ISaverTheme
import com.iamxpp.isaver.ui.archive.ArchiveBackResult
import com.iamxpp.isaver.ui.archive.ArchiveViewModel
import com.iamxpp.isaver.ui.recent.RecentOpenTarget
import com.iamxpp.isaver.ui.recent.RecentViewModel
import com.iamxpp.isaver.share.ShareSourceLocationResolver
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
            rootExportRepository = app.rootExportRepository,
            fileMoveRepository = app.fileMoveRepository,
            fileCopyRepository = app.fileCopyRepository,
            fileRenameRepository = app.fileRenameRepository,
            operationTaskStore = app.operationTaskRepository,
            trashRepository = app.trashRepository,
            checksumFile = app.fileChecksumRepository::sha256,
            bookmarkRepository = app.bookmarkRepository,
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
            handleIncomingShareIntent(intent)
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
                    val movePickerActive = browserState.moveSelection != null
                    val copyPickerActive = browserState.copySelection != null
                    val fileOperationInFlight = browserState.movingFile || browserState.copyingFile || browserState.renamingFile

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
                        enabled = pickerActive || movePickerActive || copyPickerActive ||
                            destination !is HomeDestination.Tab,
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
                        }
                    }
                    ISaverHomeScreen(
                        homeState = homeState,
                        locationState = locationState,
                        browserState = browserState,
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
                        onEnterDirectory = { entry ->
                            !fileOperationInFlight && browserViewModel.enterDirectory(entry)
                        },
                        onBrowserBack = ::handleBrowserBack,
                        onBrowserForward = { browserViewModel.forward() },
                        onToggleCurrentBookmark = browserViewModel::toggleCurrentBookmark,
                        onOpenBookmark = browserViewModel::openBookmark,
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
                        onOpenBrowserEntry = browserViewModel::openEntry,
                        onOpenWithBrowserEntry = browserViewModel::openWith,
                        onClearBrowserSelection = browserViewModel::clearSelection,
                        onDismissFileInfo = browserViewModel::dismissFileInfo,
                        onShowFileInfo = browserViewModel::showFileInfo,
                        onCalculateSha256 = browserViewModel::calculateSha256,
                        onDismissFileOpenError = browserViewModel::dismissFileOpenError,
                        onShareBrowserEntry = browserViewModel::shareEntry,
                        onShareBrowserSelection = browserViewModel::shareSelection,
                        onRecycleBrowserSelection = browserViewModel::recycleSelection,
                        onDismissFileShareError = browserViewModel::dismissFileShareError,
                        onMoveBrowserEntry = { entry ->
                            if (!pickerActive && browserViewModel.beginMove(entry)) {
                                homeViewModel.chooseMoveTarget()
                            }
                        },
                        onMoveBrowserSelection = {
                            if (!pickerActive && browserViewModel.beginMoveSelection()) {
                                homeViewModel.chooseMoveTarget()
                            }
                        },
                        onMoveHere = {
                            val move = homeState.destination as? HomeDestination.MoveTarget
                            if (
                                move?.targetBrowser != null &&
                                browserState.canCreateDirectory &&
                                browserState.currentPath != move.sourceBrowser.path
                            ) {
                                browserViewModel.moveTo(browserState.currentPath)
                            }
                        },
                        onDismissFileMoveError = browserViewModel::dismissFileMoveError,
                        onCopyBrowserEntry = { entry ->
                            if (!pickerActive && !movePickerActive && browserViewModel.beginCopy(entry)) {
                                homeViewModel.chooseCopyTarget()
                            }
                        },
                        onCopyBrowserSelection = {
                            if (!pickerActive && !movePickerActive && browserViewModel.beginCopySelection()) {
                                homeViewModel.chooseCopyTarget()
                            }
                        },
                        onCopyHere = {
                            val copy = homeState.destination as? HomeDestination.CopyTarget
                            if (
                                copy?.targetBrowser != null &&
                                browserState.canCreateDirectory &&
                                browserState.currentPath != copy.sourceBrowser.path
                            ) {
                                browserViewModel.copyTo(browserState.currentPath)
                            }
                        },
                        onDismissFileCopyError = browserViewModel::dismissFileCopyError,
                        onResolveBrowserConflict = browserViewModel::resolveConflict,
                        onRenameBrowserEntry = browserViewModel::renameEntry,
                        onPreviewBatchRename = browserViewModel::previewBatchRename,
                        onExecuteBatchRename = browserViewModel::executeBatchRename,
                        onDismissBatchRename = browserViewModel::dismissBatchRename,
                        onClearFinishedTasks = browserViewModel::clearFinishedTasks,
                        onPauseTask = browserViewModel::pauseTask,
                        onResumeTask = browserViewModel::resumeTask,
                        onCancelTask = browserViewModel::cancelTask,
                        onRecycleEntry = browserViewModel::recycleEntry,
                        onDeleteEntryPermanently = browserViewModel::deleteEntryPermanently,
                        onRestoreTrashItem = browserViewModel::restoreTrashItem,
                        onDeleteTrashItemPermanently = browserViewModel::deleteTrashItemPermanently,
                        onRestoreAllTrashItems = browserViewModel::restoreTrashItems,
                        onClearTrash = browserViewModel::clearTrash,
                        onDismissTrashError = browserViewModel::dismissTrashError,
                        onDismissFileRenameError = browserViewModel::dismissFileRenameError,
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

internal class BrowserViewModelFactory(
    private val fileSystem: RootFileSystem,
    private val preferencesStore: BrowserPreferencesStore,
    private val archiveRepository: com.iamxpp.isaver.archive.ArchiveRepository? = null,
    private val recentRepository: com.iamxpp.isaver.recent.RecentRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rootExportRepository: com.iamxpp.isaver.export.RootExportRepository? = null,
    private val fileMoveRepository: com.iamxpp.isaver.fileops.FileMoveRepository? = null,
    private val fileCopyRepository: com.iamxpp.isaver.fileops.FileCopyRepository? = null,
    private val fileRenameRepository: com.iamxpp.isaver.fileops.FileRenameRepository? = null,
    private val operationTaskStore: com.iamxpp.isaver.tasks.OperationTaskStore? = null,
    private val trashRepository: com.iamxpp.isaver.trash.TrashRepository? = null,
    private val bookmarkRepository: com.iamxpp.isaver.bookmarks.BookmarkRepository? = null,
    private val checksumFile: suspend (com.iamxpp.isaver.domain.DirectoryEntry) -> OperationResult<String> = {
        OperationResult.Failure(com.iamxpp.isaver.domain.ErrorCode.COMMAND_FAILED, "无法计算校验和")
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
            bookmarkRepository = bookmarkRepository,
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
