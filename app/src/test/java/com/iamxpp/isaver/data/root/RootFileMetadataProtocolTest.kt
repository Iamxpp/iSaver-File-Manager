package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RootFileMetadataProtocolTest {
    @Test fun `parses exact mode uid gid and identity`() {
        val result = RootFileMetadataProtocol.parse(
            listOf("ISAVER_META_V1\t416\t1000\t1001\t7\t9"),
        )

        val metadata = (result as OperationResult.Success).value
        assertEquals(0b110100000, metadata.mode)
        assertEquals(1000, metadata.uid)
        assertEquals(1001, metadata.gid)
        assertEquals(7, metadata.device)
        assertEquals(9, metadata.inode)
    }

    @Test fun `rejects malformed or out of range metadata`() {
        listOf(
            listOf("ISAVER_META_V1\t4096\t0\t0\t1\t2"),
            listOf("ISAVER_META_V1\t420\t-1\t0\t1\t2"),
            listOf("ISAVER_META_V1\t420\t0\t0\t1"),
            listOf("wrong\t420\t0\t0\t1\t2"),
        ).forEach { lines ->
            val result = RootFileMetadataProtocol.parse(lines)
            assertEquals(ErrorCode.SOURCE_UNREADABLE, (result as OperationResult.Failure).code)
        }
    }
}
