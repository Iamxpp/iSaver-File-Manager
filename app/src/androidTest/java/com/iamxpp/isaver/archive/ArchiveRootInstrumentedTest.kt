package com.iamxpp.isaver.archive

import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.data.root.LibsuRootSession
import com.iamxpp.isaver.data.root.RootCommandCodec
import com.iamxpp.isaver.data.root.AppCachePath
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.RootStatus
import com.iamxpp.isaver.transfer.CachedIncomingFile
import com.iamxpp.isaver.transfer.OutputNameDraft
import com.iamxpp.isaver.transfer.TransferState
import java.io.File
import java.util.Base64
import java.util.UUID
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRootInstrumentedTest {
    @Test
    fun rootZipCreateInspectAndExtractLeavesNoArchiveStages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        val archiveRepository = testArchiveRepository(app)
        assertDeviceHasRoot(app)
        resetTarget(app)
        try {
            val sourceDir = "$TARGET/source"
            root(app, "mkdir -p -- ${quote(sourceDir)} ${quote("$sourceDir/empty")}")
            root(app, "printf %s alpha > ${quote("$sourceDir/alpha.txt")}")
            root(app, "printf %s beta > ${quote("$sourceDir/beta.txt")}")

            val source = path("$TARGET/source")
            val snapshot = (app.rootFileSystem.readDirectory(source) as com.iamxpp.isaver.domain.OperationResult.Success).value
            val created = archiveRepository.createZip(
                sources = snapshot.entries,
                targetDirectory = path(TARGET),
                outputName = OutputNameDraft("bundle", "zip"),
            ).last()
            assertTrue("ZIP creation failed: $created", created is ArchiveState.Success)

            val zipPath = path("$TARGET/bundle.zip")
            val listing = archiveRepository.inspect(zipPath)
            assertTrue("ZIP inspect failed: $listing", listing is com.iamxpp.isaver.domain.OperationResult.Success)
            listing as com.iamxpp.isaver.domain.OperationResult.Success
            assertEquals(3, (listing.value as ArchiveListing).entries.size)

            root(app, "mkdir -p -- ${quote("$TARGET/extracted")}")
            val extractTarget = path("$TARGET/extracted")
            val extracted = archiveRepository.extract(zipPath, extractTarget).last()
            assertTrue("ZIP extract failed: $extracted", extracted is ArchiveState.Success)
            assertEquals("alpha", root(app, "cat -- ${quote("$TARGET/extracted/bundle/alpha.txt")}"))
            assertEquals("beta", root(app, "cat -- ${quote("$TARGET/extracted/bundle/beta.txt")}"))
            assertEquals("directory", root(app, "test -d ${quote("$TARGET/extracted/bundle/empty")} && echo directory"))

            listOf(
                ArchiveFormat.TAR to OutputNameDraft("created-tar", "tar"),
                ArchiveFormat.TAR_GZ to OutputNameDraft("created-tar-gz", "tar.gz"),
                ArchiveFormat.SEVEN_Z to OutputNameDraft("created-seven", "7z"),
            ).forEach { (format, outputName) ->
                val state = archiveRepository.createArchive(
                    sources = snapshot.entries,
                    targetDirectory = path(TARGET),
                    outputName = outputName,
                    format = format,
                ).last()
                assertTrue("$format creation failed: $state", state is ArchiveState.Success)
                val createdPath = path("$TARGET/${outputName.toEntryName().getOrThrow().value}")
                val inspected = archiveRepository.inspect(createdPath)
                assertTrue("$format created archive inspect failed: $inspected", inspected is com.iamxpp.isaver.domain.OperationResult.Success)
                inspected as com.iamxpp.isaver.domain.OperationResult.Success
                assertEquals(format, (inspected.value as ArchiveListing).format)
                assertEquals(listOf("alpha.txt", "beta.txt", "empty"), inspected.value.entries.map { it.path }.sorted())
            }

            root(app, "mkdir -p -- ${quote("$TARGET/formats")}")
            val fixtures = createAdditionalFixtures(app)
            fixtures.forEach { fixture -> publishFixture(app, fixture) }
            val expectedFormats = listOf(
                "plain.tar" to ArchiveFormat.TAR,
                "gzip.tgz" to ArchiveFormat.TAR_GZ,
                "seven.7z" to ArchiveFormat.SEVEN_Z,
                "rar.rar" to ArchiveFormat.RAR,
            )
            expectedFormats.forEach { (name, format) ->
                val fixturePath = path("$TARGET/$name")
                val inspected = archiveRepository.inspect(fixturePath)
                assertTrue("$format inspect failed: $inspected", inspected is com.iamxpp.isaver.domain.OperationResult.Success)
                inspected as com.iamxpp.isaver.domain.OperationResult.Success
                assertEquals(format, (inspected.value as ArchiveListing).format)
                val extraction = archiveRepository.extract(fixturePath, path("$TARGET/formats")).last()
                assertTrue("$format extract failed: $extraction", extraction is ArchiveState.Success)
            }
            root(app, "mkdir -p -- ${quote("$TARGET/cancelled")}")
            archiveRepository.extract(zipPath, path("$TARGET/cancelled")).first { state ->
                state is ArchiveState.Running && state.progress is ArchiveProgress.Publishing
            }
            root(app, "test ! -e ${quote("$TARGET/cancelled/bundle")}")
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

    private fun testArchiveRepository(app: ISaverApplication) = ArchiveRepository(
        rootFileSystem = app.rootFileSystem,
        localEngine = LocalArchiveEngine(),
        cacheDir = app.cacheDir,
        publish = { cached, outputName, target ->
            app.transferRepository.transfer(cached, outputName, target)
        },
        issueSource = { cached ->
            app.incomingStreamRegistry.issue(cached).fold(
                onSuccess = { com.iamxpp.isaver.domain.OperationResult.Success(it) },
                onFailure = {
                    com.iamxpp.isaver.domain.OperationResult.Failure(
                        com.iamxpp.isaver.domain.ErrorCode.SOURCE_UNREADABLE,
                        "无法读取测试夹具",
                    )
                },
            )
        },
        revokeSource = app.incomingStreamRegistry::revoke,
    )

    private fun createAdditionalFixtures(app: ISaverApplication): List<Fixture> {
        val incoming = File(app.cacheDir, "incoming").apply { mkdirs() }
        fun incomingFile() = File(incoming, "${UUID.randomUUID()}.tmp")
        val tar = incomingFile()
        TarArchiveOutputStream(tar.outputStream()).use { output ->
            val bytes = "tar payload".toByteArray()
            output.putArchiveEntry(TarArchiveEntry("docs/plain.txt").apply { size = bytes.size.toLong() })
            output.write(bytes)
            output.closeArchiveEntry()
            output.finish()
        }
        val gzip = incomingFile()
        GzipCompressorOutputStream(gzip.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { output ->
                val bytes = "gzip payload".toByteArray()
                output.putArchiveEntry(TarArchiveEntry("docs/gzip.txt").apply { size = bytes.size.toLong() })
                output.write(bytes)
                output.closeArchiveEntry()
                output.finish()
            }
        }
        val sevenSource = File(app.cacheDir, "seven-source.txt").apply { writeText("seven payload") }
        val seven = incomingFile()
        SevenZOutputFile(seven).use { output ->
            output.putArchiveEntry(output.createArchiveEntry(sevenSource, "docs/seven.txt"))
            output.write(sevenSource.readBytes())
            output.closeArchiveEntry()
            output.finish()
        }
        sevenSource.delete()
        val rar = incomingFile().apply {
            writeBytes(Base64.getDecoder().decode(JUNRAR_TEST_RAR_BASE64))
        }
        return listOf(
            Fixture(tar, "plain.tar"),
            Fixture(gzip, "gzip.tgz"),
            Fixture(seven, "seven.7z"),
            Fixture(rar, "rar.rar"),
        )
    }

    private suspend fun publishFixture(app: ISaverApplication, fixture: Fixture) {
        val file = fixture.file
        val cached = CachedIncomingFile(
            file,
            file.length(),
            AppCachePath.fromIncomingCacheFile(app.cacheDir, file).getOrThrow(),
        )
        val state = app.transferRepository.transfer(
            cached,
            OutputNameDraft.fromDisplayName(fixture.outputName),
            path(TARGET),
        ).last()
        assertTrue("Fixture publish failed for ${fixture.outputName}: $state", state is TransferState.Success)
    }

    private data class Fixture(val file: File, val outputName: String)

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
        const val JUNRAR_TEST_RAR_BASE64 = "UmFyIRoHAM+QcwAADQAAAAAAAAB8zXQgkC0ADQAAAAQAAAAD4Tl7zCeTJEEdMwsAtIEAAGZvb1xiYXIudHh0AMAACL8IrvLDGH6f/ZLdiiN04IAjAAAAAAAAAAAAAwAAAAAnkyRBFDADAP1BAABmb2/EPXsAQAcA"
    }
}
