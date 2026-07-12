package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
)

internal fun interface RootCommandRunner {
    suspend fun run(command: String): RootCommandResult
}

class LibsuRootFileSystem internal constructor(
    internal val shellCoordinator: RootShellCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) : RootFileSystem {
    constructor(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(ApplicationRootShellCoordinator, ioDispatcher, timeoutMillis)

    internal constructor(
        commandRunner: RootCommandRunner,
        ioDispatcher: CoroutineDispatcher,
        timeoutMillis: Long,
    ) : this(CommandRunnerCoordinator(commandRunner), ioDispatcher, timeoutMillis)

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

    override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> =
        execute(buildCanonicalizeCommand(path), "无法解析真实路径").flatMap(::parseCanonicalOutput)

    override suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>{
        val originalParent=stat(parent);if(originalParent !is OperationResult.Success)return originalParent as OperationResult.Failure
        if(originalParent.value.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"无法在符号链接目录中创建文件夹","Original parent was symlink")
        val canonical=canonicalize(parent);if(canonical !is OperationResult.Success)return canonical as OperationResult.Failure
        val parentStat=stat(canonical.value);if(parentStat !is OperationResult.Success)return parentStat as OperationResult.Failure
        val p=parentStat.value
        if(p.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY)return failure(ErrorCode.NOT_DIRECTORY,"路径不是目录","Parent was not directory")
        if(p.symbolicLink)return failure(ErrorCode.COMMAND_FAILED,"无法在符号链接目录中创建文件夹","Canonical parent was symlink")
        if(!p.readable)return failure(ErrorCode.NOT_READABLE,"目录不可读","Parent not readable")
        if(!p.writable)return failure(ErrorCode.NOT_WRITABLE,"目录不可写","Parent not writable")
        val preIdentity=readIdentity(canonical.value);if(preIdentity !is OperationResult.Success)return preIdentity as OperationResult.Failure
        val child=FolderName.join(canonical.value,name)
        when(val e=stat(child)){is OperationResult.Success->return failure(ErrorCode.ALREADY_EXISTS,"文件夹已存在","Child exists");is OperationResult.Failure->if(e.code!=ErrorCode.NOT_FOUND)return e}
        val made=try{execute(buildMkdirCommand(parent,canonical.value,child,preIdentity.value),"无法创建文件夹")}catch(cancelled:CancellationException){
            try{withContext(NonCancellable){stat(child)}}catch(_:Exception){}
            throw cancelled
        }
        if(made is OperationResult.Failure){if(stat(child) is OperationResult.Success)return uncertain("Mkdir outcome unknown after child appeared");return made}
        val postIdentity=readIdentity(canonical.value)
        if(postIdentity !is OperationResult.Success||postIdentity.value!=preIdentity.value)return uncertain("Parent identity changed")
        val postParent=canonicalize(parent)
        if(postParent !is OperationResult.Success||postParent.value!=canonical.value)return uncertain("Canonical parent changed")
        val finalChild=stat(child)
        if(finalChild !is OperationResult.Success)return uncertain("Created child could not be verified")
        if(finalChild.value.type!=com.iamxpp.isaver.domain.EntryType.DIRECTORY||finalChild.value.symbolicLink)return uncertain("Created child was not a plain directory")
        return finalChild
    }

    private suspend fun execute(command: String, failureMessage: String = "无法读取目录信息"): OperationResult<List<String>> {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) { shellCoordinator.execute(command) }
            }
        } catch (_: TimeoutCancellationException) {
            return failure(ErrorCode.COMMAND_FAILED, "Root 操作超时", "Root command timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(ErrorCode.COMMAND_FAILED, failureMessage, "Root command execution failed")
        }
        if (result.exitCode != 0) return mapExitCode(result.exitCode, result.stderr.isNotEmpty(), failureMessage)
        return OperationResult.Success(result.stdout)
    }

    private fun mapExitCode(exitCode: Int, hadStderr: Boolean, failureMessage: String): OperationResult.Failure = when (exitCode) {
        EXIT_NOT_FOUND -> failure(ErrorCode.NOT_FOUND, "路径不存在", "Path was not found")
        EXIT_NOT_DIRECTORY -> failure(ErrorCode.NOT_DIRECTORY, "路径不是目录", "Path was not a directory")
        EXIT_NOT_READABLE -> failure(ErrorCode.NOT_READABLE, "目录不可读", "Path was not readable")
        48 -> failure(ErrorCode.NOT_WRITABLE, "目录不可写", "Path was not writable")
        else -> failure(
            ErrorCode.COMMAND_FAILED,
            failureMessage,
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

    /** Uses Android's `/system/bin/sh` mksh behavior available on the min API 29 target. */
    private fun buildCanonicalizeCommand(path: RootPath): String = """
        target=${RootCommandCodec.quote(path.value)}
        [ -e "${'$'}target" ] || [ -L "${'$'}target" ] || exit $EXIT_NOT_FOUND
        set -o pipefail
        readlink -f -- "${'$'}target" | base64 -w 0 || exit 47
    """.trimIndent()
    /** Shell checks reduce but cannot eliminate the tiny check/mkdir TOCTOU window; fully atomic defense requires native mkdirat. */
    private fun buildMkdirCommand(original:RootPath,parent:RootPath,child:RootPath,identity:RootFileIdentity)="""
        set -o pipefail
        original=${RootCommandCodec.quote(original.value)}
        parent=${RootCommandCodec.quote(parent.value)}
        child=${RootCommandCodec.quote(child.value)}
        [ ! -L "${'$'}original" ] && current=${'$'}(stat -c '%d:%i' -- "${'$'}original") && mapped=${'$'}(readlink -f -- "${'$'}original" | base64 -w 0) && [ ! -L "${'$'}original" ] && [ "${'$'}current" = '${identity.device}:${identity.inode}' ] && [ "${'$'}mapped" = ${RootCommandCodec.quote(canonicalLineBase64(parent))} ] && [ -d "${'$'}original" ] && [ -w "${'$'}original" ] && mkdir -- "${'$'}child"
    """.trimIndent()

    private fun canonicalLineBase64(path:RootPath)=Base64.getEncoder().encodeToString("${path.value}\n".toByteArray(StandardCharsets.UTF_8))

    private suspend fun readIdentity(path:RootPath):OperationResult<RootFileIdentity> = execute("stat -c '%d:%i' -- ${RootCommandCodec.quote(path.value)}","无法验证目录身份").flatMap{lines->RootFileIdentity.parse(lines).fold({OperationResult.Success(it)},{failure(ErrorCode.COMMAND_FAILED,"无法验证目录身份","Malformed file identity")})}

    private fun parseCanonicalOutput(lines: List<String>): OperationResult<RootPath> = try {
        require(lines.size == 1)
        val bytes = Base64.getDecoder().decode(lines.single())
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        require(decoded.endsWith('\n'))
        RootPath.parse(decoded.dropLast(1)).fold(
            onSuccess = { OperationResult.Success(it) },
            onFailure = { malformedCanonicalOutput() },
        )
    } catch (_: IllegalArgumentException) {
        malformedCanonicalOutput()
    } catch (_: CharacterCodingException) {
        malformedCanonicalOutput()
    }

    /**
     * Emits one safe record per entry. This M1 implementation performs per-entry stat and Base64
     * subprocess work; Task6 must consume it asynchronously and limit visible batches for large
     * directories without truncating this protocol.
     */
    private fun String.withRecordEmitter(): String = """
        emit_isaver_record() {
          item="${'$'}1"
          if [ -d "${'$'}item" ]; then kind=directory; elif [ -f "${'$'}item" ]; then kind=file; else kind=other; fi
          if [ "${'$'}kind" = file ]; then size=${'$'}(stat -c %s -- "${'$'}item") || exit 47; else size=-; fi
          mtime=${'$'}(stat -c %Y -- "${'$'}item") || mtime=-
          [ -r "${'$'}item" ] && readable=1 || readable=0
          [ -w "${'$'}item" ] && writable=1 || writable=0
          [ -L "${'$'}item" ] && symlink=1 || symlink=0
          trimmed="${'$'}item"
          while [ "${'$'}trimmed" != / ] && [ "${'$'}{trimmed%/}" != "${'$'}trimmed" ]; do trimmed=${'$'}{trimmed%/}; done
          if [ "${'$'}trimmed" = / ]; then name=/; else name=${'$'}{trimmed##*/}; fi
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

private class CommandRunnerCoordinator(
    private val commandRunner: RootCommandRunner,
) : RootShellCoordinator {
    override suspend fun execute(command: String): RootCommandResult = commandRunner.run(command)

    override suspend fun invalidate() = Unit
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

private fun malformedCanonicalOutput() = failure(
    ErrorCode.COMMAND_FAILED,
    "无法解析真实路径",
    "Malformed canonical path output",
)
private fun uncertain(technical:String)=failure(ErrorCode.OUTCOME_UNCERTAIN,"文件夹可能已创建，请刷新确认",technical)

private fun failure(code: ErrorCode, userMessage: String, technicalMessage: String) =
    OperationResult.Failure(code, userMessage, technicalMessage)
