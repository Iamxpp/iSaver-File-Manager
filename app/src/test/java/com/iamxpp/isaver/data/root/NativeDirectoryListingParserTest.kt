package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.RootPath
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDirectoryListingParserTest {
    @Test
    fun `parses the exact V1 parent header`() {
        val result = parse(
            listOf("ISAVER_LIST_V1\t259\t1024\t1\t0"),
        )

        assertTrue(result is NativeDirectoryListingParseResult.Success)
        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(259L, snapshot.parentDevice)
        assertEquals(1024L, snapshot.parentInode)
        assertTrue(snapshot.parentReadable)
        assertEquals(false, snapshot.parentWritable)
        assertEquals(emptyList<Any>(), snapshot.entries)
    }

    @Test
    fun `rejects unknown protocol versions`() {
        assertFatal(
            lines = listOf("ISAVER_LIST_V2\t259\t1024\t1\t0"),
            reason = NativeDirectoryListingProtocolFailure.MALFORMED_HEADER,
        )
    }

    @Test
    fun `rejects a V1 header with extra fields`() {
        assertFatal(
            lines = listOf("ISAVER_LIST_V1\t259\t1024\t1\t0\textra"),
            reason = NativeDirectoryListingProtocolFailure.MALFORMED_HEADER,
        )
    }

    @Test
    fun `rejects non binary parent capabilities`() {
        assertFatal(
            lines = listOf("ISAVER_LIST_V1\t259\t1024\ttrue\t0"),
            reason = NativeDirectoryListingProtocolFailure.MALFORMED_HEADER,
        )
    }

    @Test
    fun `rejects negative parent identity values`() {
        assertFatal(
            lines = listOf("ISAVER_LIST_V1\t-1\t1024\t1\t0"),
            reason = NativeDirectoryListingProtocolFailure.MALFORMED_HEADER,
        )
    }

    @Test
    fun `parses directory file other and symlink records`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t259\t1024\t1\t1",
                record("folder", "/parent/folder", "directory", "-", "1700000000", "1", "1", "0"),
                record("file.pdf", "/parent/file.pdf", "file", "42", "1700000001", "1", "0", "0"),
                record("pipe", "/parent/pipe", "other", "-", "-", "0", "0", "0"),
                record("link", "/parent/link", "other", "-", "1700000002", "1", "1", "1"),
            ),
            expectedParent = "/parent",
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(
            listOf(EntryType.DIRECTORY, EntryType.FILE, EntryType.OTHER, EntryType.OTHER),
            snapshot.entries.map { it.type },
        )
        assertEquals(listOf(null, 42L, null, null), snapshot.entries.map { it.sizeBytes })
        assertEquals(listOf(1700000000L, 1700000001L, null, 1700000002L), snapshot.entries.map { it.modifiedAtEpochSeconds })
        assertEquals(listOf(true, true, false, true), snapshot.entries.map { it.readable })
        assertEquals(listOf(true, false, false, true), snapshot.entries.map { it.writable })
        assertEquals(listOf(false, false, false, true), snapshot.entries.map { it.symbolicLink })
    }

    @Test
    fun `round trips spaces Chinese quotes and embedded newlines`() {
        val names = listOf(
            "with space",
            "中文文件",
            "'single' and \"double\"",
            "line one\nline two",
            "-leading",
        )
        val result = parse(
            listOf("ISAVER_LIST_V1\t1\t2\t1\t1") + names.map { name ->
                record(name, "/parent/$name", "file", "0", "0", "1", "1", "0")
            },
            expectedParent = "/parent",
        )

        val entries = (result as NativeDirectoryListingParseResult.Success).snapshot.entries
        assertEquals(names, entries.map { it.name })
        assertEquals(names.map { "/parent/$it" }, entries.map { it.path.value })
    }

    @Test
    fun `invalid UTF8 rejects only its record and keeps valid siblings`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("before", "/before", "file", "1", "2", "1", "1", "0"),
                rawRecord("/w==", b64("/invalid"), "file", "1", "2", "1", "1", "0"),
                record("after", "/after", "file", "1", "2", "1", "1", "0"),
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(listOf("before", "after"), snapshot.entries.map { it.name })
        assertEquals(
            listOf(
                NativeDirectoryListingRecordFailure(
                    recordIndex = 1,
                    reason = NativeDirectoryListingRecordFailureReason.INVALID_UTF8,
                ),
            ),
            snapshot.recordFailures,
        )
    }

    @Test
    fun `malformed Base64 is a typed record failure`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                rawRecord("***", b64("/invalid"), "file", "1", "2", "1", "1", "0"),
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            NativeDirectoryListingRecordFailureReason.INVALID_BASE64,
            snapshot.recordFailures.single().reason,
        )
    }

    @Test
    fun `record field count must be exactly eight`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("valid", "/valid", "file", "1", "2", "1", "1", "0") + "\textra",
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            NativeDirectoryListingRecordFailureReason.INVALID_FIELD_COUNT,
            snapshot.recordFailures.single().reason,
        )
    }

    @Test
    fun `unknown entry type is a typed record failure`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("socket", "/socket", "socket", "-", "2", "1", "1", "0"),
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            NativeDirectoryListingRecordFailureReason.UNKNOWN_TYPE,
            snapshot.recordFailures.single().reason,
        )
    }

    @Test
    fun `invalid absolute path is a typed record failure`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("relative", "relative/path", "file", "1", "2", "1", "1", "0"),
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            NativeDirectoryListingRecordFailureReason.INVALID_PATH,
            snapshot.recordFailures.single().reason,
        )
    }

    @Test
    fun `invalid numeric and boolean fields have distinct typed failures`() {
        val result = parse(
            listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("negative", "/negative", "file", "-1", "2", "1", "1", "0"),
                record("number", "/number", "file", "NaN", "2", "1", "1", "0"),
                record("boolean", "/boolean", "file", "1", "2", "true", "1", "0"),
            ),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            listOf(
                NativeDirectoryListingRecordFailureReason.INVALID_NUMBER,
                NativeDirectoryListingRecordFailureReason.INVALID_NUMBER,
                NativeDirectoryListingRecordFailureReason.INVALID_BOOLEAN,
            ),
            snapshot.recordFailures.map { it.reason },
        )
    }

    @Test
    fun `production limits match the V1 protocol contract`() {
        val limits = NativeDirectoryListingLimits()

        assertEquals(100_000, limits.maxRecordCount)
        assertEquals(1_048_576, limits.maxFieldBytes)
        assertEquals(67_108_864L, limits.maxProtocolBytes)
    }

    @Test
    fun `too many records is a fatal protocol failure`() {
        val exact = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("one", "/one", "file", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(maxRecordCount = 1),
        )
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("one", "/one", "file", "1", "2", "1", "1", "0"),
                record("two", "/two", "file", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(maxRecordCount = 1),
        )

        assertEquals(listOf("one"), success(exact).entries.map { it.name })
        assertFatal(result, NativeDirectoryListingProtocolFailure.TOO_MANY_RECORDS)
    }

    @Test
    fun `protocol byte limit is fatal`() {
        val header = "ISAVER_LIST_V1\t1\t2\t1\t1"
        val result = parse(
            lines = listOf(header),
            limits = NativeDirectoryListingLimits(
                maxProtocolBytes = header.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        )

        assertFatal(result, NativeDirectoryListingProtocolFailure.PROTOCOL_TOO_LARGE)
    }

    @Test
    fun `oversized record field is rejected without losing valid siblings`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("before", "/before", "file", "1", "2", "1", "1", "0"),
                rawRecord("A".repeat(200_000), b64("/large"), "file", "1", "2", "1", "1", "0"),
                record("after", "/after", "file", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(maxFieldBytes = 16),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(listOf("before", "after"), snapshot.entries.map { it.name })
        assertEquals(
            NativeDirectoryListingRecordFailureReason.FIELD_TOO_LARGE,
            snapshot.recordFailures.single().reason,
        )
        assertEquals(1, snapshot.recordFailures.single().recordIndex)
    }

    @Test
    fun `record exactly at configured field limit is accepted`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                rawRecord(b64("1234567890"), b64("/1234567890"), "file", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(maxFieldBytes = 16),
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(listOf("1234567890"), snapshot.entries.map { it.name })
        assertEquals(emptyList<Any>(), snapshot.recordFailures)
    }

    @Test
    fun `delimiter bomb is one bounded field count failure`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("before", "/parent/before", "file", "1", "2", "1", "1", "0"),
                record("bomb", "/parent/bomb", "file", "1", "2", "1", "1", "0") +
                    "\t".repeat(200_000),
                record("after", "/parent/after", "file", "1", "2", "1", "1", "0"),
            ),
            expectedParent = "/parent",
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(listOf("before", "after"), snapshot.entries.map { it.name })
        assertEquals(
            NativeDirectoryListingRecordFailureReason.INVALID_FIELD_COUNT,
            snapshot.recordFailures.single().reason,
        )
        assertEquals(1, snapshot.recordFailures.single().recordIndex)
    }

    @Test
    fun `empty dot slash and NUL names are rejected before path binding`() {
        val invalidNames = listOf("", ".", "..", "has/slash", "nul\u0000name")
        val result = parse(
            lines = listOf("ISAVER_LIST_V1\t1\t2\t1\t1") + invalidNames.map { name ->
                record(name, "/parent/$name", "file", "1", "2", "1", "1", "0")
            },
            expectedParent = "/parent",
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            List(invalidNames.size) { NativeDirectoryListingRecordFailureReason.INVALID_NAME },
            snapshot.recordFailures.map { it.reason },
        )
    }

    @Test
    fun `decoded paths must bind name to expected parent directly`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("file", "/parent/other", "file", "1", "2", "1", "1", "0"),
                record("file", "/other/file", "file", "1", "2", "1", "1", "0"),
                record("file", "/parent/sub/file", "file", "1", "2", "1", "1", "0"),
            ),
            expectedParent = "/parent",
        )

        val snapshot = (result as NativeDirectoryListingParseResult.Success).snapshot
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            List(3) { NativeDirectoryListingRecordFailureReason.PATH_MISMATCH },
            snapshot.recordFailures.map { it.reason },
        )
    }

    @Test
    fun `root and trailing slash parents accept their exact direct children`() {
        val rootResult = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("etc", "/etc", "directory", "-", "2", "1", "1", "0"),
            ),
            expectedParent = "/",
        )
        val trailingResult = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("file", "/parent/file", "file", "1", "2", "1", "1", "0"),
            ),
            expectedParent = "/parent/",
        )

        assertEquals("/etc", success(rootResult).entries.single().path.value)
        assertEquals("/parent/file", success(trailingResult).entries.single().path.value)
    }

    @Test
    fun `Base64 fields must use canonical padded encoding`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                rawRecord("YQ", b64("/a"), "file", "1", "2", "1", "1", "0"),
                rawRecord("YR==", b64("/a"), "file", "1", "2", "1", "1", "0"),
            ),
            expectedParent = "/",
        )

        val snapshot = success(result)
        assertEquals(emptyList<Any>(), snapshot.entries)
        assertEquals(
            List(2) { NativeDirectoryListingRecordFailureReason.INVALID_BASE64 },
            snapshot.recordFailures.map { it.reason },
        )
    }

    @Test
    fun `protocol byte limit accepts exact size and rejects one byte less`() {
        val header = "ISAVER_LIST_V1\t1\t2\t1\t1"
        val exactBytes = header.toByteArray(Charsets.UTF_8).size.toLong() + 1L

        val exact = parse(
            lines = listOf(header),
            limits = NativeDirectoryListingLimits(maxProtocolBytes = exactBytes),
        )
        val tooSmall = parse(
            lines = listOf(header),
            limits = NativeDirectoryListingLimits(maxProtocolBytes = exactBytes - 1L),
        )

        assertTrue(exact is NativeDirectoryListingParseResult.Success)
        assertFatal(tooSmall, NativeDirectoryListingProtocolFailure.PROTOCOL_TOO_LARGE)
    }

    @Test
    fun `field byte limit counts multibyte UTF8 exactly`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("large", "/large", "中文中文中", "1", "2", "1", "1", "0"),
                record("within", "/within", "中文中文", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(maxFieldBytes = 14),
        )

        val snapshot = success(result)
        assertEquals(
            listOf(
                NativeDirectoryListingRecordFailureReason.FIELD_TOO_LARGE,
                NativeDirectoryListingRecordFailureReason.UNKNOWN_TYPE,
            ),
            snapshot.recordFailures.map { it.reason },
        )
    }

    @Test
    fun `limit arithmetic saturates instead of overflowing`() {
        val result = parse(
            lines = listOf(
                "ISAVER_LIST_V1\t1\t2\t1\t1",
                record("file", "/file", "file", "1", "2", "1", "1", "0"),
            ),
            limits = NativeDirectoryListingLimits(
                maxRecordCount = Int.MAX_VALUE,
                maxFieldBytes = Int.MAX_VALUE,
                maxProtocolBytes = Long.MAX_VALUE,
            ),
        )

        assertEquals(listOf("file"), success(result).entries.map { it.name })
    }

    private fun assertFatal(
        lines: List<String>,
        reason: NativeDirectoryListingProtocolFailure,
    ) {
        val result = parse(lines)
        assertTrue(result is NativeDirectoryListingParseResult.Failure)
        assertEquals(reason, (result as NativeDirectoryListingParseResult.Failure).reason)
    }

    private fun parse(
        lines: List<String>,
        expectedParent: String = "/",
        limits: NativeDirectoryListingLimits = NativeDirectoryListingLimits(),
    ): NativeDirectoryListingParseResult = NativeDirectoryListingParser.parse(
        lines = lines,
        expectedParent = RootPath.parse(expectedParent).getOrThrow(),
        limits = limits,
    )

    private fun success(result: NativeDirectoryListingParseResult): DirectorySnapshot =
        (result as NativeDirectoryListingParseResult.Success).snapshot

    private fun assertFatal(
        result: NativeDirectoryListingParseResult,
        reason: NativeDirectoryListingProtocolFailure,
    ) {
        assertTrue(result is NativeDirectoryListingParseResult.Failure)
        assertEquals(reason, (result as NativeDirectoryListingParseResult.Failure).reason)
    }

    private fun record(
        name: String,
        path: String,
        type: String,
        size: String,
        mtime: String,
        readable: String,
        writable: String,
        symlink: String,
    ): String = rawRecord(
        b64(name),
        b64(path),
        type,
        size,
        mtime,
        readable,
        writable,
        symlink,
    )

    private fun rawRecord(
        encodedName: String,
        encodedPath: String,
        type: String,
        size: String,
        mtime: String,
        readable: String,
        writable: String,
        symlink: String,
    ): String = listOf(
        encodedName,
        encodedPath,
        type,
        size,
        mtime,
        readable,
        writable,
        symlink,
    ).joinToString("\t")

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
