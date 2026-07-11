package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.RootStatus

interface RootSession {
    suspend fun check(): RootStatus

    fun invalidate()
}
