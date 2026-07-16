package com.iamxpp.isaver.archive

import java.nio.charset.StandardCharsets

object ArchivePathPolicy {
    fun normalizeRelative(raw: String): Result<String> = runCatching {
        require(raw.isNotEmpty()) { "archive path is empty" }
        require('\u0000' !in raw) { "archive path contains NUL" }
        val slashPath = raw.replace('\\', '/')
        require(!slashPath.startsWith('/')) { "archive path is absolute" }
        require(!DRIVE_PREFIX.matches(slashPath)) { "archive path has a drive prefix" }
        val components = slashPath.split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(components.isNotEmpty()) { "archive path is empty" }
        require(components.none { it == ".." }) { "archive path traverses parent" }
        val normalized = components.joinToString("/")
        require(normalized.toByteArray(StandardCharsets.UTF_8).size <= 255) {
            "archive path is too long"
        }
        normalized
    }

    fun rejectSymbolicLink(symbolicLink: Boolean): Result<Unit> = runCatching {
        require(!symbolicLink) { "symbolic links are not supported in archives" }
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:($|/).*")
}

class ArchiveEntryNameSet {
    private val names = mutableSetOf<String>()

    fun add(raw: String): Result<String> = runCatching {
        val normalized = ArchivePathPolicy.normalizeRelative(raw).getOrThrow()
        check(names.add(normalized)) { "duplicate archive entry" }
        normalized
    }
}
