package com.isaver.filemanager.transfer

import com.isaver.filemanager.data.root.AppCachePath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingStreamRegistryTest {
    private val roots = mutableListOf<File>()

    @Test
    fun `issue creates a sixty second one shot root source`() {
        val registry = registry(now = { 1_000L }, valid = { true })
        val cached = cached()

        val source = registry.issue(cached).getOrThrow()

        assertEquals(
            "content://com.isaver.filemanager.incoming-stream/incoming/${"ab".repeat(32)}",
            source.contentUri,
        )
        assertEquals(4L, source.expectedSizeBytes)
        assertNotNull(registry.consume(source.token, nowMillis = 60_999L))
        assertNull(registry.consume(source.token, nowMillis = 60_999L))

        val expired = registry.issue(cached).getOrThrow()
        assertNull(registry.consume(expired.token, nowMillis = 61_000L))
    }

    @Test
    fun `revoked invalid and raced capabilities never reveal more than one file`() = runBlocking {
        val invalid = registry(valid = { false })
        assertTrue(invalid.issue(cached()).isFailure)

        val revoked = registry(valid = { true })
        val revokedSource = revoked.issue(cached()).getOrThrow()
        revoked.revoke(revokedSource)
        assertNull(revoked.consume(revokedSource.token))

        val raced = registry(valid = { true })
        val racedSource = raced.issue(cached()).getOrThrow()
        val results = List(2) {
            async(Dispatchers.Default) { raced.consume(racedSource.token) }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `token factory must return exactly two hundred fifty six bits`() {
        val registry = registry(
            valid = { true },
            randomBytes = { ByteArray(31) },
        )

        assertTrue(registry.issue(cached()).isFailure)
    }

    @After
    fun cleanFixtures() {
        roots.forEach(File::deleteRecursively)
    }

    private fun registry(
        now: () -> Long = { 0L },
        valid: (CachedIncomingFile) -> Boolean,
        randomBytes: () -> ByteArray = { ByteArray(32) { 0xab.toByte() } },
    ) = IncomingStreamRegistry(
        authority = "com.isaver.filemanager.incoming-stream",
        validate = valid,
        nowMillis = now,
        randomBytes = randomBytes,
    )

    private fun cached(): CachedIncomingFile {
        val root = kotlin.io.path.createTempDirectory("isaver-stream-registry").toFile()
        roots += root
        val file = File(root, "incoming/123e4567-e89b-12d3-a456-426614174000.tmp")
        file.parentFile!!.mkdirs()
        file.writeText("four")
        return CachedIncomingFile(
            file = file,
            sizeBytes = 4L,
            appCachePath = AppCachePath.fromIncomingCacheFile(root, file) { 1L to 2L }
                .getOrThrow(),
        )
    }
}
