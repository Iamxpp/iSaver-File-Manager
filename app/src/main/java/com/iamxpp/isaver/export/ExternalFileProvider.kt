package com.iamxpp.isaver.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.iamxpp.isaver.ISaverApplication
import java.io.FileNotFoundException

class ExternalFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != READ_MODE) deny()
        val application = application()
        val token = token(uri, application.packageName) ?: deny()
        val cached = application.externalFileRegistry.consume(token) ?: deny()
        return try {
            ParcelFileDescriptor.open(cached.file, ParcelFileDescriptor.MODE_READ_ONLY).also {
                application.rootExportCache.discardNow(cached)
            }
        } catch (_: Exception) {
            application.rootExportCache.discardNow(cached)
            deny()
        }
    }

    override fun getType(uri: Uri): String? {
        val application = applicationOrNull() ?: return null
        val token = token(uri, application.packageName) ?: return null
        return application.externalFileRegistry.peek(token)?.mimeType
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val application = application()
        val token = token(uri, application.packageName) ?: deny()
        val cached = application.externalFileRegistry.peek(token) ?: deny()
        val columns = projection?.map(String::toString)?.toTypedArray() ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> cached.displayName
                OpenableColumns.SIZE -> cached.sizeBytes
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun application(): ISaverApplication =
        context?.applicationContext as? ISaverApplication ?: deny()

    private fun applicationOrNull(): ISaverApplication? =
        context?.applicationContext as? ISaverApplication

    private fun token(uri: Uri, packageName: String): String? {
        if (uri.scheme != CONTENT_SCHEME || uri.authority != "$packageName.$AUTHORITY_SUFFIX") return null
        val segments = uri.pathSegments
        return segments.takeIf {
            it.size == 2 && it[0] == ExternalFileRegistry.FILE_SEGMENT
        }?.get(1)
    }

    private fun deny(): Nothing = throw FileNotFoundException(DENIAL_MESSAGE)

    private companion object {
        const val CONTENT_SCHEME = "content"
        const val AUTHORITY_SUFFIX = "external-file"
        const val READ_MODE = "r"
        const val DENIAL_MESSAGE = "External file unavailable"
        val DEFAULT_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
