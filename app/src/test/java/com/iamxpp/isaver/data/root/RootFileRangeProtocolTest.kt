package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileRangeProtocolTest {
    @Test fun `decodes exact range and exposes immutable file version`() {
        val bytes = "range-data".toByteArray()
        val lines = listOf(
            header(size = 100, offset = 32, count = bytes.size.toLong()),
            Base64.getEncoder().encodeToString(bytes),
        )

        val result = RootFileRangeProtocol.decode(lines, 32, bytes.size.toLong())

        val chunk = (result as OperationResult.Success).value
        assertArrayEquals(bytes, chunk.bytes)
        assertEquals(100, chunk.version.sizeBytes)
        assertEquals(7, chunk.version.device)
        assertEquals(9, chunk.version.inode)
    }

    @Test fun `rejects malformed payload offset count and short bytes`() {
        val encoded = Base64.getEncoder().encodeToString("data".toByteArray())
        listOf(
            listOf(header(4, 1, 4), encoded),
            listOf(header(4, 0, 3), encoded),
            listOf(header(4, 0, 4), "bad base64!"),
            listOf(header(5, 0, 5), encoded),
        ).forEach { lines ->
            val result = RootFileRangeProtocol.decode(lines, 0, 4)
            assertTrue(result is OperationResult.Failure)
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        }
    }

    private fun header(size: Long, offset: Long, count: Long) =
        "ISAVER_RANGE_V1\t$size\t7\t9\t10\t11\t12\t13\t$offset\t$count"
}
