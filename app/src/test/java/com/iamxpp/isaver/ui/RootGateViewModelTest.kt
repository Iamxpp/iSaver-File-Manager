package com.iamxpp.isaver.ui

import com.iamxpp.isaver.data.root.RootSession
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootUidCheckResult
import com.iamxpp.isaver.data.root.RootUidChecker
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootGateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is checking`() {
        val viewModel = RootGateViewModel(
            rootSession = FakeRootSession(RootStatus.Available),
            checkDispatcher = dispatcher,
        )

        assertEquals(RootGateUiState.Checking, viewModel.state.value)

        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `available root grants access`() {
        val viewModel = RootGateViewModel(
            rootSession = FakeRootSession(RootStatus.Available),
            checkDispatcher = dispatcher,
        )

        dispatcher.scheduler.runCurrent()

        assertEquals(RootGateUiState.Granted, viewModel.state.value)
    }

    @Test
    fun `unavailable root denies access with user reason`() {
        val reason = "Root 权限未授予，请授权后重试"
        val viewModel = RootGateViewModel(
            rootSession = FakeRootSession(RootStatus.Unavailable(reason)),
            checkDispatcher = dispatcher,
        )

        dispatcher.scheduler.runCurrent()

        assertEquals(RootGateUiState.Denied(reason), viewModel.state.value)
    }

    @Test
    fun `retry returns to checking before publishing the new result`() {
        val rootSession = FakeRootSession(
            RootStatus.Unavailable("Root 权限未授予，请授权后重试"),
            RootStatus.Available,
        )
        val viewModel = RootGateViewModel(rootSession, dispatcher)
        dispatcher.scheduler.runCurrent()

        viewModel.retry()

        assertEquals(RootGateUiState.Checking, viewModel.state.value)
        dispatcher.scheduler.runCurrent()
        assertEquals(RootGateUiState.Granted, viewModel.state.value)
    }

    @Test
    fun `retry invalidates the previous root session`() {
        val rootSession = FakeRootSession(RootStatus.Available, RootStatus.Available)
        val viewModel = RootGateViewModel(rootSession, dispatcher)
        dispatcher.scheduler.runCurrent()

        viewModel.retry()

        assertEquals(1, rootSession.invalidations)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `session exception becomes a safe denial instead of crashing`() {
        val rootSession = object : RootSession {
            override suspend fun check(): RootStatus {
                error("sensitive technical stderr")
            }

            override fun invalidate() = Unit
        }
        val viewModel = RootGateViewModel(rootSession, dispatcher)

        dispatcher.scheduler.runCurrent()

        assertEquals(
            RootGateUiState.Denied("无法确认 Root 权限，请重试"),
            viewModel.state.value,
        )
    }

    @Test
    fun `an older check cannot overwrite a newer retry result`() {
        val first = CompletableDeferred<RootStatus>()
        val second = CompletableDeferred<RootStatus>()
        val viewModel = RootGateViewModel(
            rootSession = ControlledRootSession(first, second),
            checkDispatcher = dispatcher,
        )
        dispatcher.scheduler.runCurrent()

        viewModel.retry()
        dispatcher.scheduler.runCurrent()
        second.complete(RootStatus.Available)
        dispatcher.scheduler.runCurrent()
        first.complete(RootStatus.Unavailable("旧检测不应覆盖新结果"))
        dispatcher.scheduler.runCurrent()

        assertEquals(RootGateUiState.Granted, viewModel.state.value)
    }
}

private class FakeRootSession(
    vararg statuses: RootStatus,
) : RootSession {
    private val statuses = ArrayDeque(statuses.toList())
    var invalidations: Int = 0
        private set

    override suspend fun check(): RootStatus = statuses.removeFirst()

    override fun invalidate() {
        invalidations += 1
    }
}

private class ControlledRootSession(
    vararg results: CompletableDeferred<RootStatus>,
) : RootSession {
    private val results = ArrayDeque(results.toList())

    override suspend fun check(): RootStatus = withContext(NonCancellable) {
        results.removeFirst().await()
    }

    override fun invalidate() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibsuRootSessionTest {
    @Test
    fun `default session is constructible without exposing a command executor`() {
        LibsuRootSession()
    }

    @Test
    fun `exit zero with one canonical uid line is available`() = runTest {
        val session = LibsuRootSession(
            rootUidChecker = RootUidChecker {
                RootUidCheckResult(exitCode = 0, stdout = listOf("0"))
            },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            timeoutMillis = 5_000,
        )

        assertEquals(RootStatus.Available, session.check())
    }

    @Test
    fun `non canonical uid results are friendly unavailable states`() = runTest {
        val cases = listOf(
            RootUidCheckResult(exitCode = 1, stdout = emptyList()),
            RootUidCheckResult(exitCode = 0, stdout = emptyList()),
            RootUidCheckResult(exitCode = 0, stdout = listOf("2000")),
            RootUidCheckResult(exitCode = 0, stdout = listOf("0", "extra")),
            RootUidCheckResult(exitCode = 0, stdout = listOf(" 0")),
        )

        cases.forEach { result ->
            val session = LibsuRootSession(
                rootUidChecker = RootUidChecker { result },
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                timeoutMillis = 5_000,
            )

            assertEquals(
                RootStatus.Unavailable("Root 权限不可用，请授权后重试"),
                session.check(),
            )
        }
    }

    @Test
    fun `timeout becomes a friendly unavailable state`() = runTest {
        val session = LibsuRootSession(
            rootUidChecker = RootUidChecker { awaitCancellation() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            timeoutMillis = 1_000,
        )

        assertEquals(
            RootStatus.Unavailable("Root 检测超时，请授权后重试"),
            session.check(),
        )
    }

    @Test
    fun `command exception becomes a friendly unavailable state`() = runTest {
        val session = LibsuRootSession(
            rootUidChecker = RootUidChecker { error("sensitive root manager path") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            timeoutMillis = 5_000,
        )

        assertEquals(
            RootStatus.Unavailable("无法获取 Root 权限，请确认设备已 Root 并重试"),
            session.check(),
        )
    }

    @Test
    fun `invalidate closes the cached uid checker session`() {
        var invalidated = false
        val checker = object : RootUidChecker {
            override suspend fun check() = RootUidCheckResult(0, listOf("0"))

            override fun invalidate() {
                invalidated = true
            }
        }
        val session = LibsuRootSession(checker, Dispatchers.Unconfined, 5_000)

        session.invalidate()

        assertTrue(invalidated)
    }
}
