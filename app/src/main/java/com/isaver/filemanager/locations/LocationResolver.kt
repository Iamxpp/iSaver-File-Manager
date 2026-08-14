package com.isaver.filemanager.locations

import com.isaver.filemanager.data.root.RootFileSystem
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.OperationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

data class ResolvedAppLocation(
    val templateId: LocationId,
    val displayName: String,
    val children: List<StorageLocation.Direct>,
    val unavailableCount: Int,
) {
    val empty: Boolean get() = children.isEmpty()
}

class LocationResolver(
    private val rootFileSystem: RootFileSystem,
    private val dispatcher: CoroutineDispatcher,
    maxConcurrency: Int = 4,
) {
    private val semaphore = Semaphore(maxConcurrency.also { require(it > 0) })

    suspend fun resolve(template: AppPathTemplate): ResolvedAppLocation = withContext(dispatcher) {
        val ordered = template.candidates.sortedBy { it.priority }
        val probed = coroutineScope {
            ordered.mapIndexed { index, candidate -> async { semaphore.withPermit { index to probe(candidate) } } }.awaitAll()
        }.sortedBy { it.first }.map { it.second }
        val seen = mutableSetOf<String>()
        var unavailable = 0
        val children = buildList {
            probed.forEach { direct ->
                if (direct == null) unavailable++
                else if (seen.add(direct.path.value)) add(direct)
            }
        }
        ResolvedAppLocation(template.id, template.displayName, children, unavailable)
    }

    private suspend fun probe(candidate: PathCandidate): StorageLocation.Direct? {
        return try {
            val stat = rootFileSystem.stat(candidate.path)
            if (stat !is OperationResult.Success || stat.value.type != EntryType.DIRECTORY || !stat.value.readable) return null
            val canonical = rootFileSystem.canonicalize(candidate.path)
            if (canonical !is OperationResult.Success) return null
            StorageLocation.Direct(candidate.id, candidate.displayName, canonical.value, StorageLocation.Source.APP_TEMPLATE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
