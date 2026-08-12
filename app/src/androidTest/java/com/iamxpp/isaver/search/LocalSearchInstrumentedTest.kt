package com.iamxpp.isaver.search

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSearchInstrumentedTest {
    @Test fun searchesRealRootTreeWithoutFollowingSymbolicDirectories() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(CHILD)}")
        try {
            root(app, "printf %s report > ${quote(FILE)}")
            root(app, "ln -s -- ${quote(CHILD)} ${quote(LINK)}")

            val result = LocalSearchRepository(app.rootFileSystem).search(
                RootPath.parse(ROOT).getOrThrow(),
                LocalSearchCriteria("报告.*\\.txt", regularExpression = true, extension = "txt"),
            )

            assertTrue(result.toString(), result is OperationResult.Success)
            result as OperationResult.Success<LocalSearchResult>
            assertEquals(listOf(FILE), result.value.entries.map { it.path.value })
            assertEquals(2, result.value.scannedDirectories)
            assertFalse(result.value.truncated)
        } finally {
            root(app, "rm -rf -- ${quote(ROOT)}")
        }
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT = "/data/local/tmp/isaver-test/search"
        const val CHILD = "$ROOT/中文 空格"
        const val FILE = "$CHILD/报告-final.txt"
        const val LINK = "$ROOT/link"
    }
}
