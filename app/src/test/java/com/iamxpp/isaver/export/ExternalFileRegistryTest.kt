package com.iamxpp.isaver.export

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalFileRegistryTest {
    private val roots = mutableListOf<File>()

    @Test
    fun `issued grant is opaque expires and can be consumed only once`() {
        val discarded = mutableListOf<CachedExportFile>()
        val registry = registry(now = { 1_000L }, onDiscard = discarded::add)
        val cached = cached()

        val grant = registry.issue(cached).getOrThrow()

        assertEquals(
            "content://com.iamxpp.isaver.external-file/file/${"ab".repeat(32)}",
            grant.contentUri.toString(),
        )
        assertEquals("report.pdf", grant.displayName)
        assertEquals("application/pdf", grant.mimeType)
        assertSame(cached, registry.peek(grant.token, nowMillis = 60_999L))
        assertSame(cached, registry.consume(grant.token, nowMillis = 60_999L))
        assertNull(registry.consume(grant.token, nowMillis = 60_999L))
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun `expired invalid revoked and raced grants cannot reveal a cache file`() = runBlocking {
        val discarded = mutableListOf<CachedExportFile>()
        val registry = registry(now = { 0L }, onDiscard = discarded::add)
        val cached = cached()

        val expired = registry.issue(cached).getOrThrow()
        assertNull(registry.peek(expired.token, nowMillis = 60_000L))
        assertEquals(listOf(cached), discarded)

        val revoked = registry.issue(cached()).getOrThrow()
        registry.revoke(revoked.token)
        assertNull(registry.consume(revoked.token))

        val raced = registry.issue(cached()).getOrThrow()
        val results = List(2) {
            async(Dispatchers.Default) { registry.consume(raced.token) }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `invalid cache and short token source are rejected`() {
        val invalid = ExternalFileRegistry(
            authority = "com.iamxpp.isaver.external-file",
            validate = { false },
            onDiscard = {},
        )
        assertTrue(invalid.issue(cached()).isFailure)

        val shortToken = ExternalFileRegistry(
            authority = "com.iamxpp.isaver.external-file",
            validate = { true },
            onDiscard = {},
            randomBytes = { ByteArray(31) },
        )
        assertTrue(shortToken.issue(cached()).isFailure)
    }

    @Test
    fun `share grant uses a bounded thirty minute ttl and scheduled cleanup`() {
        var now = 2_000L
        var scheduledToken: String? = null
        var scheduledDelay: Long? = null
        var scheduledCleanup: (() -> Unit)? = null
        val discarded = mutableListOf<CachedExportFile>()
        val cached = cached()
        val registry = ExternalFileRegistry(
            authority = "com.iamxpp.isaver.external-file",
            validate = { true },
            onDiscard = discarded::add,
            nowMillis = { now },
            randomBytes = { ByteArray(32) { 0xcd.toByte() } },
            scheduleExpiry = { token, delayMillis, cleanup ->
                scheduledToken = token
                scheduledDelay = delayMillis
                scheduledCleanup = cleanup
            },
        )

        assertTrue(registry.issue(cached, ttlMillis = 0L).isFailure)
        val grant = registry.issue(
            cached,
            ttlMillis = ExternalFileRegistry.SHARE_TTL_MILLIS,
        ).getOrThrow()

        assertEquals(grant.token, scheduledToken)
        assertEquals(ExternalFileRegistry.SHARE_TTL_MILLIS, scheduledDelay)
        assertSame(cached, registry.peek(grant.token, now + ExternalFileRegistry.SHARE_TTL_MILLIS - 1L))

        now += ExternalFileRegistry.SHARE_TTL_MILLIS
        scheduledCleanup!!.invoke()

        assertNull(registry.peek(grant.token))
        assertEquals(listOf(cached), discarded)
        assertTrue(
            registry.issue(
                cached(),
                ttlMillis = ExternalFileRegistry.MAX_TTL_MILLIS + 1L,
            ).isFailure,
        )
    }

    @After
    fun cleanup() {
        roots.forEach(File::deleteRecursively)
    }

    private fun registry(
        now: () -> Long = { 0L },
        onDiscard: (CachedExportFile) -> Unit,
    ) = ExternalFileRegistry(
        authority = "com.iamxpp.isaver.external-file",
        validate = { true },
        onDiscard = onDiscard,
        nowMillis = now,
        randomBytes = { ByteArray(32) { 0xab.toByte() } },
    )

    private fun cached(): CachedExportFile {
        val root = Files.createTempDirectory("isaver-export-registry").toFile()
        roots += root
        val file = File(root, "export/123e4567-e89b-12d3-a456-426614174000.export")
        file.parentFile!!.mkdirs()
        file.writeText("four")
        return CachedExportFile(
            file = file,
            sizeBytes = 4L,
            device = 1L,
            inode = 2L,
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )
    }
}
