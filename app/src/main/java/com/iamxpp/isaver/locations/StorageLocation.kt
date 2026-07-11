package com.iamxpp.isaver.locations

import com.iamxpp.isaver.domain.RootPath

data class PathCandidate(
    val id: String,
    val displayName: String,
    val path: RootPath,
    val priority: Int,
)

data class AppPathTemplate(
    val id: String,
    val displayName: String,
    val packageNames: List<String>,
    val candidates: List<PathCandidate>,
)

sealed interface StorageLocation {
    val id: String
    val displayName: String
    val source: Source

    data class Direct(
        override val id: String,
        override val displayName: String,
        val path: RootPath,
        override val source: Source,
    ) : StorageLocation

    data class Group(
        override val id: String,
        override val displayName: String,
        val children: List<Direct>,
        override val source: Source,
    ) : StorageLocation

    enum class Source { BUILT_IN, APP_TEMPLATE, CUSTOM, RECENT }
}
