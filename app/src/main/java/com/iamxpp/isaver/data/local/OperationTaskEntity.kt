package com.iamxpp.isaver.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "operation_tasks",
    indices = [Index(value = ["updatedAt"])],
)
data class OperationTaskEntity(
    @PrimaryKey val id: String,
    val type: String,
    val state: String,
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val totalBytes: Long?,
    val completedBytes: Long,
    val recoveryPolicy: String,
    val message: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
