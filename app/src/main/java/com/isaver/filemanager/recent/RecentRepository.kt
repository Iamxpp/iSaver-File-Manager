package com.isaver.filemanager.recent

import com.isaver.filemanager.data.local.RecentItemDao
import com.isaver.filemanager.data.local.RecentItemEntity
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class RecentItemType { DIRECTORY, FILE, ARCHIVE }

enum class RecentActivity { ACCESSED, SAVED, COMPRESSED, EXTRACTED }

data class RecentItem(
    val path: RootPath,
    val displayName: String,
    val note: String?,
    val type: RecentItemType,
    val activity: RecentActivity,
    val lastActivityAt: Long,
    val available: Boolean,
)

class RecentRepository(
    private val dao: RecentItemDao,
    private val clock: () -> Long,
) {
    fun observeRecent(): Flow<List<RecentItem>> = dao.observeRecent()
        .distinctUntilChanged(::sameRecentContent)
        .map { rows ->
            rows.mapNotNull { entity -> runCatching { toRecentItem(entity) }.getOrNull() }
        }

    /** Records only a confirmed successful access to the canonical local Root path. */
    suspend fun recordAccess(
        canonicalPath: RootPath,
        displayName: String,
        note: String?,
        type: RecentItemType,
    ) {
        record(
            canonicalPath,
            displayName,
            note,
            type,
            RecentActivity.ACCESSED,
            preserveMeaningfulActivity = true,
        )
    }

    /** Records only a confirmed successful save/transfer to the canonical local Root path. */
    suspend fun recordSaved(
        canonicalPath: RootPath,
        displayName: String,
        note: String?,
        type: RecentItemType,
    ) {
        record(canonicalPath, displayName, note, type, RecentActivity.SAVED)
    }

    suspend fun recordCompressed(canonicalPath: RootPath, displayName: String) {
        record(canonicalPath, displayName, null, RecentItemType.ARCHIVE, RecentActivity.COMPRESSED)
    }

    suspend fun recordExtracted(canonicalPath: RootPath, displayName: String) {
        record(canonicalPath, displayName, null, RecentItemType.DIRECTORY, RecentActivity.EXTRACTED)
    }

    suspend fun markAvailability(canonicalPath: RootPath, available: Boolean): Boolean =
        dao.markAvailability(canonicalPath.value, available) == 1

    private suspend fun record(
        canonicalPath: RootPath,
        displayName: String,
        note: String?,
        type: RecentItemType,
        activity: RecentActivity,
        preserveMeaningfulActivity: Boolean = false,
    ) {
        val entity = RecentItemEntity(
            absolutePath = canonicalPath.value,
            displayName = displayName.trim(),
            note = note?.trim()?.takeIf(String::isNotEmpty),
            itemType = type.name,
            activity = activity.name,
            lastActivityAt = clock(),
            available = true,
        )
        if (preserveMeaningfulActivity) {
            dao.upsertAccessAndTrim(entity, MAX_RECENT_ITEMS, RecentActivity.ACCESSED.name)
        } else {
            dao.upsertAndTrim(entity, MAX_RECENT_ITEMS)
        }
    }

    private fun toRecentItem(entity: RecentItemEntity): RecentItem = RecentItem(
        path = RootPath.parse(entity.absolutePath).getOrThrow(),
        displayName = entity.displayName,
        note = entity.note,
        type = RecentItemType.valueOf(entity.itemType),
        activity = RecentActivity.valueOf(entity.activity),
        lastActivityAt = entity.lastActivityAt,
        available = entity.available,
    )

    companion object {
        const val MAX_RECENT_ITEMS = 100
    }
}

private fun sameRecentContent(
    previous: List<RecentItemEntity>,
    current: List<RecentItemEntity>,
): Boolean = previous.size == current.size && previous.indices.all { index ->
    previous[index].sameContentAs(current[index])
}

private fun RecentItemEntity.sameContentAs(other: RecentItemEntity): Boolean =
    absolutePath == other.absolutePath &&
        displayName == other.displayName &&
        note == other.note &&
        itemType == other.itemType &&
        activity == other.activity &&
        lastActivityAt == other.lastActivityAt
