package com.isaver.filemanager.texteditor

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDocumentCodecTest {
    @Test
    fun `detects utf8 bom and preserves crlf`() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
            "第一行\r\nsecond\r\n".toByteArray(Charsets.UTF_8)

        val document = TextDocumentCodec.decode(bytes)!!

        assertEquals(TextEncoding.UTF8, document.encoding)
        assertTrue(document.hasBom)
        assertEquals(LineEnding.CRLF, document.lineEnding)
        assertEquals("第一行\nsecond\n", document.text)
        assertArrayEquals(bytes, TextDocumentCodec.encode(document))
    }

    @Test
    fun `detects utf16 little endian bom`() {
        val payload = "甲\r\n乙".toByteArray(Charsets.UTF_16LE)
        val document = TextDocumentCodec.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + payload)!!

        assertEquals(TextEncoding.UTF16_LE, document.encoding)
        assertEquals(LineEnding.CRLF, document.lineEnding)
        assertEquals("甲\n乙", document.text)
    }

    @Test
    fun `falls back to strict gb18030 when utf8 is invalid`() {
        val bytes = "中文内容".toByteArray(Charset.forName("GB18030"))

        val document = TextDocumentCodec.decode(bytes)!!

        assertEquals(TextEncoding.GB18030, document.encoding)
        assertEquals("中文内容", document.text)
        assertArrayEquals(bytes, TextDocumentCodec.encode(document))
    }

    @Test
    fun `mixed line endings choose dominant and normalize editor text`() {
        val document = TextDocumentCodec.decode("a\r\nb\r\nc\nd\r".toByteArray())!!

        assertEquals(LineEnding.CRLF, document.lineEnding)
        assertEquals("a\nb\nc\nd\n", document.text)
    }

    @Test
    fun `encoding rejects characters that cannot be represented`() {
        val document = TextDocument("broken \uD800", TextEncoding.GB18030, LineEnding.LF, false)

        assertNull(TextDocumentCodec.encodeOrNull(document))
    }
}
