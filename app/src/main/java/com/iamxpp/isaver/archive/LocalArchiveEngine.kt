package com.iamxpp.isaver.archive

import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

class LocalArchiveEngine(
    private val limits: ArchiveLimits = ArchiveLimits(),
) {
    suspend fun createZip(
        sources: List<LocalArchiveSource>,
        output: File,
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveOperationSummary> = withContext(Dispatchers.IO) {
        val temporary = File(
            output.parentFile ?: error("archive output requires a parent"),
            ".isaver-archive-${UUID.randomUUID()}.tmp",
        )
        try {
            require(!output.exists()) { "archive output already exists" }
            require(sources.isNotEmpty()) { "archive source is empty" }
            temporary.parentFile?.mkdirs()
            onProgress(ArchiveProgress.Preparing)
            val names = ArchiveEntryNameSet()
            var expandedBytes = 0L
            ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { zip ->
                sources.forEachIndexed { index, source ->
                    currentCoroutineContext().ensureActive()
                    ArchivePathPolicy.rejectSymbolicLink(
                        source.symbolicLink || Files.isSymbolicLink(source.file.toPath()),
                    ).getOrThrow()
                    val name = names.add(source.relativePath).getOrThrow()
                    require(source.file.isFile) { "archive source is not a regular file" }
                    val size = source.file.length()
                    expandedBytes = Math.addExact(expandedBytes, size)
                    limits.checkEntry(index.toLong() + 1L, size, expandedBytes).getOrThrow()
                    zip.putNextEntry(ZipEntry(name))
                    source.file.inputStream().use { input ->
                        copyBounded(input, zip, size, name, onProgress)
                    }
                    zip.closeEntry()
                }
            }
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            Result.success(ArchiveOperationSummary(ArchiveFormat.ZIP, sources.size.toLong(), expandedBytes))
        } catch (error: Throwable) {
            temporary.delete()
            Result.failure(error)
        }
    }

    suspend fun inspect(archive: File): Result<ArchiveListing> = withContext(Dispatchers.IO) {
        runCatching {
            require(archive.isFile) { "archive is not a regular file" }
            when (val format = detectFormat(archive)) {
                ArchiveFormat.ZIP -> inspectZip(archive, format)
                ArchiveFormat.TAR -> inspectTar(archive, format, archive.inputStream())
                ArchiveFormat.TAR_GZ -> archive.inputStream().use { input ->
                    inspectTar(archive, format, GzipCompressorInputStream(BufferedInputStream(input)))
                }
                ArchiveFormat.SEVEN_Z -> inspectSevenZ(archive, format)
                ArchiveFormat.RAR -> inspectRar(archive, format)
            }
        }
    }

    suspend fun extract(
        archive: File,
        destination: File,
        onProgress: suspend (ArchiveProgress) -> Unit = {},
    ): Result<ArchiveOperationSummary> = withContext(Dispatchers.IO) {
        val staging = File(
            destination.parentFile ?: error("archive destination requires a parent"),
            ".isaver-extract-${UUID.randomUUID()}",
        )
        try {
            require(!destination.exists() || destination.isDirectory) { "archive destination is invalid" }
            require(!Files.isSymbolicLink(destination.toPath())) { "archive destination is symbolic link" }
            staging.mkdirs()
            onProgress(ArchiveProgress.Preparing)
            val summary = when (val format = detectFormat(archive)) {
                ArchiveFormat.ZIP -> extractZip(archive, format, staging, onProgress)
                ArchiveFormat.TAR -> extractTar(archive, format, archive.inputStream(), staging, onProgress)
                ArchiveFormat.TAR_GZ -> archive.inputStream().use { input ->
                    extractTar(
                        archive,
                        format,
                        GzipCompressorInputStream(BufferedInputStream(input)),
                        staging,
                        onProgress,
                    )
                }
                ArchiveFormat.SEVEN_Z -> extractSevenZ(archive, format, staging, onProgress)
                ArchiveFormat.RAR -> extractRar(archive, format, staging, onProgress)
            }
            if (destination.exists()) {
                require(destination.listFiles().isNullOrEmpty()) { "archive destination is not empty" }
                staging.listFiles()?.forEach { child ->
                    child.copyRecursively(File(destination, child.name), overwrite = false)
                }
                staging.deleteRecursively()
            } else {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            Result.success(summary)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            Result.failure(error)
        }
    }

    private fun inspectZip(archive: File, format: ArchiveFormat): ArchiveListing =
        ZipFile(archive).use { zip ->
            val state = ValidationState(limits)
            val entries = buildList {
                val iterator = zip.entries
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    add(validateEntry(state, entry.name, entry.isDirectory, entry.size, entry.compressedSize, isZipSymlink(entry)))
                }
            }
            ArchiveListing(format, entries)
        }

    private fun inspectTar(archive: File, format: ArchiveFormat, input: InputStream): ArchiveListing =
        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            val state = ValidationState(limits)
            val entries = buildList {
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    add(validateEntry(state, entry.name, entry.isDirectory, entry.size, null, entry.isSymbolicLink || entry.isLink))
                }
            }
            ArchiveListing(format, entries)
        }

    private fun inspectSevenZ(archive: File, format: ArchiveFormat): ArchiveListing =
        SevenZFile(archive).use { seven ->
            val state = ValidationState(limits)
            ArchiveListing(
                format,
                seven.entries.map { entry ->
                    validateEntry(state, entry.name, entry.isDirectory, entry.size, null, false)
                }.toList(),
            )
        }

    private fun inspectRar(archive: File, format: ArchiveFormat): ArchiveListing =
        Archive(archive).use { rar ->
            val state = ValidationState(limits)
            ArchiveListing(
                format,
                rar.fileHeaders.map { entry ->
                    validateEntry(state, entry.fileName, entry.isDirectory, entry.fullUnpackSize, entry.fullPackSize, false)
                },
            )
        }

    private suspend fun extractZip(
        archive: File,
        format: ArchiveFormat,
        staging: File,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): ArchiveOperationSummary = ZipFile(archive).use { zip ->
        val state = ValidationState(limits)
        var completed = 0L
        val iterator = zip.entries
        while (iterator.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            val entry = iterator.nextElement()
            val safe = validateEntry(state, entry.name, entry.isDirectory, entry.size, entry.compressedSize, isZipSymlink(entry))
            writeEntry(safe, entry.isDirectory, staging, zip.getInputStream(entry), onProgress)
            completed++
        }
        ArchiveOperationSummary(format, state.count, state.expandedBytes)
    }

    private suspend fun extractTar(
        archive: File,
        format: ArchiveFormat,
        input: InputStream,
        staging: File,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): ArchiveOperationSummary = TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
        val state = ValidationState(limits)
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = tar.nextTarEntry ?: break
            val safe = validateEntry(state, entry.name, entry.isDirectory, entry.size, null, entry.isSymbolicLink || entry.isLink)
            writeEntry(safe, entry.isDirectory, staging, tar, onProgress)
        }
        ArchiveOperationSummary(format, state.count, state.expandedBytes)
    }

    private suspend fun extractSevenZ(
        archive: File,
        format: ArchiveFormat,
        staging: File,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): ArchiveOperationSummary = SevenZFile(archive).use { seven ->
        val state = ValidationState(limits)
        seven.entries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            val safe = validateEntry(state, entry.name, entry.isDirectory, entry.size, null, false)
            writeEntry(safe, entry.isDirectory, staging, seven.getInputStream(entry), onProgress)
        }
        ArchiveOperationSummary(format, state.count, state.expandedBytes)
    }

    private suspend fun extractRar(
        archive: File,
        format: ArchiveFormat,
        staging: File,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ): ArchiveOperationSummary = Archive(archive).use { rar ->
        val state = ValidationState(limits)
        rar.fileHeaders.forEach { entry ->
            currentCoroutineContext().ensureActive()
            val safe = validateEntry(state, entry.fileName, entry.isDirectory, entry.fullUnpackSize, entry.fullPackSize, false)
            writeEntry(safe, entry.isDirectory, staging, rar.getInputStream(entry), onProgress)
        }
        ArchiveOperationSummary(format, state.count, state.expandedBytes)
    }

    private suspend fun writeEntry(
        safePath: ArchiveEntry,
        directory: Boolean,
        staging: File,
        input: InputStream,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ) {
        if (directory) {
            File(staging, safePath.path).mkdirs()
            return
        }
        input.use { source ->
            val target = File(staging, safePath.path)
            target.parentFile?.mkdirs()
            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                "archive entry escaped destination"
            }
            Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                copyBounded(source, output, safePath.sizeBytes ?: Long.MAX_VALUE, safePath.path, onProgress)
            }
        }
    }

    private suspend fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        declaredBytes: Long,
        path: String,
        onProgress: suspend (ArchiveProgress) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            copied = Math.addExact(copied, read.toLong())
            require(copied <= limits.maxEntryBytes) { "archive entry size limit exceeded" }
            output.write(buffer, 0, read)
            onProgress(ArchiveProgress.Entry(path, copied, declaredBytes.takeUnless { it == Long.MAX_VALUE }))
        }
        require(declaredBytes == Long.MAX_VALUE || copied == declaredBytes) { "archive entry size changed" }
    }

    private fun validateEntry(
        state: ValidationState,
        rawPath: String,
        directory: Boolean,
        size: Long,
        compressedSize: Long?,
        symbolicLink: Boolean,
    ): ArchiveEntry {
        val path = ArchivePathPolicy.normalizeRelative(rawPath).getOrThrow()
        ArchivePathPolicy.rejectSymbolicLink(symbolicLink).getOrThrow()
        check(state.names.add(path)) { "duplicate archive entry" }
        state.count++
        state.expandedBytes = Math.addExact(state.expandedBytes, size.coerceAtLeast(0L))
        limits.checkEntry(state.count, size.coerceAtLeast(0L), state.expandedBytes, compressedSize).getOrThrow()
        return ArchiveEntry(path, directory, size.coerceAtLeast(0L), compressedSize)
    }

    private fun detectFormat(archive: File): ArchiveFormat {
        val lower = archive.name.lowercase()
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return ArchiveFormat.TAR_GZ
        if (lower.endsWith(".tar")) return ArchiveFormat.TAR
        if (lower.endsWith(".7z")) return ArchiveFormat.SEVEN_Z
        if (lower.endsWith(".rar")) return ArchiveFormat.RAR
        if (lower.endsWith(".zip")) return ArchiveFormat.ZIP
        val header = archive.inputStream().use { it.readNBytes(512) }
        return when {
            header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> ArchiveFormat.ZIP
            header.startsWith(byteArrayOf(0x37, 0x7a.toByte(), 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) -> ArchiveFormat.SEVEN_Z
            header.size >= 7 && header.copyOf(7).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)) -> ArchiveFormat.RAR
            header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() -> ArchiveFormat.TAR_GZ
            header.size >= 262 && header.copyOfRange(257, 262).decodeToString() == "ustar" -> ArchiveFormat.TAR
            else -> throw IllegalArgumentException("unsupported archive format")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && indices.take(prefix.size).all { this[it] == prefix[it] }

    private fun isZipSymlink(entry: ZipArchiveEntry): Boolean =
        (entry.unixMode and 0xf000) == 0xa000

    private class ValidationState(val limits: ArchiveLimits) {
        val names = mutableSetOf<String>()
        var count = 0L
        var expandedBytes = 0L
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 32 * 1024
    }
}
