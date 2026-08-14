package com.isaver.filemanager.transfer

import android.content.Intent
import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.share.IncomingShare
import com.isaver.filemanager.share.ShareIntentParseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

class TransferDependencies internal constructor(
    val parseShare: suspend (Intent) -> ShareIntentParseResult,
    val validateTarget: suspend (RootPath) -> OperationResult<RootPath>,
    val cacheIncoming: suspend (IncomingShare, (Long) -> Unit) -> IncomingFileCacheResult,
    val validateCache: suspend (CachedIncomingFile) -> Boolean,
    val cleanupIncoming: suspend (CachedIncomingFile) -> Boolean,
    val transferCached: (
        CachedIncomingFile,
        OutputNameDraft,
        RootPath,
        () -> Boolean,
    ) -> Flow<TransferState>,
    val recordSaved: suspend (DirectoryEntry) -> Unit,
    val workDispatcher: CoroutineDispatcher,
)
