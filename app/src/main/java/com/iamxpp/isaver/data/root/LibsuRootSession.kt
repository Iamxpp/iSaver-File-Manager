package com.iamxpp.isaver.data.root

import com.topjohnwu.superuser.Shell
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
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
    private val rootUidChecker: RootUidChecker,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long,
) : RootSession {
    constructor(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(LibsuRootUidChecker, ioDispatcher, timeoutMillis)

    override suspend fun check(): RootStatus {
        val result = try {
            withTimeout(timeoutMillis) {
                withContext(ioDispatcher) { rootUidChecker.check() }
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
        withContext(ioDispatcher) { rootUidChecker.invalidate() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}

private object LibsuRootUidChecker : RootUidChecker {
    override suspend fun check(): RootUidCheckResult = runInterruptible {
        val result = Shell.cmd("id -u").exec()
        RootUidCheckResult(
            exitCode = result.code,
            stdout = result.out.toList(),
        )
    }

    override suspend fun invalidate() {
        runCatching { Shell.getCachedShell()?.close() }
    }
}
