package com.iamxpp.isaver.export

import org.junit.Assert.assertEquals
import org.junit.Test

class MimeResolverTest {
    private val resolver = MimeResolver(
        platformLookup = { extension ->
            mapOf(
                "pdf" to "application/pdf",
                "jpg" to "image/jpeg",
            )[extension]
        },
    )

    @Test
    fun `resolves common and compound extensions without case sensitivity`() {
        assertEquals("application/pdf", resolver.resolve("Report.PDF"))
        assertEquals("image/jpeg", resolver.resolve("photo.JPG"))
        assertEquals("application/vnd.android.package-archive", resolver.resolve("release.APK"))
        assertEquals("application/gzip", resolver.resolve("backup.TAR.GZ"))
    }

    @Test
    fun `unknown or missing extensions use binary fallback`() {
        assertEquals("application/octet-stream", resolver.resolve("README"))
        assertEquals("application/octet-stream", resolver.resolve("payload.unknown"))
        assertEquals("application/octet-stream", resolver.resolve("payload."))
    }

    @Test
    fun `trusted file signatures override misleading extensions`() {
        assertEquals("application/pdf", resolver.resolve("fake.jpg", "%PDF-1.7".toByteArray()))
        assertEquals(
            "image/png",
            resolver.resolve("fake.txt", byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)),
        )
        assertEquals("image/jpeg", resolver.resolve("fake.bin", byteArrayOf(-1, -40, -1, -32)))
        assertEquals("application/gzip", resolver.resolve("fake.bin", byteArrayOf(0x1f, 0x8b.toByte())))
    }

    @Test
    fun `zip signature preserves apk container semantics`() {
        val zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        assertEquals("application/zip", resolver.resolve("archive.bin", zip))
        assertEquals("application/vnd.android.package-archive", resolver.resolve("release.apk", zip))
    }
}
