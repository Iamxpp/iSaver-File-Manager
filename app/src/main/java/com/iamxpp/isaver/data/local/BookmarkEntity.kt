package com.iamxpp.isaver.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val absolutePath: String,
    val displayName: String,
    val createdAt: Long,
)
