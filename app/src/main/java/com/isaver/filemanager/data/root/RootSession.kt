package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.RootStatus

/**
 * Owns the single application-level libsu shell lifecycle.
 *
 * Future [RootFileSystem] implementations must share this session and must not close the cached
 * shell themselves.
 */
interface RootSession {
    suspend fun check(): RootStatus

    suspend fun invalidate()
}
