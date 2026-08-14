package com.isaver.filemanager.archive

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArchiveEngineTest {
    @Test
    fun `creates inspects and extracts zip with nested files`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-engine").toFile()
        val source = File(root, "source.txt").apply { writeText("archive payload") }
        val archive = File(root, "output.zip")
        val extracted = File(root, "extracted")
        try {
            val engine = LocalArchiveEngine()
            engine.createZip(
                listOf(LocalArchiveSource("docs/source.txt", source)),
                archive,
            ).getOrThrow()

            val listing = engine.inspect(archive).getOrThrow()
            assertEquals(ArchiveFormat.ZIP, listing.format)
            assertEquals(listOf("docs/source.txt"), listing.entries.map { it.path })

            engine.extract(archive, extracted).getOrThrow()
            assertEquals("archive payload", File(extracted, "docs/source.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal and absolute zip entries before extraction`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-malicious").toFile()
        val archive = File(root, "malicious.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            listOf("../escape.txt", "/absolute.txt").forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write("bad".toByteArray())
                output.closeEntry()
            }
        }
        try {
            val result = LocalArchiveEngine().inspect(archive)
            assertTrue(result.isFailure)
            assertFalse(File(root, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `enforces entry and expanded size limits`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-limits").toFile()
        val archive = File(root, "many.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            repeat(2) { index ->
                output.putNextEntry(ZipEntry("$index.txt"))
                output.write("payload".toByteArray())
                output.closeEntry()
            }
        }
        try {
            val result = LocalArchiveEngine(ArchiveLimits(maxEntries = 1)).inspect(archive)
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation removes private output`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-cancel").toFile()
        val source = File(root, "source.txt").apply { writeText("payload") }
        val output = File(root, "cancelled.zip")
        try {
            try {
                LocalArchiveEngine().createZip(
                    listOf(LocalArchiveSource("source.txt", source)),
                    output,
                    onProgress = { throw CancellationException("test cancellation") },
                ).getOrThrow()
            } catch (_: CancellationException) {
                // Expected cancellation.
            }
            assertFalse(output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inspects tar and tar gz`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-tar").toFile()
        val tar = File(root, "sample.tar")
        TarArchiveOutputStream(tar.outputStream()).use { output ->
            val bytes = "tar payload".toByteArray()
            output.putArchiveEntry(TarArchiveEntry("docs/readme.txt").apply { size = bytes.size.toLong() })
            output.write(bytes)
            output.closeArchiveEntry()
            output.finish()
        }
        val tarGz = File(root, "sample.tar.gz")
        GzipCompressorOutputStream(tarGz.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { output ->
                val bytes = "tar gz payload".toByteArray()
                output.putArchiveEntry(TarArchiveEntry("docs/gz.txt").apply { size = bytes.size.toLong() })
                output.write(bytes)
                output.closeArchiveEntry()
                output.finish()
            }
        }
        try {
            val engine = LocalArchiveEngine()
            assertEquals(ArchiveFormat.TAR, engine.inspect(tar).getOrThrow().format)
            assertEquals(ArchiveFormat.TAR_GZ, engine.inspect(tarGz).getOrThrow().format)
            val tarOutput = File(root, "tar-output")
            val gzipOutput = File(root, "gzip-output")
            engine.extract(tar, tarOutput).getOrThrow()
            engine.extract(tarGz, gzipOutput).getOrThrow()
            assertEquals("tar payload", File(tarOutput, "docs/readme.txt").readText())
            assertEquals("tar gz payload", File(gzipOutput, "docs/gz.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inspects and extracts seven zip`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-seven").toFile()
        val source = File(root, "seven.txt").apply { writeText("seven payload") }
        val archive = File(root, "sample.7z")
        SevenZOutputFile(archive).use { output ->
            output.putArchiveEntry(output.createArchiveEntry(source, "docs/seven.txt"))
            output.write(source.readBytes())
            output.closeArchiveEntry()
            output.finish()
        }
        try {
            val engine = LocalArchiveEngine()
            val listing = engine.inspect(archive).getOrThrow()
            assertEquals(ArchiveFormat.SEVEN_Z, listing.format)
            assertEquals(listOf("docs/seven.txt"), listing.entries.map { it.path })
            val destination = File(root, "extracted")
            engine.extract(archive, destination).getOrThrow()
            assertEquals("seven payload", File(destination, "docs/seven.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `creates tar tar gz and seven zip from the same regular file sources`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-create-formats").toFile()
        val source = File(root, "source.txt").apply { writeText("format payload") }
        try {
            val engine = LocalArchiveEngine()
            listOf(
                ArchiveFormat.TAR to File(root, "created.tar"),
                ArchiveFormat.TAR_GZ to File(root, "created.tar.gz"),
                ArchiveFormat.SEVEN_Z to File(root, "created.7z"),
            ).forEach { (format, output) ->
                val summary = engine.createArchive(
                    listOf(LocalArchiveSource("docs/source.txt", source)),
                    format,
                    output,
                ).getOrThrow()
                assertEquals(format, summary.format)
                assertEquals(listOf("docs/source.txt"), engine.inspect(output).getOrThrow().entries.map { it.path })
                val extracted = File(root, "${format.name.lowercase()}-extracted")
                engine.extract(output, extracted).getOrThrow()
                assertEquals("format payload", File(extracted, "docs/source.txt").readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `preserves empty directories in every creation format`() = runBlocking {
        val root = Files.createTempDirectory("isaver-archive-empty-directories").toFile()
        try {
            val engine = LocalArchiveEngine()
            listOf(
                ArchiveFormat.ZIP to File(root, "empty.zip"),
                ArchiveFormat.TAR to File(root, "empty.tar"),
                ArchiveFormat.TAR_GZ to File(root, "empty.tar.gz"),
                ArchiveFormat.SEVEN_Z to File(root, "empty.7z"),
            ).forEach { (format, output) ->
                engine.createArchive(
                    listOf(LocalArchiveSource("folder/empty", directory = true)),
                    format,
                    output,
                ).getOrThrow()
                val listing = engine.inspect(output).getOrThrow()
                assertEquals(listOf("folder/empty"), listing.entries.map { it.path })
                assertTrue(listing.entries.single().directory)
                val extracted = File(root, "${format.name.lowercase()}-empty")
                engine.extract(output, extracted).getOrThrow()
                assertTrue(File(extracted, "folder/empty").isDirectory)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
