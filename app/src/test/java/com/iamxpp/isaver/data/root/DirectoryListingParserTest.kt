package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingParserTest {
    @Test
    fun `parses directory file and symlink records`() {
        val result = DirectoryListingParser.parse(
            listOf(
                record("folder", "/parent/folder", "directory", "-", "1700000000", "1", "1", "0"),
                record("file.txt", "/parent/file.txt", "file", "42", "1700000001", "1", "0", "0"),
                record("link", "/parent/link", "other", "-", "-", "1", "1", "1"),
            ),
        )

        val entries = (result as OperationResult.Success).value
        assertEquals(listOf(EntryType.DIRECTORY, EntryType.FILE, EntryType.OTHER), entries.map { it.type })
        assertEquals(listOf(null, 42L, null), entries.map { it.sizeBytes })
        assertEquals(listOf(false, false, true), entries.map { it.symbolicLink })
    }

    @Test
    fun `empty output is an empty successful listing`() {
        assertEquals(emptyList<Any>(), (DirectoryListingParser.parse(emptyList()) as OperationResult.Success).value)
    }

    @Test
    fun `round trips hostile and unicode names without record ambiguity`() {
        val names = listOf(
            "with space", "中文", "'single'", "\"double\"", "line1\nline2", "tab\tname",
            "semi;colon", "`backtick`", "\$(command)", "-leading", " !@#%^&*()[]{};`\$()中文 ",
        )
        val lines = names.map { name -> record(name, "/parent/$name", "file", "0", "0", "1", "0", "0") }

        val entries = (DirectoryListingParser.parse(lines) as OperationResult.Success).value

        assertEquals(names, entries.map { it.name })
        assertEquals(names.map { "/parent/$it" }, entries.map { it.path.value })
    }

    @Test
    fun `invalid base64 fails the whole listing`() {
        assertMalformed("***\t${b64("/p")}	file\t0\t0\t1\t1\t0")
    }

    @Test
    fun `missing fields fail the whole listing`() {
        assertMalformed("${b64("a")}\t${b64("/a")}\tfile")
    }

    @Test
    fun `invalid numbers fail the whole listing`() {
        assertMalformed(record("a", "/a", "file", "NaN", "0", "1", "1", "0"))
        assertMalformed(record("a", "/a", "file", "0", "1.5", "1", "1", "0"))
    }

    @Test
    fun `invalid booleans fail the whole listing`() {
        assertMalformed(record("a", "/a", "file", "0", "0", "true", "1", "0"))
        assertMalformed(record("a", "/a", "file", "0", "0", "1", "2", "0"))
    }

    @Test
    fun `invalid type fails the whole listing`() {
        assertMalformed(record("a", "/a", "socket", "0", "0", "1", "1", "0"))
    }

    private fun assertMalformed(line: String) {
        val result = DirectoryListingParser.parse(listOf(line))
        assertTrue(result is OperationResult.Failure)
        assertEquals(ErrorCode.COMMAND_FAILED, (result as OperationResult.Failure).code)
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
    ) = listOf(b64(name), b64(path), type, size, mtime, readable, writable, symlink).joinToString("\t")

    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
