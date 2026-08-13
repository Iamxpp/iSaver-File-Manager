package com.iamxpp.isaver.data.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDirectoryHelperTest {
    private val helper = RootTransferHelper("/data/local/tmp/iSaver helper")

    @Test
    fun `list directory emits only the fixed helper subcommand and one safely quoted path`() {
        val path = "/data/local/tmp/中文 'quoted'\n-leading;\$(id)`ignored`"

        val command = helper.listDirectory(path)

        assertEquals(
            "'/data/local/tmp/iSaver helper' 'list-dir' " +
                "'/data/local/tmp/中文 '\\''quoted'\\''\n-leading;\$(id)`ignored`'",
            command,
        )
    }

    @Test
    fun `list directory rejects a NUL path before constructing a command`() {
        val failure = runCatching { helper.listDirectory("/data/local/tmp/bad\u0000path") }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `change mode emits a fixed helper command with numeric mode`() {
        val command = helper.changeModeBound(
            original = "/data/local/tmp",
            canonical = "/data/local/tmp",
            name = com.iamxpp.isaver.domain.EntryName.parse("a';\n.txt").getOrThrow(),
            parentIdentity = RootFileIdentity(1, 2),
            sourceIdentity = RootFileIdentity(3, 4),
            expectedMode = 0x1A4,
            mode = 0x1ED,
        )

        assertTrue(command.contains("'chmod-bound'"))
        assertTrue(command.contains("'a'\\'';\n.txt'"))
        assertTrue(command.endsWith("'420' '493'"))
        assertFalse(command.contains("chmod 777"))
        assertFalse(command.contains("sh -c"))
    }
}
