package com.iamxpp.isaver.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "custom_locations", indices = [Index(value = ["absolutePath"], unique = true)])
data class CustomLocationEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val absolutePath: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
