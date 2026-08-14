package com.isaver.filemanager

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.isaver.filemanager.data.local.BrowserPreferencesRepository
import com.isaver.filemanager.data.local.BrowserPreferencesStore
import com.isaver.filemanager.data.local.BrowserSessionRepository
import com.isaver.filemanager.data.local.BrowserSessionStore
import com.isaver.filemanager.data.local.ISaverDatabase
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootSession
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootPathRiskPolicy
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.data.root.LibsuRootFileSystem
import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.data.access.FileAccessController
import com.isaver.filemanager.data.access.FileAccessMode
import com.isaver.filemanager.data.access.FileAccessModeRepository
import com.isaver.filemanager.data.access.FileAccessModeStore
import com.isaver.filemanager.data.access.LocalReadOnlyFileSystem
import com.isaver.filemanager.data.access.ModeAwareRootFileSystem
import com.isaver.filemanager.locations.CustomLocationRepository
import com.isaver.filemanager.locations.CustomLocationResult
import com.isaver.filemanager.locations.LocationId
import com.isaver.filemanager.locations.LocationResolver
import com.isaver.filemanager.locations.StorageLocation
import com.isaver.filemanager.recent.RecentRepository
import com.isaver.filemanager.recent.RecentItemType
import com.isaver.filemanager.share.ShareIntentParser
import com.isaver.filemanager.transfer.IncomingFileCache
import com.isaver.filemanager.transfer.IncomingStreamRegistry
import com.isaver.filemanager.transfer.RootFileTransferRepository
import com.isaver.filemanager.transfer.TargetNameResolver
import com.isaver.filemanager.transfer.TransferDependencies
import com.isaver.filemanager.archive.ArchiveRepository
import com.isaver.filemanager.archive.DirectoryShareRepository
import com.isaver.filemanager.archive.LocalArchiveEngine
import com.isaver.filemanager.export.ExternalFileRegistry
import com.isaver.filemanager.export.MimeResolver
import com.isaver.filemanager.export.RootExportCache
import com.isaver.filemanager.export.RootExportRepository
import com.isaver.filemanager.fileops.FileMoveRepository
import com.isaver.filemanager.fileops.FileCopyRepository
import com.isaver.filemanager.fileops.FileRenameRepository
import com.isaver.filemanager.fileops.FileChecksumRepository
import com.isaver.filemanager.filetools.FileComparisonRepository
import com.isaver.filemanager.filetools.HexViewerRepository
import com.isaver.filemanager.tasks.OperationTaskRepository
import com.isaver.filemanager.texteditor.EditorContent
import com.isaver.filemanager.texteditor.EditorContentCache
import com.isaver.filemanager.texteditor.TextDraftStore
import com.isaver.filemanager.texteditor.TextEditorRepository
import com.isaver.filemanager.trash.TrashRepository
import com.isaver.filemanager.bookmarks.BookmarkRepository
import com.isaver.filemanager.virtualviews.VirtualViewRepository
import com.isaver.filemanager.ui.LocationHomeAppResolver
import com.isaver.filemanager.ui.LocationHomeCustomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ISaverApplication : Application() {
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val rootSession: RootSession by lazy { LibsuRootSession() }
    internal val fileAccessController by lazy { FileAccessController(FileAccessMode.LOCAL_READ_ONLY) }
    private val privilegedFileSystem: RootFileSystem by lazy {
        LibsuRootFileSystem("${applicationInfo.nativeLibraryDir}/libisaver_fs_helper.so")
    }
    private val localReadOnlyFileSystem: RootFileSystem by lazy { LocalReadOnlyFileSystem() }
    internal val rootFileSystem: RootFileSystem by lazy {
        ModeAwareRootFileSystem(fileAccessController, privilegedFileSystem, localReadOnlyFileSystem)
    }
    internal val database: ISaverDatabase by lazy {
        Room.databaseBuilder(this, ISaverDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                ISaverDatabase.MIGRATION_1_2,
                ISaverDatabase.MIGRATION_2_3,
                ISaverDatabase.MIGRATION_3_4,
                ISaverDatabase.MIGRATION_4_5,
                ISaverDatabase.MIGRATION_5_6,
                ISaverDatabase.MIGRATION_6_7,
                ISaverDatabase.MIGRATION_7_8,
            )
            .build()
    }
    internal val customLocationRepository: CustomLocationRepository by lazy {
        CustomLocationRepository(
            dao = database.customLocationDao(),
            idFactory = { LocationId.of(UUID.randomUUID().toString()) },
            clock = System::currentTimeMillis,
        )
    }
    internal val recentRepository: RecentRepository by lazy {
        RecentRepository(
            dao = database.recentItemDao(),
            clock = System::currentTimeMillis,
        )
    }
    internal val locationResolver: LocationResolver by lazy {
        LocationResolver(rootFileSystem, Dispatchers.IO)
    }
    internal val locationHomeAppResolver: LocationHomeAppResolver by lazy {
        LocationHomeAppResolver(locationResolver::resolve)
    }
    internal val locationHomeCustomStore: LocationHomeCustomStore by lazy {
        EmptyCustomLocationStore
    }
    private val browserDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = applicationScope,
            produceFile = { preferencesDataStoreFile("browser.preferences_pb") },
        )
    }
    private val accessModeDataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = applicationScope,
            produceFile = { preferencesDataStoreFile("file-access.preferences_pb") },
        )
    }
    internal val fileAccessModeStore: FileAccessModeStore by lazy {
        FileAccessModeRepository(accessModeDataStore)
    }
    internal val browserPreferencesStore: BrowserPreferencesStore by lazy {
        BrowserPreferencesRepository(browserDataStore)
    }
    internal val browserSessionStore: BrowserSessionStore by lazy { BrowserSessionRepository(browserDataStore) }
    internal val secondaryBrowserPreferencesStore: BrowserPreferencesStore by lazy {
        BrowserPreferencesRepository(browserDataStore, "secondary")
    }
    internal val secondaryBrowserSessionStore: BrowserSessionStore by lazy {
        BrowserSessionRepository(browserDataStore, "secondary")
    }
    internal val shareIntentParser: ShareIntentParser by lazy { ShareIntentParser(this) }
    internal val incomingFileCache: IncomingFileCache by lazy {
        IncomingFileCache(contentResolver, cacheDir, Dispatchers.IO)
    }
    internal val incomingStreamRegistry: IncomingStreamRegistry by lazy {
        IncomingStreamRegistry(
            authority = "$packageName.incoming-stream",
            validate = incomingFileCache::validateNow,
        )
    }
    internal val transferRepository: RootFileTransferRepository by lazy {
        RootFileTransferRepository(
            fileSystem = rootFileSystem,
            nameResolver = TargetNameResolver(),
            issueSource = { cached ->
                incomingStreamRegistry.issue(cached).fold(
                    onSuccess = { OperationResult.Success(it) },
                    onFailure = {
                        OperationResult.Failure(
                            code = ErrorCode.SOURCE_UNREADABLE,
                            userMessage = "无法读取分享文件",
                            technicalMessage = "Incoming stream capability could not be issued",
                        )
                    },
                )
            },
            revokeSource = incomingStreamRegistry::revoke,
            cleanupCache = incomingFileCache::cleanup,
        )
    }
    internal val archiveRepository: ArchiveRepository by lazy {
        ArchiveRepository(
            rootFileSystem = rootFileSystem,
            localEngine = LocalArchiveEngine(),
            cacheDir = cacheDir,
            publish = { cached, outputName, target ->
                transferRepository.transfer(cached, outputName, target)
            },
            issueSource = { cached ->
                incomingStreamRegistry.issue(cached).fold(
                    onSuccess = { OperationResult.Success(it) },
                    onFailure = {
                        OperationResult.Failure(
                            ErrorCode.SOURCE_UNREADABLE,
                            "无法读取解压文件",
                            "Extraction stream capability could not be issued",
                        )
                    },
                )
            },
            revokeSource = incomingStreamRegistry::revoke,
            recordCompressed = { entry ->
                recentRepository.recordCompressed(entry.path, entry.name)
            },
            recordExtracted = { entry ->
                recentRepository.recordExtracted(entry.path, entry.name)
            },
        )
    }
    internal val textDraftStore by lazy { TextDraftStore(filesDir, Dispatchers.IO) }
    private val editorContentCache by lazy { EditorContentCache(cacheDir, Dispatchers.IO) }
    internal val textEditorRepository by lazy {
        TextEditorRepository(rootFileSystem, issueContent = { bytes ->
            when (val cached = editorContentCache.write(bytes)) {
                is OperationResult.Failure -> cached
                is OperationResult.Success -> incomingStreamRegistry.issue(cached.value).fold(
                    onSuccess = { source -> OperationResult.Success(EditorContent(source) {
                        incomingStreamRegistry.revoke(source)
                        editorContentCache.discard(cached.value)
                    }) },
                    onFailure = {
                        editorContentCache.discard(cached.value)
                        OperationResult.Failure(ErrorCode.COMMAND_FAILED, "无法准备编辑内容")
                    },
                )
            }
        })
    }
    internal val rootExportCache: RootExportCache by lazy {
        RootExportCache(
            rootFileSystem = rootFileSystem,
            cacheDir = cacheDir,
            ioDispatcher = Dispatchers.IO,
        )
    }
    internal val externalFileRegistry: ExternalFileRegistry by lazy {
        ExternalFileRegistry(
            authority = "$packageName.external-file",
            validate = rootExportCache::validateNow,
            onDiscard = rootExportCache::discardNow,
            scheduleExpiry = { _, delayMillis, cleanup ->
                applicationScope.launch {
                    delay(delayMillis)
                    cleanup()
                }
            },
        )
    }
    internal val rootExportRepository: RootExportRepository by lazy {
        RootExportRepository(
            cache = rootExportCache,
            registry = externalFileRegistry,
            mimeResolver = MimeResolver(),
        )
    }
    internal val directoryShareRepository: DirectoryShareRepository by lazy {
        DirectoryShareRepository(archiveRepository, rootExportRepository)
    }
    internal val trashRepository: TrashRepository by lazy { TrashRepository(rootFileSystem, database.trashItemDao()) }
    internal val fileMoveRepository: FileMoveRepository by lazy {
        FileMoveRepository(rootFileSystem, trashRepository)
    }
    internal val fileCopyRepository: FileCopyRepository by lazy {
        FileCopyRepository(rootFileSystem, trashRepository)
    }
    internal val fileRenameRepository: FileRenameRepository by lazy {
        FileRenameRepository(rootFileSystem)
    }
    internal val fileChecksumRepository: FileChecksumRepository by lazy { FileChecksumRepository(rootFileSystem) }
    internal val hexViewerRepository by lazy { HexViewerRepository(rootFileSystem) }
    internal val fileComparisonRepository by lazy {
        FileComparisonRepository(rootFileSystem, fileChecksumRepository)
    }
    internal val bookmarkRepository: BookmarkRepository by lazy { BookmarkRepository(database.bookmarkDao()) }
    internal val virtualViewRepository: VirtualViewRepository by lazy { VirtualViewRepository(database) }
    internal val operationTaskRepository: OperationTaskRepository by lazy {
        OperationTaskRepository(database.operationTaskDao())
    }
    internal val transferDependencies: TransferDependencies by lazy {
        TransferDependencies(
            parseShare = shareIntentParser::parseAsync,
            validateTarget = ::validateTransferTarget,
            cacheIncoming = incomingFileCache::cache,
            validateCache = incomingFileCache::validate,
            cleanupIncoming = incomingFileCache::cleanup,
            transferCached = { cached, outputName, target, mayContinue ->
                transferRepository.transfer(cached, outputName, target, mayContinue)
            },
            recordSaved = { entry ->
                recentRepository.recordSaved(
                    canonicalPath = entry.path,
                    displayName = entry.name,
                    note = null,
                    type = RecentItemType.FILE,
                )
            },
            workDispatcher = Dispatchers.IO,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Detailed libsu logging stays disabled, including in debug builds, to avoid path disclosure.
        applicationScope.launch {
            incomingFileCache.cleanupOrphans(System.currentTimeMillis())
            rootExportCache.cleanupOrphans(System.currentTimeMillis())
            operationTaskRepository.reconcileInterrupted()
            trashRepository.reconcilePending()
            virtualViewRepository.cleanupEmptyLegacyMigrationFolder()
        }
    }

    private suspend fun validateTransferTarget(path: RootPath): OperationResult<RootPath> {
        if (RootPathRiskPolicy.isProtected(path)) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "系统保护区域仅允许浏览")
        }
        val original = rootFileSystem.stat(path)
        if (original !is OperationResult.Success ||
            original.value.type != EntryType.DIRECTORY ||
            !original.value.writable ||
            original.value.symbolicLink
        ) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "请选择可写的真实文件夹")
        }
        val canonical = rootFileSystem.canonicalize(path)
        if (canonical !is OperationResult.Success) return canonical
        val canonicalEntry = rootFileSystem.stat(canonical.value)
        if (canonicalEntry !is OperationResult.Success ||
            canonicalEntry.value.type != EntryType.DIRECTORY ||
            !canonicalEntry.value.writable ||
            canonicalEntry.value.symbolicLink
        ) {
            return OperationResult.Failure(ErrorCode.NOT_WRITABLE, "请选择可写的真实文件夹")
        }
        return canonical
    }

    private companion object {
        const val DATABASE_NAME = "isaver.db"
    }
}

internal class CustomLocationStoreAdapter(
    private val repository: CustomLocationRepository,
) : LocationHomeCustomStore {
    override fun observeAll(): Flow<List<StorageLocation.Direct>> = repository.observeAll()

    override suspend fun add(name: String, path: RootPath): CustomLocationResult = repository.add(name, path)

    override suspend fun update(
        id: LocationId,
        name: String,
        path: RootPath,
    ): CustomLocationResult = repository.update(id, name, path)

    override suspend fun remove(id: LocationId): CustomLocationResult = repository.remove(id)
}

private object EmptyCustomLocationStore : LocationHomeCustomStore {
    override fun observeAll(): Flow<List<StorageLocation.Direct>> = flowOf(emptyList())
    override suspend fun add(name: String, path: RootPath) = CustomLocationResult.InvalidOrder
    override suspend fun update(id: LocationId, name: String, path: RootPath) = CustomLocationResult.InvalidOrder
    override suspend fun remove(id: LocationId) = CustomLocationResult.NotFound
}
