package com.isaver.filemanager.fileops

import androidx.test.core.app.ApplicationProvider
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.data.root.LibsuRootSession
import com.isaver.filemanager.data.root.RootCommandCodec
import com.isaver.filemanager.data.root.RootFileMetadata
import com.isaver.filemanager.domain.OperationResult
import com.isaver.filemanager.domain.RootPath
import com.isaver.filemanager.domain.RootStatus
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

    @Test fun supportedAlgorithmsMatchIndependentDeviceDigests() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(ROOT)}")
        try {
            root(app, "printf %s iSaver-checksum > ${quote(FILE)}")
            val entry = app.rootFileSystem.stat(path(FILE)) as OperationResult.Success
            val expected = mapOf(
                ChecksumAlgorithm.MD5 to root(app, "md5sum -- ${quote(FILE)}").substringBefore(' '),
                ChecksumAlgorithm.SHA1 to root(app, "sha1sum -- ${quote(FILE)}").substringBefore(' '),
                ChecksumAlgorithm.SHA256 to root(app, "sha256sum -- ${quote(FILE)}").substringBefore(' '),
                ChecksumAlgorithm.SHA512 to root(app, "sha512sum -- ${quote(FILE)}").substringBefore(' '),
            )

            expected.forEach { (algorithm, digest) ->
                val actual = app.fileChecksumRepository.checksum(entry.value, algorithm)
                assertTrue("$algorithm: $actual", actual is OperationResult.Success)
                assertEquals(digest, (actual as OperationResult.Success).value)
            }
        } finally {
            root(app, "rm -rf -- ${quote(ROOT)}")
        }
    }

    @Test fun sparseFileBeyondLegacyLimitStreamsAndReportsExactMetadata() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ISaverApplication>()
        assertEquals(RootStatus.Available, app.rootSession.check())
        root(app, "rm -rf -- ${quote(ROOT)}; mkdir -p -- ${quote(ROOT)}")
        try {
            root(app, "dd if=/dev/zero of=${quote(LARGE_FILE)} bs=1 count=0 seek=$LARGE_SIZE")
            root(app, "chmod 640 ${quote(LARGE_FILE)}")
            val entry = app.rootFileSystem.stat(path(LARGE_FILE)) as OperationResult.Success
            val expectedDigest = root(app, "sha256sum -- ${quote(LARGE_FILE)}").substringBefore(' ')

            val digest = app.fileChecksumRepository.sha256(entry.value)
            val metadata = app.rootFileSystem.metadata(path(LARGE_FILE))
            val expectedMetadata = root(
                app,
                "stat -c '%a:%u:%g:%d:%i' -- ${quote(LARGE_FILE)}",
            ).split(':')

            assertTrue(digest.toString(), digest is OperationResult.Success)
            assertEquals(expectedDigest, (digest as OperationResult.Success).value)
            assertTrue(metadata.toString(), metadata is OperationResult.Success)
            metadata as OperationResult.Success<RootFileMetadata>
            assertEquals(expectedMetadata[0].toInt(8), metadata.value.mode)
            assertEquals(expectedMetadata[1].toLong(), metadata.value.uid)
            assertEquals(expectedMetadata[2].toLong(), metadata.value.gid)
            assertEquals(expectedMetadata[3].toLong(), metadata.value.device)
            assertEquals(expectedMetadata[4].toLong(), metadata.value.inode)
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
        const val LARGE_FILE = "$ROOT/large.bin"
        const val LARGE_SIZE = 268435457
    }
}
