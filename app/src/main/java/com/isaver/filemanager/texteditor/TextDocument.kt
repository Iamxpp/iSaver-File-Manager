package com.isaver.filemanager.texteditor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class TextEncoding(val label: String, internal val charset: Charset) {
    UTF8("UTF-8", Charsets.UTF_8),
    UTF16_LE("UTF-16 LE", Charsets.UTF_16LE),
    UTF16_BE("UTF-16 BE", Charsets.UTF_16BE),
    GB18030("GB18030", Charset.forName("GB18030")),
}

enum class LineEnding(val label: String, internal val sequence: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
    CR("CR", "\r"),
}

data class TextDocument(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val hasBom: Boolean,
)

object TextDocumentCodec {
    fun decode(bytes: ByteArray): TextDocument? {
        val detected = detect(bytes)
        for ((encoding, bomBytes) in detected) {
            val decoded = decodeStrict(bytes, encoding, bomBytes) ?: continue
            val lineEnding = dominantLineEnding(decoded)
            return TextDocument(
                text = normalizeLineEndings(decoded),
                encoding = encoding,
                lineEnding = lineEnding,
                hasBom = bomBytes > 0,
            )
        }
        return null
    }

    fun encode(document: TextDocument): ByteArray = requireNotNull(encodeOrNull(document))

    fun encodeOrNull(document: TextDocument): ByteArray? {
        val externalText = document.text.replace("\n", document.lineEnding.sequence)
        val payload = encodeStrict(externalText, document.encoding) ?: return null
        val bom = if (document.hasBom) bom(document.encoding) else byteArrayOf()
        return bom + payload
    }

    private fun detect(bytes: ByteArray): List<Pair<TextEncoding, Int>> = when {
        bytes.startsWith(UTF8_BOM) -> listOf(TextEncoding.UTF8 to UTF8_BOM.size)
        bytes.startsWith(UTF16_LE_BOM) -> listOf(TextEncoding.UTF16_LE to UTF16_LE_BOM.size)
        bytes.startsWith(UTF16_BE_BOM) -> listOf(TextEncoding.UTF16_BE to UTF16_BE_BOM.size)
        else -> listOf(TextEncoding.UTF8 to 0, TextEncoding.GB18030 to 0)
    }

    private fun decodeStrict(bytes: ByteArray, encoding: TextEncoding, offset: Int): String? = try {
        encoding.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun encodeStrict(text: String, encoding: TextEncoding): ByteArray? = try {
        val buffer = encoding.charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: Exception) {
        null
    }

    private fun dominantLineEnding(text: String): LineEnding {
        var lf = 0
        var crlf = 0
        var cr = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> if (index + 1 < text.length && text[index + 1] == '\n') {
                    crlf += 1
                    index += 1
                } else cr += 1
                '\n' -> lf += 1
            }
            index += 1
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            lf >= cr && lf > 0 -> LineEnding.LF
            cr > 0 -> LineEnding.CR
            else -> LineEnding.LF
        }
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun bom(encoding: TextEncoding): ByteArray = when (encoding) {
        TextEncoding.UTF8 -> UTF8_BOM
        TextEncoding.UTF16_LE -> UTF16_LE_BOM
        TextEncoding.UTF16_BE -> UTF16_BE_BOM
        TextEncoding.GB18030 -> byteArrayOf()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
}
