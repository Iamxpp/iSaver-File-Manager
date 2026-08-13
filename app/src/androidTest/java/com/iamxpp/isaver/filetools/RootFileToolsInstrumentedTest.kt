package com.iamxpp.isaver.filetools

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import com.iamxpp.isaver.fileops.ChecksumAlgorithm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileToolsInstrumentedTest {
    @Test fun rootHexAndComparisonUseStableTypedRanges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(ROOT)}")
        try {
            root(app, "dd if=/dev/zero of=${quote(LEFT)} bs=4096 count=1; printf 'LEFT' >> ${quote(LEFT)}")
            root(app, "dd if=/dev/zero of=${quote(RIGHT)} bs=4096 count=1; printf 'RIGH' >> ${quote(RIGHT)}")
            val left = (app.rootFileSystem.stat(path(LEFT)) as OperationResult.Success).value
            val right = (app.rootFileSystem.stat(path(RIGHT)) as OperationResult.Success).value

            val page = app.hexViewerRepository.loadPage(left, 4096)
            assertTrue(page.toString(), page is OperationResult.Success)
            page as OperationResult.Success<HexPage>
            assertEquals(4096L, page.value.offset)
            assertEquals("4C 45 46 54", page.value.rows.single().hex)

            val content = app.fileComparisonRepository.compareContent(left, right)
            assertTrue(content.toString(), content is OperationResult.Success)
            assertEquals(4096L, ((content as OperationResult.Success).value as ContentComparison.DifferentContent).firstDifferenceOffset)

            val checksums = app.fileComparisonRepository.compareChecksums(left, right, ChecksumAlgorithm.SHA256)
            assertTrue(checksums.toString(), checksums is OperationResult.Success)
            checksums as OperationResult.Success<ChecksumComparison>
            assertFalse(checksums.value.identical)
            assertEquals(root(app, "sha256sum -- ${quote(LEFT)}").substringBefore(' '), checksums.value.leftDigest)
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
        const val ROOT = "/data/local/tmp/isaver-test/file-tools"
        const val LEFT = "$ROOT/left.bin"
        const val RIGHT = "$ROOT/right.bin"
    }
}
