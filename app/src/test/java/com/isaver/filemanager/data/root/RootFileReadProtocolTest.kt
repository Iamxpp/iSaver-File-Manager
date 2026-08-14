package com.isaver.filemanager.data.root

import com.isaver.filemanager.domain.ErrorCode
import com.isaver.filemanager.domain.OperationResult
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileReadProtocolTest {
    @Test
    fun `decodes exact independently padded chunks`() {
        val output = ByteArrayOutputStream()
        val bytes = "archive bytes".toByteArray()
        val lines = listOf(
            "ISAVER_FILE_V1\t${bytes.size}",
            Base64.getEncoder().encodeToString(bytes.copyOfRange(0, 4)),
            Base64.getEncoder().encodeToString(bytes.copyOfRange(4, bytes.size)),
        )

        val result = RootFileReadProtocol.decode(lines, output, bytes.size.toLong())

        assertEquals(bytes.size.toLong(), (result as OperationResult.Success<Long>).value)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `rejects header size mismatch malformed base64 and short payload`() {
        listOf(
            listOf("ISAVER_FILE_V1\t5", Base64.getEncoder().encodeToString("four".toByteArray())),
            listOf("ISAVER_FILE_V1\t4", "not base64!"),
            listOf("ISAVER_FILE_V1\t4", Base64.getEncoder().encodeToString("one".toByteArray())),
        ).forEach { lines ->
            val result = RootFileReadProtocol.decode(lines, ByteArrayOutputStream(), 4L)
            assertTrue(result is OperationResult.Failure)
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        }
    }
}
