package com.iamxpp.isaver.data.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) : RootShellCoordinator {
    private val mutex = Mutex()

    override suspend fun execute(command: String): RootCommandResult =
        mutex.withLock { backend.execute(command) }

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
            val result=shell.newJob().add(command).exec()
            RootCommandResult(result.code,result.out.toList(),result.err.toList())
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
