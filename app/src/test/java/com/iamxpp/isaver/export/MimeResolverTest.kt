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
}
