package com.iamxpp.isaver.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

class DebugShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireDocument(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "测试 报告.pdf"
                        OpenableColumns.SIZE -> CONTENT.size.toLong()
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireDocument(uri)
        if (mode != "r") throw FileNotFoundException("Read only")
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write(CONTENT)
            }
        }.start()
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun requireDocument(uri: Uri) {
        if (uri.authority != AUTHORITY || uri.lastPathSegment != "report.pdf") {
            throw FileNotFoundException("Unknown debug document")
        }
    }

    private companion object {
        const val AUTHORITY = "com.iamxpp.isaver.debug-share"
        val CONTENT = "%PDF-1.4\n%iSaver debug fixture\n%%EOF\n".toByteArray()
    }
}
