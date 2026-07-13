package com.iamxpp.isaver.data.root

import org.junit.Assert.assertEquals
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
}
