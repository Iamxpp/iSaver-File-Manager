package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.DirectoryEntry
import com.isaver.filemanager.domain.EntryType
import com.isaver.filemanager.domain.RootPath
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal sealed interface NativeDirectoryListingParseResult {
    data class Success(val snapshot: DirectorySnapshot) : NativeDirectoryListingParseResult

    data class Failure(val reason: NativeDirectoryListingProtocolFailure) : NativeDirectoryListingParseResult
}

internal enum class NativeDirectoryListingProtocolFailure {
    FIELD_TOO_LARGE,
    MALFORMED_HEADER,
    PROTOCOL_TOO_LARGE,
    TOO_MANY_RECORDS,
}

internal data class NativeDirectoryListingLimits(
    val maxRecordCount: Int = 100_000,
    val maxFieldBytes: Int = 1_048_576,
    val maxProtocolBytes: Long = 67_108_864L,
) {
    init {
        require(maxRecordCount >= 0)
        require(maxFieldBytes > 0)
        require(maxProtocolBytes > 0)
    }
}

internal object NativeDirectoryListingParser {
    fun parse(
        lines: List<String>,
        expectedParent: RootPath,
        limits: NativeDirectoryListingLimits = NativeDirectoryListingLimits(),
    ): NativeDirectoryListingParseResult {
        if ((lines.size - 1).coerceAtLeast(0) > limits.maxRecordCount) {
            return NativeDirectoryListingParseResult.Failure(
                NativeDirectoryListingProtocolFailure.TOO_MANY_RECORDS,
            )
        }
        if (exceedsProtocolLimit(lines, limits.maxProtocolBytes)) {
            return NativeDirectoryListingParseResult.Failure(
                NativeDirectoryListingProtocolFailure.PROTOCOL_TOO_LARGE,
            )
        }
        val headerLine = lines.firstOrNull()
            ?: return NativeDirectoryListingParseResult.Failure(
                NativeDirectoryListingProtocolFailure.MALFORMED_HEADER,
            )
        val header = when (val parsed = parseHeader(headerLine, limits)) {
            is HeaderParseResult.Failure -> return NativeDirectoryListingParseResult.Failure(parsed.reason)
            is HeaderParseResult.Success -> parsed.header
        }
        val entries = ArrayList<DirectoryEntry>((lines.size - 1).coerceAtLeast(0))
        val recordFailures = ArrayList<NativeDirectoryListingRecordFailure>()
        for (lineIndex in 1 until lines.size) {
            try {
                entries += parseRecord(lines[lineIndex], expectedParent, limits)
            } catch (failure: InvalidRecordException) {
                recordFailures += NativeDirectoryListingRecordFailure(
                    recordIndex = lineIndex - 1,
                    reason = failure.reason,
                )
            }
        }
        return NativeDirectoryListingParseResult.Success(
            DirectorySnapshot(
                parentDevice = header.parentDevice,
                parentInode = header.parentInode,
                parentReadable = header.parentReadable,
                parentWritable = header.parentWritable,
                entries = entries,
                recordFailures = recordFailures,
            ),
        )
    }

    private fun parseHeader(
        line: String,
        limits: NativeDirectoryListingLimits,
    ): HeaderParseResult {
        val fields = when (
            val tokenized = tokenizeExactly(line, HEADER_FIELD_COUNT, limits.maxFieldBytes)
        ) {
            BoundedTokenizationResult.FieldTooLarge ->
                return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.FIELD_TOO_LARGE)
            BoundedTokenizationResult.InvalidFieldCount ->
                return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
            is BoundedTokenizationResult.Success -> tokenized.fields
        }
        if (fields.any { exceedsFieldLimit(it, limits.maxFieldBytes) }) {
            return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.FIELD_TOO_LARGE)
        }
        if (fields[0] != PROTOCOL_VERSION) {
            return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
        }
        val parentDevice = fields[1].toLongOrNull()?.takeIf { it >= 0 }
            ?: return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
        val parentInode = fields[2].toLongOrNull()?.takeIf { it >= 0 }
            ?: return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
        val parentReadable = parseHeaderBoolean(fields[3])
            ?: return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
        val parentWritable = parseHeaderBoolean(fields[4])
            ?: return HeaderParseResult.Failure(NativeDirectoryListingProtocolFailure.MALFORMED_HEADER)
        return HeaderParseResult.Success(
            ParentHeader(
                parentDevice = parentDevice,
                parentInode = parentInode,
                parentReadable = parentReadable,
                parentWritable = parentWritable,
            ),
        )
    }

    private fun parseRecord(
        line: String,
        expectedParent: RootPath,
        limits: NativeDirectoryListingLimits,
    ): DirectoryEntry {
        val fields = when (
            val tokenized = tokenizeExactly(line, RECORD_FIELD_COUNT, limits.maxFieldBytes)
        ) {
            BoundedTokenizationResult.FieldTooLarge ->
                invalidRecord(NativeDirectoryListingRecordFailureReason.FIELD_TOO_LARGE)
            BoundedTokenizationResult.InvalidFieldCount ->
                invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_FIELD_COUNT)
            is BoundedTokenizationResult.Success -> tokenized.fields
        }
        if (fields.any { exceedsFieldLimit(it, limits.maxFieldBytes) }) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.FIELD_TOO_LARGE)
        }
        val name = decodeCanonicalBase64Utf8(fields[0])
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_NAME)
        }
        val path = RootPath.parse(decodeCanonicalBase64Utf8(fields[1])).getOrElse {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_PATH)
        }
        if (!isExactDirectChild(path.value, expectedParent.value, name)) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.PATH_MISMATCH)
        }
        val type = when (fields[2]) {
            "directory" -> EntryType.DIRECTORY
            "file" -> EntryType.FILE
            "other" -> EntryType.OTHER
            else -> invalidRecord(NativeDirectoryListingRecordFailureReason.UNKNOWN_TYPE)
        }
        return DirectoryEntry(
            path = path,
            name = name,
            type = type,
            sizeBytes = nullableSize(fields[3]),
            modifiedAtEpochSeconds = nullableRecordLong(fields[4]),
            readable = recordBoolean(fields[5]),
            writable = recordBoolean(fields[6]),
            symbolicLink = recordBoolean(fields[7]),
        )
    }

    private fun decodeCanonicalBase64Utf8(encoded: String): String {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_BASE64)
        }
        if (Base64.getEncoder().encodeToString(bytes) != encoded) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_BASE64)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_UTF8)
        }
    }

    private fun tokenizeExactly(
        line: String,
        fieldCount: Int,
        maxFieldBytes: Int,
    ): BoundedTokenizationResult {
        val delimiterPositions = IntArray(fieldCount - 1)
        var fieldStart = 0
        for (fieldIndex in 0 until fieldCount - 1) {
            val delimiter = line.indexOf('\t', fieldStart)
            if (delimiter < 0) {
                return BoundedTokenizationResult.InvalidFieldCount
            }
            delimiterPositions[fieldIndex] = delimiter
            fieldStart = delimiter + 1
        }
        if (line.indexOf('\t', fieldStart) >= 0) {
            return BoundedTokenizationResult.InvalidFieldCount
        }
        val fields = Array(fieldCount) { "" }
        fieldStart = 0
        for (fieldIndex in delimiterPositions.indices) {
            val delimiter = delimiterPositions[fieldIndex]
            if (delimiter - fieldStart > maxFieldBytes) {
                return BoundedTokenizationResult.FieldTooLarge
            }
            fields[fieldIndex] = line.substring(fieldStart, delimiter)
            fieldStart = delimiter + 1
        }
        if (line.length - fieldStart > maxFieldBytes) {
            return BoundedTokenizationResult.FieldTooLarge
        }
        fields[fieldCount - 1] = line.substring(fieldStart)
        return BoundedTokenizationResult.Success(fields)
    }

    private fun exceedsProtocolLimit(lines: List<String>, maxBytes: Long): Boolean {
        var asciiLowerBound = 0L
        var utf8UpperBound = 0L
        for (line in lines) {
            asciiLowerBound = saturatingAdd(asciiLowerBound, line.length.toLong())
            asciiLowerBound = saturatingAdd(asciiLowerBound, 1L)
            if (asciiLowerBound > maxBytes) {
                return true
            }
            utf8UpperBound = saturatingAdd(
                utf8UpperBound,
                saturatingMultiply(line.length.toLong(), MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT),
            )
            utf8UpperBound = saturatingAdd(utf8UpperBound, 1L)
        }
        if (utf8UpperBound <= maxBytes) {
            return false
        }
        var remaining = maxBytes
        for (line in lines) {
            if (remaining < 1L) {
                return true
            }
            val lineBytes = utf8SizeAtMost(line, remaining - 1L) ?: return true
            remaining -= lineBytes + 1L
        }
        return false
    }

    private fun exceedsFieldLimit(value: String, maxBytes: Int): Boolean {
        val maxBytesLong = maxBytes.toLong()
        val asciiLowerBound = value.length.toLong()
        if (asciiLowerBound > maxBytesLong) {
            return true
        }
        val utf8UpperBound = saturatingMultiply(
            asciiLowerBound,
            MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT,
        )
        return utf8UpperBound > maxBytesLong && utf8SizeAtMost(value, maxBytesLong) == null
    }

    private fun utf8SizeAtMost(value: String, maxBytes: Long): Long? {
        if (value.length.toLong() > maxBytes) {
            return null
        }
        var byteCount = 0L
        var index = 0
        while (index < value.length) {
            val character = value[index]
            val characterBytes = when {
                character.code <= 0x7f -> 1L
                character.code <= 0x7ff -> 2L
                Character.isHighSurrogate(character) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index += 1
                    4L
                }
                else -> 3L
            }
            if (byteCount > maxBytes - characterBytes) {
                return null
            }
            byteCount += characterBytes
            index += 1
        }
        return byteCount
    }

    private fun isExactDirectChild(path: String, parent: String, name: String): Boolean {
        val separatorLength = if (parent == "/" || parent.endsWith('/')) 0L else 1L
        val expectedLength = parent.length.toLong() + separatorLength + name.length.toLong()
        if (path.length.toLong() != expectedLength || !path.startsWith(parent)) {
            return false
        }
        val nameStart = when {
            parent == "/" || parent.endsWith('/') -> parent.length
            path.getOrNull(parent.length) == '/' -> parent.length + 1
            else -> return false
        }
        return path.regionMatches(nameStart, name, 0, name.length, ignoreCase = false)
    }

    private fun nullableSize(value: String): Long? {
        val size = nullableRecordLong(value)
        if (size != null && size < 0) {
            invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_NUMBER)
        }
        return size
    }

    private fun nullableRecordLong(value: String): Long? = when (value) {
        "-" -> null
        else -> value.toLongOrNull()
            ?: invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_NUMBER)
    }

    private fun parseHeaderBoolean(value: String): Boolean? = when (value) {
        "0" -> false
        "1" -> true
        else -> null
    }

    private fun recordBoolean(value: String): Boolean = when (value) {
        "0" -> false
        "1" -> true
        else -> invalidRecord(NativeDirectoryListingRecordFailureReason.INVALID_BOOLEAN)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(value: Long, factor: Long): Long =
        if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor

    private const val PROTOCOL_VERSION = "ISAVER_LIST_V1"
    private const val HEADER_FIELD_COUNT = 5
    private const val RECORD_FIELD_COUNT = 8
    private const val MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT = 3L
}

private sealed interface HeaderParseResult {
    data class Success(val header: ParentHeader) : HeaderParseResult

    data class Failure(val reason: NativeDirectoryListingProtocolFailure) : HeaderParseResult
}

private sealed interface BoundedTokenizationResult {
    data class Success(val fields: Array<String>) : BoundedTokenizationResult

    data object InvalidFieldCount : BoundedTokenizationResult

    data object FieldTooLarge : BoundedTokenizationResult
}

private data class ParentHeader(
    val parentDevice: Long,
    val parentInode: Long,
    val parentReadable: Boolean,
    val parentWritable: Boolean,
)

private class InvalidRecordException(
    val reason: NativeDirectoryListingRecordFailureReason,
) : RuntimeException(null, null, false, false)

private fun invalidRecord(reason: NativeDirectoryListingRecordFailureReason): Nothing =
    throw InvalidRecordException(reason)
