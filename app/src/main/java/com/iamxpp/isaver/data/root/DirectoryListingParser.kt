package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object DirectoryListingParser {
    private const val FIELD_COUNT = 8

    fun parse(lines: List<String>): OperationResult<List<DirectoryEntry>> = try {
        OperationResult.Success(lines.map(::parseLine))
    } catch (_: IllegalArgumentException) {
        OperationResult.Failure(
            code = ErrorCode.COMMAND_FAILED,
            userMessage = "无法读取目录信息",
            technicalMessage = "Malformed structured directory output",
        )
    }

    private fun parseLine(line: String): DirectoryEntry {
        val fields = line.split('\t')
        require(fields.size == FIELD_COUNT)
        val name = decodeUtf8(fields[0])
        val path = RootPath.parse(decodeUtf8(fields[1])).getOrElse { throw IllegalArgumentException() }
        val type = when (fields[2]) {
            "directory" -> EntryType.DIRECTORY
            "file" -> EntryType.FILE
            "other" -> EntryType.OTHER
            else -> throw IllegalArgumentException()
        }
        return DirectoryEntry(
            path = path,
            name = name,
            type = type,
            sizeBytes = nullableLong(fields[3]),
            modifiedAtEpochSeconds = nullableLong(fields[4]),
            readable = strictBoolean(fields[5]),
            writable = strictBoolean(fields[6]),
            symbolicLink = strictBoolean(fields[7]),
        )
    }

    private fun decodeUtf8(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun nullableLong(value: String): Long? = if (value == "-") null else value.toLong()

    private fun strictBoolean(value: String): Boolean = when (value) {
        "0" -> false
        "1" -> true
        else -> throw IllegalArgumentException()
    }
}
