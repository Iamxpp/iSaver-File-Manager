package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.RootPath

@ConsistentCopyVisibility
data class ExtractionStage internal constructor(
    val originalParent: RootPath,
    val canonicalParent: RootPath,
    val name: String,
    internal val parentIdentity: RootFileIdentity,
    internal val stageIdentity: RootFileIdentity,
) {
    companion object {
        internal fun create(
            originalParent: RootPath,
            canonicalParent: RootPath,
            parentIdentity: RootFileIdentity,
            name: String,
            stageIdentity: RootFileIdentity,
        ): Result<ExtractionStage> = runCatching {
            require(EXTRACTION_STAGE_NAME.matches(name)) { "invalid extraction stage name" }
            ExtractionStage(originalParent, canonicalParent, name, parentIdentity, stageIdentity)
        }
    }
}

@JvmInline
internal value class ExtractionRelativePath private constructor(val value: String) {
    companion object {
        fun directory(raw: String): Result<ExtractionRelativePath> = parse(raw, allowEmpty = false)
        fun parent(raw: String): Result<ExtractionRelativePath> = parse(raw, allowEmpty = true)

        private fun parse(raw: String, allowEmpty: Boolean): Result<ExtractionRelativePath> = runCatching {
            if (raw.isEmpty()) {
                require(allowEmpty) { "empty directory path" }
                return@runCatching ExtractionRelativePath("")
            }
            require(!raw.startsWith('/') && !raw.endsWith('/')) { "relative path required" }
            require("//" !in raw && '\u0000' !in raw) { "invalid relative path" }
            val components = raw.split('/')
            require(components.isNotEmpty()) { "empty relative path" }
            components.forEach { EntryName.parse(it).getOrThrow() }
            ExtractionRelativePath(raw)
        }
    }
}

internal val EXTRACTION_STAGE_NAME = Regex(
    "\\.isaver-extract-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
)
