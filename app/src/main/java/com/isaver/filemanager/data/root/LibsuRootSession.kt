package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.RootStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class RootUidCheckResult(
    val exitCode: Int,
    val stdout: List<String>,
)

internal fun interface RootUidChecker {
    suspend fun check(): RootUidCheckResult

    suspend fun invalidate() = Unit
}

class LibsuRootSession internal constructor(
    internal val shellCoordinator: RootShellCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) : RootSession {
    constructor(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(ApplicationRootShellCoordinator, ioDispatcher, timeoutMillis)

    internal constructor(
        rootUidChecker: RootUidChecker,
        ioDispatcher: CoroutineDispatcher,
        timeoutMillis: Long,
    ) : this(UidCheckerCoordinator(rootUidChecker), ioDispatcher, timeoutMillis)

    override suspend fun check(): RootStatus {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) {
                    val command = shellCoordinator.execute("id -u")
                    RootUidCheckResult(command.exitCode, command.stdout)
                }
            }
        } catch (_: TimeoutCancellationException) {
            return RootStatus.Unavailable("Root 检测超时，请授权后重试")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RootStatus.Unavailable("无法获取 Root 权限，请确认设备已 Root 并重试")
        }
        return if (result.exitCode == 0 && result.stdout == listOf("0")) {
            RootStatus.Available
        } else {
            RootStatus.Unavailable("Root 权限不可用，请授权后重试")
        }
    }

    override suspend fun invalidate() {
        withContext(ioDispatcher) { shellCoordinator.invalidate() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

private class UidCheckerCoordinator(
    private val checker: RootUidChecker,
) : RootShellCoordinator {
    override suspend fun execute(command: String): RootCommandResult {
        require(command == "id -u")
        val result = checker.check()
        return RootCommandResult(result.exitCode, result.stdout, emptyList())
    }

    override suspend fun invalidate() = checker.invalidate()
}
