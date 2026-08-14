package com.isaver.filemanager.ui

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.DirectorySnapshot
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootBrowserPerformanceTest {
    @Test
    fun rootListingMeetsXiaomi9PerformanceBudgets() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val fileSystem = application.rootFileSystem

        val coldReadMillis = (0 until SAMPLE_COUNT).map { index ->
            measureSuspend {
                val snapshot = fileSystem.readDirectory(path("$FIXTURE_ROOT/app-cold-200-$index"))
                assertSnapshot(snapshot, 200)
            }
        }
        val warmPath = path("$FIXTURE_ROOT/warm-200")
        assertSnapshot(fileSystem.readDirectory(warmPath), 200)
        val warmReadMillis = List(SAMPLE_COUNT) {
            measureSuspend { assertSnapshot(fileSystem.readDirectory(warmPath), 200) }
        }

        val browser = BrowserViewModel(
            rootFileSystem = fileSystem,
            ioDispatcher = Dispatchers.IO,
            preferencesStore = application.browserPreferencesStore,
            snapshotCache = DirectorySnapshotCache(ttlMillis = 60_000L),
        )
        browser.openRoot(warmPath, "性能夹具")
        awaitLoaded(browser, warmPath, 200)
        val cacheHitMillis = List(SAMPLE_COUNT) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            browser.openRoot(warmPath, "性能夹具")
            val immediate = browser.state.value
            val elapsedMillis = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
            assertEquals(200, immediate.totalCount)
            assertTrue(immediate.entries.isNotEmpty())
            assertTrue(immediate.refreshing)
            awaitLoaded(browser, warmPath, 200)
            elapsedMillis
        }

        val firstVisibleMillis = (0 until SAMPLE_COUNT).map { index ->
            val largePath = path("$FIXTURE_ROOT/visible-1000-$index")
            val startedAt = SystemClock.elapsedRealtimeNanos()
            browser.openRoot(largePath, "性能夹具")
            withTimeout(2_000L) {
                browser.state
                    .filter { state -> state.currentPath == largePath && state.entries.isNotEmpty() }
                    .first()
            }
            nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        }

        val coldP95 = percentile95(coldReadMillis)
        val warmP95 = percentile95(warmReadMillis)
        val cacheP95 = percentile95(cacheHitMillis)
        val firstVisibleP95 = percentile95(firstVisibleMillis)
        Log.i(
            TAG,
            "cold200=${summary(coldReadMillis)} warm200=${summary(warmReadMillis)} " +
                "cache=${summary(cacheHitMillis)} visible1000=${summary(firstVisibleMillis)}",
        )

        assertTrue("200-entry cold read P95 was ${coldP95}ms", coldP95 < 500.0)
        assertTrue("200-entry warm read P95 was ${warmP95}ms", warmP95 < 500.0)
        assertTrue("Cache-hit presentation P95 was ${cacheP95}ms", cacheP95 < 100.0)
        assertTrue("1000-entry first-visible P95 was ${firstVisibleP95}ms", firstVisibleP95 < 500.0)
    }

    private suspend fun awaitLoaded(
        browser: BrowserViewModel,
        expectedPath: RootPath,
        expectedCount: Int,
    ) {
        withTimeout(2_000L) {
            browser.state
                .filter { state ->
                    state.currentPath == expectedPath &&
                        !state.refreshing &&
                        state.totalCount == expectedCount
                }
                .first()
        }
    }

    private suspend fun measureSuspend(block: suspend () -> Unit): Double {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        block()
        return nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
    }

    private fun assertSnapshot(
        result: OperationResult<DirectorySnapshot>,
        expectedCount: Int,
    ) {
        assertTrue("Root listing failed: $result", result is OperationResult.Success)
        result as OperationResult.Success
        assertEquals(expectedCount, result.value.entries.size)
    }

    private fun percentile95(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)]
    }

    private fun summary(values: List<Double>): String =
        "p50=${"%.2f".format(percentile(values, 50))}ms,p95=${"%.2f".format(percentile95(values))}ms"

    private fun percentile(values: List<Double>, percentile: Int): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size * percentile + 99) / 100 - 1).coerceIn(sorted.indices)]
    }

    private fun nanosToMillis(nanos: Long): Double = nanos / 1_000_000.0

    private fun path(value: String): RootPath = RootPath.parse(value).getOrThrow()

    private companion object {
        const val TAG = "ISaverPerf"
        const val FIXTURE_ROOT = "/data/local/tmp/isaver-perf"
        const val SAMPLE_COUNT = 20
    }
}
