package com.iamxpp.isaver.data.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellCoordinatorTest {
    @Test
    fun `timed out command closes cached shell and releases coordinator`() = runTest {
        val never = CompletableDeferred<Unit>()
        var closed = 0
        var calls = 0
        val backend = object : RootShellBackend {
            override suspend fun execute(command: String): RootCommandResult {
                calls += 1
                if (calls == 1) never.await()
                return RootCommandResult(0, listOf(command), emptyList())
            }

            override suspend fun closeCachedShell() {
                closed += 1
            }
        }
        val coordinator = MutexRootShellCoordinator(backend, commandTimeoutMillis = 100)

        val timedOut = coordinator.execute("stuck")
        val next = coordinator.execute("next")

        assertEquals(124, timedOut.exitCode)
        assertEquals(1, closed)
        assertEquals(listOf("next"), next.stdout)
    }

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

    @Test
    fun `cancelling caller after dispatch does not interrupt shared root command`() = runTest {
        val commandStarted = CompletableDeferred<Unit>()
        val allowCommandToFinish = CompletableDeferred<Unit>()
        var backendCancelled = false
        var backendCompleted = false
        val backend = object : RootShellBackend {
            override suspend fun execute(command: String): RootCommandResult = try {
                commandStarted.complete(Unit)
                allowCommandToFinish.await()
                backendCompleted = true
                RootCommandResult(0, emptyList(), emptyList())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                backendCancelled = true
                throw cancelled
            }

            override suspend fun closeCachedShell() = Unit
        }
        val coordinator = MutexRootShellCoordinator(backend)

        val execution = launch { coordinator.execute("list-dir") }
        commandStarted.await()
        execution.cancel()
        testScheduler.runCurrent()

        assertFalse(backendCancelled)
        allowCommandToFinish.complete(Unit)
        execution.cancelAndJoin()
        assertTrue(backendCompleted)
    }

}
