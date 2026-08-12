package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import java.io.OutputStream
import java.security.MessageDigest

class FileChecksumRepository internal constructor(
    private val copyToOutput: suspend (DirectoryEntry, OutputStream) -> OperationResult<Long>,
) {
    constructor(fileSystem: RootFileSystem) : this(
        copyToOutput = { entry, output -> fileSystem.copyToOutput(entry.path, output) },
    )

    suspend fun sha256(entry: DirectoryEntry): OperationResult<String> {
        if (entry.type != EntryType.FILE || entry.symbolicLink || !entry.readable || entry.sizeBytes == null) {
            return OperationResult.Failure(ErrorCode.SOURCE_UNREADABLE, "无法计算此项目的校验和")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(value: Int) = digest.update(value.toByte())
            override fun write(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
        }
        return when (val copied = copyToOutput(entry, sink)) {
            is OperationResult.Failure -> copied
            is OperationResult.Success -> if (copied.value == entry.sizeBytes) {
                OperationResult.Success(digest.digest().joinToString("") { "%02x".format(it) })
            } else {
                OperationResult.Failure(ErrorCode.OUTCOME_UNCERTAIN, "文件读取结果需要核对")
            }
        }
    }
}
