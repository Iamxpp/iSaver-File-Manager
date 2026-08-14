package com.isaver.filemanager.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "virtual_view_nodes",
    indices = [
        Index(value = ["parentId", "sortOrder"]),
        Index(value = ["parentId", "targetPath", "entryType"], unique = true),
    ],
)
data class VirtualViewNodeEntity(
    @PrimaryKey val id: String,
    val parentId: String?,
    val nodeType: String,
    val displayName: String,
    val targetPath: String?,
    val entryType: String?,
    val device: Long?,
    val inode: Long?,
    val available: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
