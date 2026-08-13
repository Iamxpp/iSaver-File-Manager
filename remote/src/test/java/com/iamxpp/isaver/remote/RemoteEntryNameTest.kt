package com.iamxpp.isaver.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEntryNameTest {
    @Test
    fun acceptsLinuxBasenameWithinUtf8Limit() {
        assertEquals("资料", RemoteEntryName.parse("资料").getOrThrow().value)
        assertEquals("report.txt", RemoteEntryName.parse("report.txt").getOrThrow().value)
    }

    @Test
    fun rejectsReservedOrUnsafeNames() {
        listOf("", " ", ".", "..", "a/b", "a\u0000b").forEach { name ->
            assertTrue("expected rejection for $name", RemoteEntryName.parse(name).isFailure)
        }
    }

    @Test
    fun rejectsNamesLongerThan255Utf8Bytes() {
        assertTrue(RemoteEntryName.parse("界".repeat(86)).isFailure)
    }
}
