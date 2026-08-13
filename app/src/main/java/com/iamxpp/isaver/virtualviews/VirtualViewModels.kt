package com.iamxpp.isaver.virtualviews

import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath

enum class VirtualViewNodeType { VIRTUAL_FOLDER, REAL_REFERENCE }

sealed interface VirtualViewNode {
    val id: String
    val parentId: String?
    val displayName: String
    val sortOrder: Int
    val createdAt: Long
    val updatedAt: Long

    data class VirtualFolder(
        override val id: String,
        override val parentId: String?,
        override val displayName: String,
        override val sortOrder: Int,
        override val createdAt: Long,
        override val updatedAt: Long,
    ) : VirtualViewNode

    data class RealReference(
        override val id: String,
        override val parentId: String,
        override val displayName: String,
        val targetPath: RootPath,
        val entryType: EntryType,
        val identity: RootEntryIdentity?,
        val available: Boolean,
        override val sortOrder: Int,
        override val createdAt: Long,
        override val updatedAt: Long,
    ) : VirtualViewNode
}

sealed interface VirtualViewResult {
    data class Success(val nodeId: String) : VirtualViewResult
    data object InvalidName : VirtualViewResult
    data object InvalidParent : VirtualViewResult
    data object NotFound : VirtualViewResult
    data object DuplicateReference : VirtualViewResult
    data object Cycle : VirtualViewResult
    data object ConfirmationRequired : VirtualViewResult
    data object InvalidNode : VirtualViewResult
}
