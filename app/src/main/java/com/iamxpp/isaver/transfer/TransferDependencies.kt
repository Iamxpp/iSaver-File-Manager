package com.iamxpp.isaver.transfer

import android.content.Intent
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.share.IncomingShare
import com.iamxpp.isaver.share.ShareIntentParseResult
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
