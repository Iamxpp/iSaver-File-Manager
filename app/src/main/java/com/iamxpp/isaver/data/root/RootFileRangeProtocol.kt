package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import java.io.ByteArrayOutputStream
import java.util.Base64

data class RootFileVersion(
    val sizeBytes: Long,
    val device: Long,
    val inode: Long,
    val modifiedSeconds: Long,
    val modifiedNanoseconds: Long,
    val changedSeconds: Long,
    val changedNanoseconds: Long,
)

data class RootFileChunk(val bytes: ByteArray, val version: RootFileVersion)

internal object RootFileRangeProtocol {
    fun decode(lines: List<String>, expectedOffset: Long, expectedCount: Long): OperationResult<RootFileChunk> = try {
        require(expectedOffset >= 0 && expectedCount >= 0 && expectedCount <= MAX_RANGE_BYTES)
        require(lines.isNotEmpty())
        val fields = lines.first().split('\t')
        require(fields.size == 10 && fields[0] == PROTOCOL)
        val version = RootFileVersion(
            fields[1].toLong(), fields[2].toLong(), fields[3].toLong(), fields[4].toLong(),
            fields[5].toLong(), fields[6].toLong(), fields[7].toLong(),
        )
        val offset = fields[8].toLong()
        val count = fields[9].toLong()
        require(version.sizeBytes >= 0 && version.device >= 0 && version.inode >= 0)
        require(version.modifiedNanoseconds in 0..999_999_999)
        require(version.changedNanoseconds in 0..999_999_999)
        require(offset == expectedOffset && count == expectedCount)
        require(offset <= version.sizeBytes && count <= version.sizeBytes - offset)
        require(count <= Int.MAX_VALUE)
        val output = ByteArrayOutputStream(count.toInt())
        lines.drop(1).forEach { line ->
            require(line.isNotEmpty())
            val decoded = Base64.getDecoder().decode(line)
            output.write(decoded)
            require(output.size().toLong() <= count)
        }
        require(output.size().toLong() == count)
        OperationResult.Success(RootFileChunk(output.toByteArray(), version))
    } catch (_: IllegalArgumentException) {
        malformed()
    } catch (_: ArithmeticException) {
        malformed()
    }

    private fun malformed() = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE, "无法读取来源文件", "Malformed root file range protocol",
    )

    const val MAX_RANGE_BYTES = 4L * 1024L * 1024L
    private const val PROTOCOL = "ISAVER_RANGE_V1"
}
