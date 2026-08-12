package com.iamxpp.isaver.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trash_items", indices = [Index(value = ["deletedAt"])])
data class TrashItemEntity(
    @PrimaryKey val id: String,
    val originalPath: String,
    val originalParent: String,
    val originalName: String,
    val trashedPath: String,
    val trashedName: String,
    val entryType: String,
    val sizeBytes: Long?,
    val device: Long?,
    val inode: Long?,
    val state: String,
    val deletedAt: Long,
)
