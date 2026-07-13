package com.iamxpp.isaver.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_items",
    indices = [Index(value = ["lastActivityAt"])],
)
data class RecentItemEntity(
    @PrimaryKey val absolutePath: String,
    val displayName: String,
    val note: String?,
    val itemType: String,
    val activity: String,
    val lastActivityAt: Long,
    val available: Boolean,
)
