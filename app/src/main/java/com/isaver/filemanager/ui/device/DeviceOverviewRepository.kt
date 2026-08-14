package com.isaver.filemanager.ui.device

import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceStorageUsage(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    init {
        require(totalBytes >= 0)
        require(availableBytes in 0..totalBytes)
    }

    val usedBytes: Long = totalBytes - availableBytes
    val usedFraction: Float = if (totalBytes == 0L) 0f else (usedBytes.toDouble() / totalBytes).toFloat()
}

class DeviceOverviewRepository(
    private val readStats: () -> DeviceStorageUsage = ::readInternalStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(): Result<DeviceStorageUsage> = withContext(ioDispatcher) {
        runCatching {
            readStats().also { usage ->
                require(usage.usedFraction in 0f..1f)
            }
        }
    }

    private companion object {
        fun readInternalStorage(): DeviceStorageUsage {
            val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            return DeviceStorageUsage(
                totalBytes = stats.totalBytes.coerceAtLeast(0),
                availableBytes = stats.availableBytes.coerceIn(0, stats.totalBytes.coerceAtLeast(0)),
            )
        }
    }
}
