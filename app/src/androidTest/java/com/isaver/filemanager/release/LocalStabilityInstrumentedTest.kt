package com.isaver.filemanager.release

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootCommandCodec
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootStatus
import com.isaver.filemanager.fileops.ChecksumAlgorithm
import com.isaver.filemanager.ui.BrowserViewModel
import com.isaver.filemanager.ui.DirectorySnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStabilityInstrumentedTest {
    @Test
    fun tenThousandEntriesAndLargeSparseFileRemainBounded() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
            assertEquals(RootStatus.Available, app.rootSession.check())
            root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(LARGE_DIRECTORY)}")
            try {
                root(
                    app,
                    "i=0; while [ \"\$i\" -lt 10000 ]; do : > ${quote(LARGE_DIRECTORY)}/item-\$i; i=\$((i+1)); done",
                )
                val listStarted = SystemClock.elapsedRealtime()
                val snapshot = app.rootFileSystem.readDirectory(path(LARGE_DIRECTORY))
                val listMillis = SystemClock.elapsedRealtime() - listStarted
                assertTrue(snapshot.toString(), snapshot is OperationResult.Success)
                snapshot as OperationResult.Success
                assertEquals(10_000, snapshot.value.entries.size)
                assertTrue("10,000-entry listing took ${listMillis}ms", listMillis < 15_000L)

                val browser = BrowserViewModel(
                    rootFileSystem = app.rootFileSystem,
                    ioDispatcher = Dispatchers.IO,
                    preferencesStore = app.browserPreferencesStore,
                    snapshotCache = DirectorySnapshotCache(ttlMillis = 60_000L),
                )
                browser.openRoot(path(LARGE_DIRECTORY), "万项目录", recordAccess = false)
                withTimeout(15_000L) {
                    browser.state.filter { !it.refreshing && it.totalCount == 10_000 }.first()
                }
                assertEquals(10_000, browser.state.value.allEntries.size)
                assertEquals(200, browser.state.value.entries.size)
                assertTrue(browser.state.value.hasMore)
                browser.loadMore()
                assertEquals(400, browser.state.value.entries.size)

                root(
                    app,
                    "truncate -s $LARGE_FILE_BYTES ${quote(LARGE_FILE)}; " +
                        "printf 'ISAVER-LARGE-END' | dd of=${quote(LARGE_FILE)} bs=1 seek=$LARGE_FILE_TAIL_OFFSET conv=notrunc 2>/dev/null",
                )
                val entry = success(app.rootFileSystem.stat(path(LARGE_FILE)))
                assertEquals(LARGE_FILE_BYTES, entry.sizeBytes)
                val lastPage = success(app.hexViewerRepository.loadPage(entry, LARGE_FILE_BYTES - 1))
                assertEquals(LARGE_FILE_BYTES - 4096L, lastPage.offset)
                assertFalse(lastPage.hasNext)
                assertTrue(lastPage.rows.joinToString("") { it.ascii }.contains("ISAVER-LARGE-END"))

                val checksumStarted = SystemClock.elapsedRealtime()
                val checksum = success(app.fileChecksumRepository.checksum(entry, ChecksumAlgorithm.SHA256))
                val checksumMillis = SystemClock.elapsedRealtime() - checksumStarted
                val expected = root(app, "sha256sum -- ${quote(LARGE_FILE)}").substringBefore(' ')
                assertEquals(expected, checksum)
                assertTrue("512 MiB checksum took ${checksumMillis}ms", checksumMillis < 120_000L)
                Log.i(TAG, "entries10000=${listMillis}ms checksum512MiB=${checksumMillis}ms")
            } finally {
                root(app, "rm -rf -- ${quote(ROOT)}")
            }
        }
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun <T> success(result: OperationResult<T>): T = (result as OperationResult.Success).value
    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val TAG = "ISaverM9"
        const val ROOT = "/data/local/tmp/isaver-test/m9-stability"
        const val LARGE_DIRECTORY = "$ROOT/entries-10000"
        const val LARGE_FILE = "$ROOT/large-sparse.bin"
        const val LARGE_FILE_BYTES = 512L * 1024L * 1024L
        const val LARGE_FILE_TAIL_OFFSET = LARGE_FILE_BYTES - 16L
    }
}
