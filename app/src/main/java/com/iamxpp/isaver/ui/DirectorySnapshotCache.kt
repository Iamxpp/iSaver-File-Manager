package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.ui.files.SortSpec

internal data class DirectoryPresentationKey(
    val sortSpec: SortSpec,
    val searchQuery: String,
)

internal data class CachedDirectorySnapshot(
    val snapshot: DirectorySnapshot,
    val presentedEntries: List<DirectoryEntry>,
    val presentationKey: DirectoryPresentationKey,
)

class DirectorySnapshotCache(
    private val maxEntries: Int = 16,
    private val ttlMillis: Long = 2_000L,
    private val monotonicNowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private data class Entry(
        val value: CachedDirectorySnapshot,
        val storedAtMillis: Long,
    )

    private val snapshots = LinkedHashMap<RootPath, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    internal fun get(path: RootPath): CachedDirectorySnapshot? {
        val cached = snapshots[path] ?: return null
        val ageMillis = monotonicNowMillis() - cached.storedAtMillis
        if (ageMillis < 0L || ageMillis >= ttlMillis) {
            snapshots.remove(path)
            return null
        }
        return cached.value
    }

    @Synchronized
    internal fun put(
        path: RootPath,
        snapshot: DirectorySnapshot,
        presentedEntries: List<DirectoryEntry>,
        presentationKey: DirectoryPresentationKey,
    ) {
        snapshots[path] = Entry(
            CachedDirectorySnapshot(snapshot, presentedEntries, presentationKey),
            monotonicNowMillis(),
        )
        while (snapshots.size > maxEntries) {
            snapshots.remove(snapshots.entries.first().key)
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
