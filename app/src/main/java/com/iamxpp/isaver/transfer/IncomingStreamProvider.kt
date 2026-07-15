package com.iamxpp.isaver.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import com.iamxpp.isaver.ISaverApplication
import java.io.FileNotFoundException

class IncomingStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val caller = Binder.getCallingUid()
        if (caller != ROOT_UID && caller != SHELL_UID) deny()
        val application = context?.applicationContext as? ISaverApplication ?: deny()
        if (mode != READ_MODE || uri.authority != "${application.packageName}.incoming-stream") deny()
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != INCOMING_SEGMENT) deny()
        val cached = application.incomingStreamRegistry.consume(segments[1]) ?: deny()
        return try {
            ParcelFileDescriptor.open(cached.file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            deny()
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

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

    private fun deny(): Nothing = throw FileNotFoundException(DENIAL_MESSAGE)

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val READ_MODE = "r"
        const val INCOMING_SEGMENT = "incoming"
        const val DENIAL_MESSAGE = "Stream unavailable"
    }
}
