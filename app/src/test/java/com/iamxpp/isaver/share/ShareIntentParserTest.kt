package com.iamxpp.isaver.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.BadParcelableException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ShareIntentParserTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val uri = Uri.parse("content://share.test/document/42")

    @Before
    fun setUp() {
        registerProvider()
    }

    @Test
    fun `parses one granted content stream and its display metadata`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val result = ShareIntentParser(context).parse(intent)

        assertTrue(result is ShareIntentParseResult.Success)
        val share = (result as ShareIntentParseResult.Success).share
        assertEquals(uri, share.uri)
        assertEquals("报告.pdf", share.displayName)
        assertEquals(4096L, share.sizeBytes)
        assertEquals("application/pdf", share.mimeType)
    }

    @Test
    @Config(sdk = [29])
    fun `parses a valid Uri on Android 10`() {
        assertTrue(ShareIntentParser(context).parse(sendIntent()) is ShareIntentParseResult.Success)
    }

    @Test
    @Config(sdk = [33])
    fun `parses a valid Uri with typed Parcelable API on Android 13`() {
        assertTrue(ShareIntentParser(context).parse(sendIntent()) is ShareIntentParseResult.Success)
    }

    @Test
    @Config(sdk = [35])
    fun `parses a valid Uri with typed Parcelable API on Android 15`() {
        assertTrue(ShareIntentParser(context).parse(sendIntent()) is ShareIntentParseResult.Success)
    }

    @Test
    fun `rejects an intent without a stream`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )

        assertFailure(result, ShareIntentFailureReason.MISSING_STREAM, "未接收到文件")
    }

    @Test
    @Config(sdk = [29, 33, 35])
    fun `parses ACTION_VIEW content data across supported Android versions`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
            },
        )

        assertTrue(result is ShareIntentParseResult.Success)
        assertEquals(uri, (result as ShareIntentParseResult.Success).share.uri)
    }

    @Test
    @Config(sdk = [29, 33, 35])
    fun `parses one content Uri from ACTION_SEND ClipData`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                clipData = ClipData.newRawUri("shared file", uri)
            },
        )

        assertTrue(result is ShareIntentParseResult.Success)
        assertEquals(uri, (result as ShareIntentParseResult.Success).share.uri)
    }

    @Test
    fun `rejects ACTION_SEND ClipData containing more than one item`() {
        val clips = ClipData.newRawUri("first file", uri).apply {
            addItem(ClipData.Item(Uri.parse("content://share.test/document/43")))
        }
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                clipData = clips
            },
        )

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    fun `rejects conflicting ACTION_SEND extra and ClipData Uris`() {
        val result = ShareIntentParser(context).parse(
            sendIntent().apply {
                clipData = ClipData.newRawUri(
                    "different file",
                    Uri.parse("content://share.test/document/43"),
                )
            },
        )

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    fun `accepts matching ACTION_SEND extra and ClipData Uris`() {
        val result = ShareIntentParser(context).parse(
            sendIntent().apply {
                clipData = ClipData.newRawUri("same file", uri)
            },
        )

        assertTrue(result is ShareIntentParseResult.Success)
        assertEquals(uri, (result as ShareIntentParseResult.Success).share.uri)
    }

    @Test
    fun `rejects launcher ACTION_MAIN even when it contains a content Uri`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_MAIN).apply {
                data = uri
                putExtra(Intent.EXTRA_STREAM, uri)
            },
        )

        assertFailure(result, ShareIntentFailureReason.UNSUPPORTED_INTENT, "仅支持分享单个文件")
    }

    @Test
    fun `rejects ACTION_SEND_MULTIPLE`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )

        assertFailure(result, ShareIntentFailureReason.UNSUPPORTED_INTENT, "仅支持分享单个文件")
    }

    @Test
    @Config(sdk = [29])
    fun `returns invalid share for String stream extra on Android 10`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, "content://share.test/document/42")
            },
        )

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    @Config(sdk = [35])
    fun `returns invalid share for list stream extra on Android 15`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                putStringArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri.toString()))
            },
        )

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    @Config(sdk = [29])
    fun `returns invalid share when lazy Bundle unparcel throws`() {
        val result = ShareIntentParser(context).parse(ThrowingExtrasIntent())

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    @Config(sdk = [35])
    fun `returns invalid share when typed Parcelable getter throws`() {
        val result = ShareIntentParser(context).parse(ThrowingTypedExtraIntent())

        assertFailure(result, ShareIntentFailureReason.INVALID_SHARE, "分享文件信息无效")
    }

    @Test
    fun `rejects file Uri without converting it to a path`() {
        val fileUri = Uri.parse("file:///storage/emulated/0/report.pdf")
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )

        assertFailure(result, ShareIntentFailureReason.UNSUPPORTED_URI, "仅支持安全的内容 Uri")
    }

    @Test
    fun `rejects file and http Uris from both supported actions`() {
        listOf(
            Uri.parse("file:///storage/emulated/0/report.pdf"),
            Uri.parse("https://example.test/report.pdf"),
        ).forEach { unsupportedUri ->
            val sendResult = ShareIntentParser(context).parse(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, unsupportedUri)
                },
            )
            val viewResult = ShareIntentParser(context).parse(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(unsupportedUri, "application/pdf")
                },
            )

            assertFailure(sendResult, ShareIntentFailureReason.UNSUPPORTED_URI, "仅支持安全的内容 Uri")
            assertFailure(viewResult, ShareIntentFailureReason.UNSUPPORTED_URI, "仅支持安全的内容 Uri")
        }
    }

    @Test
    fun `parses a stream without a flag when provider access succeeds`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
            },
        )

        assertTrue(result is ShareIntentParseResult.Success)
    }

    @Test
    fun `forwards caller cancellation signal to the bounded metadata query`() {
        val provider = registerProvider()
        val cancellationSignal = CancellationSignal()

        val result = ShareIntentParser(context).parse(sendIntent(), cancellationSignal)

        assertTrue(result is ShareIntentParseResult.Success)
        assertSame(cancellationSignal, provider.lastCancellationSignal)
    }

    @Test
    fun `uses unnamed file plus extension inferred from MIME when display name is missing`() {
        registerProvider(displayName = null, size = null, mimeType = "image/png")
        val intent = sendIntent(type = "image/png")
        val result = ShareIntentParser(context).parse(intent)

        assertTrue(result is ShareIntentParseResult.Success)
        val share = (result as ShareIntentParseResult.Success).share
        assertEquals("未命名文件.png", share.displayName)
        assertEquals(null, share.sizeBytes)
        assertEquals("image/png", share.mimeType)
    }

    @Test
    fun `treats blank display name and negative size as missing metadata`() {
        registerProvider(displayName = "  ", size = -1L)

        val result = ShareIntentParser(context).parse(sendIntent())

        assertTrue(result is ShareIntentParseResult.Success)
        val share = (result as ShareIntentParseResult.Success).share
        assertEquals("未命名文件.pdf", share.displayName)
        assertEquals(null, share.sizeBytes)
    }

    @Test
    fun `falls back to intent MIME when provider type is missing`() {
        registerProvider(mimeType = null)

        val result = ShareIntentParser(context).parse(sendIntent(type = "application/pdf"))

        assertTrue(result is ShareIntentParseResult.Success)
        assertEquals("application/pdf", (result as ShareIntentParseResult.Success).share.mimeType)
    }

    @Test
    fun `returns typed unreadable failure when metadata query is denied`() {
        registerProvider(denyQuery = true)

        val result = ShareIntentParser(context).parse(sendIntent())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
    }

    @Test
    fun `falls back when metadata columns are missing`() {
        registerProvider(omitMetadataColumns = true, mimeType = "application/pdf")

        val result = ShareIntentParser(context).parse(sendIntent())

        assertTrue(result is ShareIntentParseResult.Success)
        val share = (result as ShareIntentParseResult.Success).share
        assertEquals("未命名文件.pdf", share.displayName)
        assertEquals(null, share.sizeBytes)
    }

    @Test
    fun `returns unreadable failure for provider runtime metadata errors`() {
        listOf(
            IllegalArgumentException("bad projection"),
            SQLiteException("database unavailable"),
            CursorIndexOutOfBoundsException(4, 2),
            RuntimeException("provider failure"),
        ).forEach { exception ->
            registerProvider(queryFailure = exception)

            val result = ShareIntentParser(context).parse(sendIntent())

            assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        }
    }

    @Test
    fun `returns unreadable failure when the metadata provider dies`() {
        registerProvider(queryFailure = RemoteException("provider died"))

        val result = ShareIntentParser(context).parse(sendIntent())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
    }

    @Test
    fun `maps an unowned provider cancellation to an unreadable failure`() {
        registerProvider(queryFailure = CancellationException("cancelled"))

        val result = ShareIntentParser(context).parse(sendIntent())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
    }

    @Test
    fun `maps an unowned Android provider cancellation to an unreadable failure`() {
        registerProvider(queryFailure = OperationCanceledException("provider cancelled itself"))

        val result = ShareIntentParser(context).parse(sendIntent(), CancellationSignal())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
    }

    @Test
    fun `propagates Android metadata query cancellation`() {
        val cancellationSignal = CancellationSignal().apply { cancel() }

        assertThrows(OperationCanceledException::class.java) {
            ShareIntentParser(context).parse(sendIntent(), cancellationSignal)
        }
    }

    @Test
    fun `bounded async parse times out at two seconds and ignores late provider work`() = runTest {
        lateinit var cancellationSignal: CancellationSignal
        var lateProviderWorkCompleted = false
        val parser = ShareIntentParser(
            context = context,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            parseOperation = { _, signal ->
                cancellationSignal = signal
                withContext(NonCancellable) {
                    delay(2_500L)
                    lateProviderWorkCompleted = true
                }
                ShareIntentParseResult.Success(
                    IncomingShare(uri, "late.pdf", 1L, "application/pdf"),
                )
            },
        )

        val result = parser.parseAsync(sendIntent())

        assertFailure(result, ShareIntentFailureReason.PROVIDER_TIMEOUT, "读取来源文件超时")
        assertEquals(2_000L, testScheduler.currentTime)
        assertTrue(cancellationSignal.isCanceled)
        assertFalse(lateProviderWorkCompleted)

        advanceUntilIdle()

        assertTrue(lateProviderWorkCompleted)
        assertFailure(result, ShareIntentFailureReason.PROVIDER_TIMEOUT, "读取来源文件超时")
    }

    @Test
    fun `bounded async parse propagates caller cancellation and cancels provider query`() = runTest {
        lateinit var cancellationSignal: CancellationSignal
        val parser = ShareIntentParser(
            context = context,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            parseOperation = { _, signal ->
                cancellationSignal = signal
                awaitCancellation()
            },
        )
        val callerCancellation = CancellationException("caller cancelled")
        val result = backgroundScope.async {
            parser.parseAsync(sendIntent())
        }
        testScheduler.runCurrent()

        result.cancel(callerCancellation)
        testScheduler.runCurrent()

        val thrown = try {
            result.await()
            throw AssertionError("expected caller cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertEquals(callerCancellation.message, thrown.message)
        assertTrue(cancellationSignal.isCanceled)
    }

    @Test
    fun `bounded async parse does not swallow a caller timeout cancellation`() = runTest {
        lateinit var cancellationSignal: CancellationSignal
        val parser = ShareIntentParser(
            context = context,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            parseOperation = { _, signal ->
                cancellationSignal = signal
                awaitCancellation()
            },
        )

        val thrown = try {
            withTimeout(100L) {
                parser.parseAsync(sendIntent())
            }
            null
        } catch (timeout: TimeoutCancellationException) {
            timeout
        }
        assertTrue(thrown is TimeoutCancellationException)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(cancellationSignal.isCanceled)
    }

    @Test
    fun `bounded async parse does not convert a worker timeout cancellation to provider timeout`() = runTest {
        lateinit var cancellationSignal: CancellationSignal
        val parser = ShareIntentParser(
            context = context,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            parseOperation = { _, signal ->
                cancellationSignal = signal
                withTimeout(100L) {
                    awaitCancellation()
                }
            },
        )

        val thrown = try {
            parser.parseAsync(sendIntent())
            null
        } catch (timeout: TimeoutCancellationException) {
            timeout
        }

        assertTrue(thrown is TimeoutCancellationException)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(cancellationSignal.isCanceled)
    }

    @Test
    fun `bounded worker runs at most one non cooperative provider operation`() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        try {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val parser = ShareIntentParser(
                context = context,
                workerDispatcher = dispatcher,
                providerTimeoutMillis = 5_000L,
                parseOperation = { intent, _ ->
                    val currentActive = active.incrementAndGet()
                    maximumActive.updateAndGet { previous -> maxOf(previous, currentActive) }
                    try {
                        if (intent.getStringExtra("request") == "first") {
                            firstStarted.complete(Unit)
                            withContext(NonCancellable) { releaseFirst.await() }
                        } else {
                            secondStarted.complete(Unit)
                        }
                        ShareIntentParseResult.Success(
                            IncomingShare(uri, "报告.pdf", 1L, "application/pdf"),
                        )
                    } finally {
                        active.decrementAndGet()
                    }
                },
            )
            val first = async {
                parser.parseAsync(sendIntent().putExtra("request", "first"))
            }
            firstStarted.await()
            val second = async {
                parser.parseAsync(sendIntent().putExtra("request", "second"))
            }

            withContext(Dispatchers.Default) { delay(100L) }
            assertFalse(
                "secondStarted=${secondStarted.isCompleted}, active=${active.get()}, max=${maximumActive.get()}, " +
                    "firstCompleted=${first.isCompleted}, secondCompleted=${second.isCompleted}",
                secondStarted.isCompleted,
            )
            assertEquals(1, maximumActive.get())

            releaseFirst.complete(Unit)
            assertTrue(first.await() is ShareIntentParseResult.Success)
            assertTrue(second.await() is ShareIntentParseResult.Success)
            assertEquals(1, maximumActive.get())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `parser resolves providers from the application context`() {
        val activityLikeContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = context

            override fun getContentResolver(): ContentResolver =
                error("Activity-scoped resolver must not be retained")
        }

        val result = ShareIntentParser(activityLikeContext).parse(sendIntent())

        assertTrue(result is ShareIntentParseResult.Success)
    }

    @Test
    fun `closes cursor and returns unreadable failure when size has wrong type`() {
        val provider = registerProvider(sizeValue = "not-a-number")

        val result = ShareIntentParser(context).parse(sendIntent())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        assertTrue(provider.lastCursor?.wasClosed == true)
    }

    @Test
    fun `closes cursor and returns unreadable failure when name has wrong type`() {
        val provider = registerProvider(throwOnDisplayName = true)

        val result = ShareIntentParser(context).parse(sendIntent())

        assertFailure(result, ShareIntentFailureReason.SOURCE_UNREADABLE, "无法读取来源文件")
        assertTrue(provider.lastCursor?.wasClosed == true)
    }

    @Test
    fun `incoming share string representation redacts provider controlled values`() {
        val share = IncomingShare(uri, "报告`nsecret.pdf", 4096L, "application/private-token")

        assertFalse(share.toString().contains(uri.toString()))
        assertFalse(share.toString().contains("报告"))
        assertFalse(share.toString().contains("private-token"))
        assertFalse(share.toString().contains("`n"))
    }

    private fun sendIntent(type: String = "application/pdf") = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun assertFailure(
        result: ShareIntentParseResult,
        reason: ShareIntentFailureReason,
        message: String,
    ) {
        assertTrue(result is ShareIntentParseResult.Failure)
        result as ShareIntentParseResult.Failure
        assertEquals(reason, result.reason)
        assertEquals(message, result.userMessage)
    }

    private fun registerProvider(
        displayName: String? = "报告.pdf",
        size: Long? = 4096L,
        mimeType: String? = "application/pdf",
        denyQuery: Boolean = false,
        omitMetadataColumns: Boolean = false,
        queryFailure: Throwable? = null,
        sizeValue: Any? = size,
        throwOnDisplayName: Boolean = false,
    ): MetadataProvider {
        val provider = MetadataProvider(
            displayName = displayName,
            sizeValue = sizeValue,
            mimeType = mimeType,
            queryFailure = queryFailure ?: if (denyQuery) SecurityException("permission denied") else null,
            omitMetadataColumns = omitMetadataColumns,
            throwOnDisplayName = throwOnDisplayName,
        )
        ShadowContentResolver.registerProviderInternal(
            "share.test",
            provider,
        )
        return provider
    }

    private class MetadataProvider(
        private val displayName: String?,
        private val sizeValue: Any?,
        private val mimeType: String?,
        private val queryFailure: Throwable?,
        private val omitMetadataColumns: Boolean,
        private val throwOnDisplayName: Boolean,
    ) : ContentProvider() {
        var lastCursor: TrackingMatrixCursor? = null
            private set
        var lastCancellationSignal: CancellationSignal? = null
            private set

        override fun onCreate() = true

        override fun getType(uri: Uri): String? = mimeType

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = metadataCursor(projection)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            lastCancellationSignal = cancellationSignal
            cancellationSignal?.throwIfCanceled()
            return metadataCursor(projection)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            lastCancellationSignal = cancellationSignal
            return metadataCursor(projection)
        }

        private fun metadataCursor(projection: Array<out String>?): Cursor {
            queryFailure?.let { throw it }
            val columns = if (omitMetadataColumns) {
                arrayOf("ignored")
            } else {
                projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            }
            return TrackingMatrixCursor(
                columns = columns,
                throwingStringColumn = if (throwOnDisplayName) {
                    columns.indexOf(OpenableColumns.DISPLAY_NAME)
                } else {
                    -1
                },
            ).apply {
                addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> displayName
                        OpenableColumns.SIZE -> sizeValue
                        else -> null
                    }
                })
                lastCursor = this
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
    }

    private class TrackingMatrixCursor(
        columns: Array<out String>,
        private val throwingStringColumn: Int,
    ) : MatrixCursor(columns) {
        var wasClosed = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }

        override fun getString(column: Int): String? {
            if (column == throwingStringColumn) throw ClassCastException("wrong display name type")
            return super.getString(column)
        }
    }

    private class ThrowingExtrasIntent : Intent(ACTION_SEND) {
        override fun hasExtra(name: String?): Boolean = name == EXTRA_STREAM

        override fun getExtras(): Bundle {
            throw BadParcelableException("malformed parcel")
        }
    }

    private class ThrowingTypedExtraIntent : Intent(ACTION_SEND) {
        override fun hasExtra(name: String?): Boolean = name == EXTRA_STREAM

        override fun <T : Any?> getParcelableExtra(name: String?, clazz: Class<T>): T? {
            throw BadParcelableException("malformed typed parcel")
        }
    }
}
