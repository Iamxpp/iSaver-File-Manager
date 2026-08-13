package com.iamxpp.isaver.texteditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextSearchTest {
    @Test
    fun `find all supports case sensitivity and non overlapping matches`() {
        assertEquals(listOf(0..2, 4..6), TextSearch.findAll("One one", "one", matchCase = false))
        assertEquals(listOf(4..6), TextSearch.findAll("One one", "one", matchCase = true))
    }

    @Test
    fun `replace all reports count and leaves input unchanged without matches`() {
        assertEquals(TextReplaceResult("x x", 2), TextSearch.replaceAll("one one", "one", "x", true))
        val unchanged = TextSearch.replaceAll("text", "missing", "x", false)
        assertEquals("text", unchanged.text)
        assertEquals(0, unchanged.count)
        assertFalse(unchanged.changed)
    }

    @Test
    fun `empty query never matches or replaces`() {
        assertEquals(emptyList<IntRange>(), TextSearch.findAll("text", "", false))
        assertEquals(TextReplaceResult("text", 0), TextSearch.replaceAll("text", "", "x", false))
    }
}
