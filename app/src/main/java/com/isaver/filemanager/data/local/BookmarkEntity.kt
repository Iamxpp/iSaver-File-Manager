package com.isaver.filemanager.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val absolutePath: String,
    val displayName: String,
    val createdAt: Long,
    val entryType: String = "DIRECTORY",
    val device: Long? = null,
    val inode: Long? = null,
    val available: Boolean = true,
    val colorKey: String? = null,
    val groupName: String? = null,
)
