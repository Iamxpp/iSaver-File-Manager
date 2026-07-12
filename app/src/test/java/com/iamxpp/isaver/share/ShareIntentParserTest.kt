package com.iamxpp.isaver.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.CursorIndexOutOfBoundsException
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.BadParcelableException
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    fun `rejects an action other than ACTION_SEND`() {
        val result = ShareIntentParser(context).parse(
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    fun `incoming share string representation redacts the source Uri`() {
        val share = IncomingShare(uri, "报告.pdf", 4096L, "application/pdf")

        assertFalse(share.toString().contains(uri.toString()))
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
        queryFailure: RuntimeException? = null,
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
        private val queryFailure: RuntimeException?,
        private val omitMetadataColumns: Boolean,
        private val throwOnDisplayName: Boolean,
    ) : ContentProvider() {
        var lastCursor: TrackingMatrixCursor? = null
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
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor = metadataCursor(projection)

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
}
