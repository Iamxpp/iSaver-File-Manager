package com.iamxpp.isaver.fileops

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootChecksumInstrumentedTest {
    @Test fun sha256MatchesIndependentDeviceDigest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(ROOT)}")
        try {
            root(app, "printf %s iSaver-checksum > ${quote(FILE)}")
            val entry = app.rootFileSystem.stat(path(FILE)) as OperationResult.Success
            val expected = root(app, "sha256sum -- ${quote(FILE)}").substringBefore(' ')

            val actual = app.fileChecksumRepository.sha256(entry.value)

            assertTrue(actual.toString(), actual is OperationResult.Success)
            assertEquals(expected, (actual as OperationResult.Success).value)
        } finally {
            root(app, "rm -rf -- ${quote(ROOT)}")
        }
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val result = (app.rootSession as LibsuRootSession).shellCoordinator.execute(command)
        assertEquals(result.stderr.joinToString("\n"), 0, result.exitCode)
        return result.stdout.joinToString("\n")
    }
    private fun path(value: String) = RootPath.parse(value).getOrThrow()
    private fun quote(value: String) = RootCommandCodec.quote(value)

    private companion object {
        const val ROOT = "/data/local/tmp/isaver-test/checksum"
        const val FILE = "$ROOT/value.txt"
    }
}
