package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.RootStatus

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
