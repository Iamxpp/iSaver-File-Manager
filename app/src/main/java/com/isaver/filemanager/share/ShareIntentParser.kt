package com.isaver.filemanager.share

import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.RemoteException
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

class ShareIntentParser private constructor(
    context: Context,
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val parseOperation: (suspend (Intent, CancellationSignal) -> ShareIntentParseResult)? = null,
    private val workerScope: CoroutineScope = APPLICATION_WORKER_SCOPE,
) {
    private val contentResolver = (context.applicationContext ?: context).contentResolver
    private val workerPermit = Semaphore(1)

    constructor(context: Context) : this(
        context = context,
        workerScope = APPLICATION_WORKER_SCOPE,
    )

    internal constructor(
        context: Context,
        workerDispatcher: CoroutineDispatcher,
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
        parseOperation: (suspend (Intent, CancellationSignal) -> ShareIntentParseResult)? = null,
    ) : this(
        context = context,
        providerTimeoutMillis = providerTimeoutMillis,
        parseOperation = parseOperation,
        workerScope = CoroutineScope(SupervisorJob() + workerDispatcher.limitedParallelism(1)),
    )

    init {
        require(providerTimeoutMillis > 0L)
    }

    suspend fun parseAsync(intent: Intent): ShareIntentParseResult {
        val cancellationSignal = CancellationSignal()
        val completion = CompletableDeferred<ShareIntentParseResult>()
        val worker = workerScope.launch {
            try {
                workerPermit.withPermit {
                    completion.complete(
                        parseOperation?.invoke(intent, cancellationSignal)
                            ?: parse(intent, cancellationSignal),
                    )
                }
            } catch (cancelled: CancellationException) {
                completion.completeExceptionally(cancelled)
            } catch (throwable: Throwable) {
                completion.completeExceptionally(throwable)
            }
        }

        val result = try {
            withTimeoutOrNull(providerTimeoutMillis) {
                completion.await()
            }
        } catch (cancelled: CancellationException) {
            cancellationSignal.cancel()
            completion.cancel(cancelled)
            worker.cancel(cancelled)
            throw cancelled
        }
        if (result != null) return result

        cancellationSignal.cancel()
        completion.cancel(CancellationException("metadata provider timed out"))
        worker.cancel()
        return failure(ShareIntentFailureReason.PROVIDER_TIMEOUT, "读取来源文件超时")
    }

    internal fun parse(
        intent: Intent,
        cancellationSignal: CancellationSignal? = null,
    ): ShareIntentParseResult {
        val stream = when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> ShareIntentStream.extract(intent)
            else -> return failure(
                ShareIntentFailureReason.UNSUPPORTED_INTENT,
                "仅支持分享单个文件",
            )
        }
        val uri = when (stream) {
            ShareIntentStreamExtra.Missing ->
                return failure(ShareIntentFailureReason.MISSING_STREAM, "未接收到文件")
            ShareIntentStreamExtra.Invalid ->
                return failure(ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
            is ShareIntentStreamExtra.Valid -> stream.uri
        }

        if (uri.scheme != "content") {
            return failure(
                ShareIntentFailureReason.UNSUPPORTED_URI,
                "仅支持安全的内容 Uri",
            )
        }
        return try {
            cancellationSignal?.throwIfCanceled()
            val mimeType = contentResolver.getType(uri) ?: intent.type
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            val metadata = contentResolver.query(
                uri,
                projection,
                null,
                null,
                null,
                cancellationSignal,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0L }
                name to size
            }

            ShareIntentParseResult.Success(
                IncomingShare(
                    uri = uri,
                    displayName = metadata?.first?.takeIf(String::isNotBlank)
                        ?: unnamedFile(mimeType),
                    sizeBytes = metadata?.second,
                    mimeType = mimeType,
                ),
            )
        } catch (cancelled: CancellationException) {
            if (cancellationSignal?.isCanceled == true) throw cancelled
            failure(ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        } catch (cancelled: OperationCanceledException) {
            if (cancellationSignal?.isCanceled == true) throw cancelled
            failure(ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        } catch (_: RemoteException) {
            failure(ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        } catch (_: RuntimeException) {
            failure(ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        }
    }

    private fun unnamedFile(mimeType: String?): String {
        val mappedExtension = mimeType
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.takeIf(String::isNotBlank)
        val safeSubtype = mimeType
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.takeIf { it.matches(SAFE_MIME_SUBTYPE) }
        val extension = mappedExtension ?: safeSubtype
        return if (extension == null) "未命名文件" else "未命名文件.$extension"
    }

    private fun failure(
        reason: ShareIntentFailureReason,
        userMessage: String,
    ) = ShareIntentParseResult.Failure(
        reason = reason,
        userMessage = userMessage,
    )

    private companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 2_000L
        val SAFE_MIME_SUBTYPE = Regex("[A-Za-z0-9]{1,10}")
        val APPLICATION_WORKER_SCOPE = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
        )
    }

}
