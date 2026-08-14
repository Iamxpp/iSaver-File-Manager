package com.isaver.filemanager.texteditor

data class TextReplaceResult(val text: String, val count: Int) {
    val changed: Boolean get() = count > 0
}

object TextSearch {
    fun findAll(text: String, query: String, matchCase: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<IntRange>()
        var start = 0
        while (start <= text.length - query.length) {
            val index = text.indexOf(query, start, ignoreCase = !matchCase)
            if (index < 0) break
            matches += index until index + query.length
            start = index + query.length
        }
        return matches
    }

    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        matchCase: Boolean,
    ): TextReplaceResult {
        val matches = findAll(text, query, matchCase)
        if (matches.isEmpty()) return TextReplaceResult(text, 0)
        val output = StringBuilder(text.length)
        var offset = 0
        matches.forEach { match ->
            output.append(text, offset, match.first)
            output.append(replacement)
            offset = match.last + 1
        }
        output.append(text, offset, text.length)
        return TextReplaceResult(output.toString(), matches.size)
    }
}
