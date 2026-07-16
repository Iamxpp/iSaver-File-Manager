package com.iamxpp.isaver.archive

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import com.iamxpp.isaver.transfer.OutputNameDraft
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRootInstrumentedTest {
    @Test
    fun rootZipCreateInspectAndExtractLeavesNoArchiveStages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertDeviceHasRoot(app)
        resetTarget(app)
        try {
            val sourceDir = "$TARGET/source"
            root(app, "mkdir -p -- ${quote(sourceDir)}")
            root(app, "printf %s alpha > ${quote("$sourceDir/alpha.txt")}")
            root(app, "printf %s beta > ${quote("$sourceDir/beta.txt")}")

            val source = path("$TARGET/source")
            val snapshot = (app.rootFileSystem.readDirectory(source) as com.iamxpp.isaver.domain.OperationResult.Success).value
            val created = app.archiveRepository.createZip(
                sources = snapshot.entries,
                targetDirectory = path(TARGET),
                outputName = OutputNameDraft("bundle", "zip"),
            ).last()
            assertTrue("ZIP creation failed: $created", created is ArchiveState.Success)

            val zipPath = path("$TARGET/bundle.zip")
            val listing = app.archiveRepository.inspect(zipPath)
            assertTrue("ZIP inspect failed: $listing", listing is com.iamxpp.isaver.domain.OperationResult.Success)
            listing as com.iamxpp.isaver.domain.OperationResult.Success
            assertEquals(2, (listing.value as ArchiveListing).entries.size)

            val extractTarget = path("$TARGET/extracted")
            val extracted = app.archiveRepository.extract(zipPath, extractTarget).last()
            assertTrue("ZIP extract failed: $extracted", extracted is ArchiveState.Success)
            assertEquals("alpha", root(app, "cat -- ${quote("$TARGET/extracted/alpha.txt")}"))
            assertEquals("beta", root(app, "cat -- ${quote("$TARGET/extracted/beta.txt")}"))
            assertNoArchiveStages(app)
        } finally {
            resetTarget(app)
        }
    }

    private suspend fun assertDeviceHasRoot(app: ISaverApplication) {
        val status = app.rootSession.check()
        assertEquals(RootStatus.Available, status)
        assertEquals("0", root(app, "id -u"))
    }

    private suspend fun resetTarget(app: ISaverApplication) {
        root(app, "rm -rf -- ${quote(TARGET)}; mkdir -p -- ${quote(TARGET)}")
    }

    private suspend fun assertNoArchiveStages(app: ISaverApplication) {
        assertTrue(
            root(app, "find ${quote(TARGET)} -maxdepth 3 -name '.isaver-*' -print").isBlank(),
        )
    }

    private suspend fun root(app: ISaverApplication, command: String): String {
        val session = app.rootSession as LibsuRootSession
        val result = session.shellCoordinator.execute(command)
        assertEquals(0, result.exitCode)
        return result.stdout.joinToString("\n")
    }

    private fun quote(value: String) = RootCommandCodec.quote(value)
    private fun path(value: String) = RootPath.parse(value).getOrThrow()

    private companion object {
        const val TARGET = "/data/local/tmp/isaver-archive-test"
    }
}
