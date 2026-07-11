package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
)

internal fun interface RootCommandRunner {
    suspend fun run(command: String): RootCommandResult
}

class LibsuRootFileSystem internal constructor(
    private val commandRunner: RootCommandRunner,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) : RootFileSystem {
    constructor(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(LibsuCommandRunner, ioDispatcher, timeoutMillis)

    override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> =
        execute(buildListCommand(path)).flatMap { DirectoryListingParser.parse(it) }

    override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> =
        execute(buildStatCommand(path)).flatMap { lines ->
            when (val parsed = DirectoryListingParser.parse(lines)) {
                is OperationResult.Failure -> parsed
                is OperationResult.Success -> parsed.value.singleOrNull()?.let { OperationResult.Success(it) }
                    ?: malformedOutput()
            }
        }

    private suspend fun execute(command: String): OperationResult<List<String>> {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) { commandRunner.run(command) }
            }
        } catch (_: TimeoutCancellationException) {
            return failure(ErrorCode.COMMAND_FAILED, "Root 操作超时", "Root command timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(ErrorCode.ROOT_UNAVAILABLE, "Root 权限不可用", "Root command execution failed")
        }
        if (result.exitCode != 0) return mapExitCode(result.exitCode, result.stderr.isNotEmpty())
        return OperationResult.Success(result.stdout)
    }

    private fun mapExitCode(exitCode: Int, hadStderr: Boolean): OperationResult.Failure = when (exitCode) {
        EXIT_NOT_FOUND -> failure(ErrorCode.NOT_FOUND, "路径不存在", "Path was not found")
        EXIT_NOT_DIRECTORY -> failure(ErrorCode.NOT_DIRECTORY, "路径不是目录", "Path was not a directory")
        EXIT_NOT_READABLE -> failure(ErrorCode.NOT_READABLE, "目录不可读", "Path was not readable")
        else -> failure(
            ErrorCode.COMMAND_FAILED,
            "无法读取目录信息",
            if (hadStderr) "Root command failed with diagnostic output" else "Root command failed",
        )
    }

    private fun buildListCommand(path: RootPath): String {
        val quoted = RootCommandCodec.quote(path.value)
        return """
            dir=$quoted
            [ -e "${'$'}dir" ] || [ -L "${'$'}dir" ] || exit $EXIT_NOT_FOUND
            [ -d "${'$'}dir" ] || exit $EXIT_NOT_DIRECTORY
            [ -r "${'$'}dir" ] || exit $EXIT_NOT_READABLE
            for p in "${'$'}dir"/* "${'$'}dir"/.[!.]* "${'$'}dir"/..?*; do
              [ -e "${'$'}p" ] || [ -L "${'$'}p" ] || continue
              emit_isaver_record "${'$'}p"
            done
        """.trimIndent().withRecordEmitter()
    }

    private fun buildStatCommand(path: RootPath): String {
        val quoted = RootCommandCodec.quote(path.value)
        return """
            target=$quoted
            [ -e "${'$'}target" ] || [ -L "${'$'}target" ] || exit $EXIT_NOT_FOUND
            emit_isaver_record "${'$'}target"
        """.trimIndent().withRecordEmitter()
    }

    private fun String.withRecordEmitter(): String = """
        emit_isaver_record() {
          item="${'$'}1"
          if [ -d "${'$'}item" ]; then kind=directory; elif [ -f "${'$'}item" ]; then kind=file; else kind=other; fi
          if [ "${'$'}kind" = file ]; then size=${'$'}(stat -c %s -- "${'$'}item") || exit 47; else size=-; fi
          mtime=${'$'}(stat -c %Y -- "${'$'}item") || mtime=-
          [ -r "${'$'}item" ] && readable=1 || readable=0
          [ -w "${'$'}item" ] && writable=1 || writable=0
          [ -L "${'$'}item" ] && symlink=1 || symlink=0
          name=${'$'}{item##*/}
          [ -n "${'$'}name" ] || name=/
          name64=${'$'}(printf '%s' "${'$'}name" | base64 -w 0) || exit 47
          path64=${'$'}(printf '%s' "${'$'}item" | base64 -w 0) || exit 47
          printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}name64" "${'$'}path64" "${'$'}kind" "${'$'}size" "${'$'}mtime" "${'$'}readable" "${'$'}writable" "${'$'}symlink"
        }
        $this
    """.trimIndent()

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val EXIT_NOT_FOUND = 44
        const val EXIT_NOT_DIRECTORY = 45
        const val EXIT_NOT_READABLE = 46
    }
}

private object LibsuCommandRunner : RootCommandRunner {
    override suspend fun run(command: String): RootCommandResult = runInterruptible {
        val result = Shell.cmd(command).exec()
        RootCommandResult(result.code, result.out.toList(), result.err.toList())
    }
}

private inline fun <T, R> OperationResult<T>.flatMap(transform: (T) -> OperationResult<R>): OperationResult<R> =
    when (this) {
        is OperationResult.Failure -> this
        is OperationResult.Success -> transform(value)
    }

private fun malformedOutput() = failure(
    ErrorCode.COMMAND_FAILED,
    "无法读取目录信息",
    "Unexpected structured record count",
)

private fun failure(code: ErrorCode, userMessage: String, technicalMessage: String) =
    OperationResult.Failure(code, userMessage, technicalMessage)
