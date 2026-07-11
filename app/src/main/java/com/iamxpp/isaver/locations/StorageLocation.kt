package com.iamxpp.isaver.locations

import com.iamxpp.isaver.domain.RootPath
import java.util.Collections

@JvmInline
value class LocationId private constructor(val value: String) {
    companion object {
        fun of(value: String): LocationId {
            require(value.isNotBlank()) { "Location id must not be blank" }
            require('\u0000' !in value) { "Location id must not contain NUL" }
            return LocationId(value)
        }
    }
}

class PathCandidate(
    val id: LocationId,
    val displayName: String,
    val path: RootPath,
    val priority: Int,
) {
    init { require(priority >= 0) { "Candidate priority must be non-negative" } }
}

class AppPathTemplate(
    val id: LocationId,
    val displayName: String,
    packageNames: List<String>,
    candidates: List<PathCandidate>,
) {
    val packageNames: List<String> = immutableCopy(packageNames)
    val candidates: List<PathCandidate> = immutableCopy(candidates)

    init {
        require(this.candidates.map { it.id }.distinct().size == this.candidates.size) { "Candidate ids must be unique" }
        require(this.candidates.map { it.priority }.distinct().size == this.candidates.size) { "Candidate priorities must be unique" }
    }
}

sealed interface StorageLocation {
    val id: LocationId
    val displayName: String
    val source: Source

    class Direct(
        override val id: LocationId,
        override val displayName: String,
        val path: RootPath,
        override val source: Source,
    ) : StorageLocation

    class Group(
        override val id: LocationId,
        override val displayName: String,
        children: List<Direct>,
        override val source: Source,
    ) : StorageLocation {
        val children: List<Direct> = immutableCopy(children)
    }

    enum class Source { BUILT_IN, APP_TEMPLATE, CUSTOM, RECENT }
}

private fun <T> immutableCopy(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
