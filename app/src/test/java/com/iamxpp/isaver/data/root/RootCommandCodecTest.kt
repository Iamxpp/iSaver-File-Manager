package com.iamxpp.isaver.data.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandCodecTest {
    @Test
    fun `escapes single quotes with posix shell quoting`() {
        assertEquals("'has'\\''quote'", RootCommandCodec.quote("has'quote"))
    }

    @Test
    fun `quotes supported argument edge cases`() {
        val cases = mapOf(
            "with space" to "'with space'",
            "文件" to "'文件'",
            "\"double\"" to "'\"double\"'",
            "line1\nline2" to "'line1\nline2'",
            "a;b" to "'a;b'",
            "`id`" to "'`id`'",
            "\$(id)" to "'\$(id)'",
            "-rf" to "'-rf'",
            "" to "''",
        )

        cases.forEach { (raw, expected) ->
            assertEquals(expected, RootCommandCodec.quote(raw))
        }
    }

    @Test
    fun `quotes combined spaces unicode and shell metacharacters as one argument`() {
        val raw = "/data/a b/'x';\$(id)`whoami`\n文件"

        assertEquals(
            "'/data/a b/'\\''x'\\'';\$(id)`whoami`\n文件'",
            RootCommandCodec.quote(raw),
        )
    }
}
