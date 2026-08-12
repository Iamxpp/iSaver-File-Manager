package com.iamxpp.isaver

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.iamxpp.isaver.data.local.BrowserPreferencesRepository
import com.iamxpp.isaver.data.local.BrowserPreferencesStore
import com.iamxpp.isaver.data.local.ISaverDatabase
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootPathRiskPolicy
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.data.root.LibsuRootFileSystem
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.locations.CustomLocationRepository
import com.iamxpp.isaver.locations.CustomLocationResult
import com.iamxpp.isaver.locations.LocationId
import com.iamxpp.isaver.locations.LocationResolver
import com.iamxpp.isaver.locations.StorageLocation
import com.iamxpp.isaver.recent.RecentRepository
import com.iamxpp.isaver.recent.RecentItemType
import com.iamxpp.isaver.share.ShareIntentParser
import com.iamxpp.isaver.transfer.IncomingFileCache
import com.iamxpp.isaver.transfer.IncomingStreamRegistry
import com.iamxpp.isaver.transfer.RootFileTransferRepository
import com.iamxpp.isaver.transfer.TargetNameResolver
import com.iamxpp.isaver.transfer.TransferDependencies
import com.iamxpp.isaver.archive.ArchiveRepository
import com.iamxpp.isaver.archive.DirectoryShareRepository
import com.iamxpp.isaver.archive.LocalArchiveEngine
import com.iamxpp.isaver.export.ExternalFileRegistry
import com.iamxpp.isaver.export.MimeResolver
import com.iamxpp.isaver.export.RootExportCache
import com.iamxpp.isaver.export.RootExportRepository
import com.iamxpp.isaver.fileops.FileMoveRepository
import com.iamxpp.isaver.fileops.FileCopyRepository
import com.iamxpp.isaver.fileops.FileRenameRepository
import com.iamxpp.isaver.fileops.FileChecksumRepository
import com.iamxpp.isaver.tasks.OperationTaskRepository
import com.iamxpp.isaver.trash.TrashRepository
import com.iamxpp.isaver.bookmarks.BookmarkRepository
import com.iamxpp.isaver.remote.KeystoreCredentialStore
import com.iamxpp.isaver.remote.RemoteFileSystemFactory
import com.iamxpp.isaver.ui.LocationHomeAppResolver
import com.iamxpp.isaver.ui.LocationHomeCustomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ISaverApplication : Application() {
    private val applicationScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val rootSession: RootSession by lazy { LibsuRootSession() }
    internal val rootFileSystem: RootFileSystem by lazy { LibsuRootFileSystem("${applicationInfo.nativeLibraryDir}/libisaver_fs_helper.so") }
    internal val database: ISaverDatabase by lazy {
        Room.databaseBuilder(this, ISaverDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                ISaverDatabase.MIGRATION_1_2,
                ISaverDatabase.MIGRATION_2_3,
                ISaverDatabase.MIGRATION_3_4,
                ISaverDatabase.MIGRATION_4_5,
                ISaverDatabase.MIGRATION_5_6,
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
        CustomLocationStoreAdapter(customLocationRepository)
    }
    internal val browserPreferencesStore: BrowserPreferencesStore by lazy {
        BrowserPreferencesRepository(
            PreferenceDataStoreFactory.create(
                scope = applicationScope,
                produceFile = { preferencesDataStoreFile("browser.preferences_pb") },
            ),
        )
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
    internal val bookmarkRepository: BookmarkRepository by lazy { BookmarkRepository(database.bookmarkDao()) }
    internal val operationTaskRepository: OperationTaskRepository by lazy {
        OperationTaskRepository(database.operationTaskDao())
    }
    internal val remoteCredentialStore by lazy { KeystoreCredentialStore(this) }
    internal val remoteFileSystemFactory by lazy { RemoteFileSystemFactory(remoteCredentialStore) }
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
