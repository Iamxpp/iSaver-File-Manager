package com.isaver.filemanager.data.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface RootShellCoordinator {
    suspend fun execute(command: String): RootCommandResult

    suspend fun invalidate()
}

internal interface RootShellBackend {
    suspend fun execute(command: String): RootCommandResult

    suspend fun closeCachedShell()
}

internal class MutexRootShellCoordinator(
    private val backend: RootShellBackend,
    private val commandTimeoutMillis: Long = 15_000L,
) : RootShellCoordinator {
    private val mutex = Mutex()

    override suspend fun execute(command: String): RootCommandResult =
        mutex.withLock {
            withContext(NonCancellable) {
                try {
                    withTimeout(commandTimeoutMillis) { backend.execute(command) }
                } catch (_: TimeoutCancellationException) {
                    backend.closeCachedShell()
                    RootCommandResult(124, emptyList(), listOf("Root command timed out"))
                }
            }
        }

    override suspend fun invalidate() {
        mutex.withLock { backend.closeCachedShell() }
    }
}

internal object ApplicationRootShellCoordinator : RootShellCoordinator by
    MutexRootShellCoordinator(LibsuRootShellBackend)

internal fun interface RootTransferCommandRunner{
    suspend fun run(command:String):RootCommandResult
}

internal object IsolatedLibsuRootTransferCommandRunner:RootTransferCommandRunner{
    override suspend fun run(command:String):RootCommandResult=runInterruptible{
        val shell=Shell.Builder.create().build()
        try{
            if(!shell.isRoot)return@runInterruptible RootCommandResult(43,emptyList(),emptyList())
            val stdout=mutableListOf<String>()
            val stderr=mutableListOf<String>()
            val result=shell.newJob().to(stdout,stderr).add(command).exec()
            RootCommandResult(result.code,stdout,stderr)
        }finally{
            runCatching{shell.close()}
        }
    }
}

private object LibsuRootShellBackend : RootShellBackend {
    override suspend fun execute(command: String): RootCommandResult = runInterruptible {
        val result = Shell.cmd(command).exec()
        RootCommandResult(result.code, result.out.toList(), result.err.toList())
    }

    override suspend fun closeCachedShell() = runInterruptible {
        runCatching { Shell.getCachedShell()?.close() }
        Unit
    }
}
