package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import java.io.OutputStream
import java.util.Base64

internal object RootFileReadProtocol {
    fun decode(
        lines: List<String>,
        output: OutputStream,
        expectedSizeBytes: Long,
    ): OperationResult<Long> = try {
        require(expectedSizeBytes >= 0L)
        require(lines.isNotEmpty())
        val header = lines.first().split('\t')
        require(header.size == 2 && header[0] == PROTOCOL)
        require(header[1].toLong() == expectedSizeBytes)
        var copied = 0L
        lines.drop(1).forEach { line ->
            require(line.isNotEmpty())
            val bytes = Base64.getDecoder().decode(line)
            copied = Math.addExact(copied, bytes.size.toLong())
            require(copied <= expectedSizeBytes)
            output.write(bytes)
        }
        require(copied == expectedSizeBytes)
        output.flush()
        OperationResult.Success(copied)
    } catch (_: IllegalArgumentException) {
        malformed()
    } catch (_: ArithmeticException) {
        malformed()
    } catch (_: Exception) {
        OperationResult.Failure(
            ErrorCode.COMMAND_FAILED,
            "无法缓存 Root 文件",
            "Private cache output failed",
        )
    }

    private fun malformed(): OperationResult.Failure = OperationResult.Failure(
        ErrorCode.SOURCE_UNREADABLE,
        "无法读取来源文件",
        "Malformed root file stream protocol",
    )

    private const val PROTOCOL = "ISAVER_FILE_V1"
}
