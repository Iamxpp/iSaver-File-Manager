package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileRangeProtocol
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import java.io.OutputStream
import java.security.MessageDigest

enum class ChecksumAlgorithm(val digestName: String, val label: String) {
    MD5("MD5", "MD5"),
    SHA1("SHA-1", "SHA-1"),
    SHA256("SHA-256", "SHA-256"),
    SHA512("SHA-512", "SHA-512"),
}

class FileChecksumRepository private constructor(
    private val copyToOutput: (suspend (DirectoryEntry, OutputStream) -> OperationResult<Long>)?,
    private val readRange: (suspend (DirectoryEntry, Long, Long) -> OperationResult<RootFileChunk>)?,
    private val chunkSizeBytes: Long,
) {
    internal constructor(
        copyToOutput: suspend (DirectoryEntry, OutputStream) -> OperationResult<Long>,
    ) : this(copyToOutput, null, 0)

    internal constructor(
        readRange: suspend (DirectoryEntry, Long, Long) -> OperationResult<RootFileChunk>,
        chunkSizeBytes: Long,
    ) : this(null, readRange, chunkSizeBytes)

    constructor(fileSystem: RootFileSystem) : this(
        copyToOutput = null,
        readRange = { entry, offset, count -> fileSystem.readRange(entry.path, offset, count) },
        chunkSizeBytes = RootFileRangeProtocol.MAX_RANGE_BYTES,
    )

    suspend fun sha256(entry: DirectoryEntry): OperationResult<String> {
        return checksum(entry, ChecksumAlgorithm.SHA256)
    }

    suspend fun checksum(
        entry: DirectoryEntry,
        algorithm: ChecksumAlgorithm,
    ): OperationResult<String> {
        if (entry.type != EntryType.FILE || entry.symbolicLink || !entry.readable || entry.sizeBytes == null) {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法计算此项目的校验和")
        }
        val digest = MessageDigest.getInstance(algorithm.digestName)
        val legacyCopy = copyToOutput
        if (legacyCopy != null) {
            val sink = object : OutputStream() {
                override fun write(value: Int) = digest.update(value.toByte())
                override fun write(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
            }
            return when (val copied = legacyCopy(entry, sink)) {
                is OperationResult.Failure -> copied
                is OperationResult.Success -> if (copied.value == entry.sizeBytes) success(digest) else changed()
            }
        }

        val rangeReader = requireNotNull(readRange)
        require(chunkSizeBytes in 1..RootFileRangeProtocol.MAX_RANGE_BYTES)
        var offset = 0L
        var expectedVersion: RootFileVersion? = null
        if (entry.sizeBytes == 0L) {
            return when (val read = rangeReader(entry, 0, 0)) {
                is OperationResult.Failure -> read
                is OperationResult.Success -> if (
                    read.value.bytes.isEmpty() && read.value.version.sizeBytes == 0L
                ) success(digest) else changed()
            }
        }
        while (offset < entry.sizeBytes) {
            val count = minOf(chunkSizeBytes, entry.sizeBytes - offset)
            when (val read = rangeReader(entry, offset, count)) {
                is OperationResult.Failure -> return read
                is OperationResult.Success -> {
                    if (read.value.bytes.size.toLong() != count || read.value.version.sizeBytes != entry.sizeBytes) {
                        return changed()
                    }
                    if (expectedVersion == null) expectedVersion = read.value.version
                    else if (expectedVersion != read.value.version) return changed()
                    digest.update(read.value.bytes)
                    offset += count
                }
            }
        }
        return success(digest)
    }

    private fun success(digest: MessageDigest) = OperationResult.Success(
        digest.digest().joinToString("") { "%02x".format(it) },
    )

    private fun changed() = OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "文件读取结果需要核对")
}
