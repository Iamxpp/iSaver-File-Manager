package com.iamxpp.isaver.data.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellCoordinatorTest {
    @Test
    fun `invalidate waits for an in flight command before closing shell`() = runTest {
        val commandStarted = CompletableDeferred<Unit>()
        val allowCommandToFinish = CompletableDeferred<Unit>()
        val backend = object : RootShellBackend {
            var closed = false

            override suspend fun execute(command: String): RootCommandResult {
                commandStarted.complete(Unit)
                allowCommandToFinish.await()
                return RootCommandResult(0, emptyList(), emptyList())
            }

            override suspend fun closeCachedShell() {
                closed = true
            }
        }
        val coordinator = MutexRootShellCoordinator(backend)

        val execution = async { coordinator.execute("long-running") }
        commandStarted.await()
        val invalidation = launch { coordinator.invalidate() }
        testScheduler.runCurrent()

        assertFalse(backend.closed)
        allowCommandToFinish.complete(Unit)
        execution.await()
        invalidation.join()
        assertTrue(backend.closed)
    }

    @Test
    fun `default session and filesystem share the application coordinator`() {
        assertSame(LibsuRootSession().shellCoordinator, LibsuRootFileSystem().shellCoordinator)
    }
}
