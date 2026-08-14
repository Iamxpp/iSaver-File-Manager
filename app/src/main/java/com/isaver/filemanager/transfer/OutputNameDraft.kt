package com.isaver.filemanager.transfer

import com.isaver.filemanager.domain.EntryName

data class OutputNameDraft(
    val stem: String,
    val extension: String,
) {
    fun toEntryName(): Result<EntryName> {
        if (stem.isEmpty() || stem.isBlank()) {
            return Result.failure(IllegalArgumentException("Output stem is required"))
        }
        if (extension.startsWith('.') || '/' in extension || '\u0000' in extension) {
            return Result.failure(IllegalArgumentException("Invalid output extension"))
        }
        val combined = if (extension.isEmpty()) stem else "$stem.$extension"
        return EntryName.parse(combined)
    }

    companion object {
        fun fromDisplayName(displayName: String): OutputNameDraft {
            val extensionStart = displayName.lastIndexOf('.')
            return if (extensionStart in 1 until displayName.lastIndex) {
                OutputNameDraft(
                    stem = displayName.substring(0, extensionStart),
                    extension = displayName.substring(extensionStart + 1),
                )
            } else {
                OutputNameDraft(stem = displayName, extension = "")
            }
        }
    }
}
