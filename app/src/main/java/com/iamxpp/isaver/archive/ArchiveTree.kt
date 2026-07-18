package com.iamxpp.isaver.archive

import java.util.Locale

data class ArchiveNode(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long?,
    val compressedSizeBytes: Long?,
)

fun ArchiveListing.children(prefix: String): List<ArchiveNode> {
    val normalizedPrefix = prefix.trim('/')
    val prefixWithSeparator = normalizedPrefix.takeIf(String::isNotEmpty)?.plus('/') ?: ""
    val nodes = linkedMapOf<String, ArchiveNode>()
    entries.forEach { entry ->
        val normalizedPath = entry.path.trim('/')
        val remainder = when {
            normalizedPrefix.isEmpty() -> normalizedPath
            normalizedPath.startsWith(prefixWithSeparator) -> normalizedPath.removePrefix(prefixWithSeparator)
            else -> return@forEach
        }
        if (remainder.isEmpty()) return@forEach
        val name = remainder.substringBefore('/')
        val directory = '/' in remainder || entry.directory
        val path = if (normalizedPrefix.isEmpty()) name else "$normalizedPrefix/$name"
        val candidate = ArchiveNode(
            name = name,
            path = path,
            directory = directory,
            sizeBytes = entry.sizeBytes.takeUnless { directory },
            compressedSizeBytes = entry.compressedSizeBytes.takeUnless { directory },
        )
        val existing = nodes[name]
        nodes[name] = if (existing == null || !existing.directory || !directory) {
            if (existing?.directory == true || directory) candidate.copy(
                directory = true,
                sizeBytes = null,
                compressedSizeBytes = null,
            ) else candidate
        } else {
            existing
        }
    }
    return nodes.values.sortedWith(archiveNodeComparator)
}

fun archiveDisplayName(fileName: String): String {
    val lower = fileName.lowercase(Locale.ROOT)
    val suffix = ARCHIVE_SUFFIXES.firstOrNull(lower::endsWith) ?: return fileName
    return fileName.dropLast(suffix.length).ifEmpty { fileName }
}

private val archiveNodeComparator = Comparator<ArchiveNode> { left, right ->
    when {
        left.directory != right.directory -> if (left.directory) -1 else 1
        else -> compareNatural(left.name, right.name)
    }
}

private fun compareNatural(left: String, right: String): Int {
    val leftChunks = chunks(left)
    val rightChunks = chunks(right)
    for (index in 0 until minOf(leftChunks.size, rightChunks.size)) {
        val comparison = compareChunk(leftChunks[index], rightChunks[index])
        if (comparison != 0) return comparison
    }
    return leftChunks.size.compareTo(rightChunks.size).takeIf { it != 0 }
        ?: left.compareTo(right)
}

private fun chunks(value: String): List<String> =
    CHUNK_PATTERN.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

private fun compareChunk(left: String, right: String): Int {
    if (!left.first().isDigit() || !right.first().isDigit()) return left.compareTo(right)
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return normalizedLeft.length.compareTo(normalizedRight.length).takeIf { it != 0 }
        ?: normalizedLeft.compareTo(normalizedRight)
}

private val ARCHIVE_SUFFIXES = listOf(".tar.gz", ".tgz", ".zip", ".tar", ".7z", ".rar")
private val CHUNK_PATTERN = Regex("\\d+|\\D+")
