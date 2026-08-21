package com.example.chatbar.domain.image

data class NovelAiActiveTagFragment(
    val query: String,
    val replaceStart: Int,
    val replaceEnd: Int
)

data class NovelAiTagInsertion(
    val text: String,
    val cursor: Int
)

object NovelAiTagCompletion {
    private val weightPrefix = Regex("^[+-]?(?:\\d+(?:\\.\\d+)?)?::")
    private val delimiters = setOf(',', '，', '\n')
    private val opening = setOf('{', '[', '(')
    private val closing = setOf('}', ']', ')')

    fun activeFragment(text: String, cursor: Int): NovelAiActiveTagFragment? {
        val safeCursor = cursor.coerceIn(0, text.length)
        val segmentStart = (text.indexOfLastBefore(safeCursor) { it in delimiters } + 1)
        val nextDelimiter = text.indexOfFirstAfter(safeCursor) { it in delimiters }
            .takeIf { it >= 0 } ?: text.length
        var replaceStart = segmentStart
        while (replaceStart < nextDelimiter && (text[replaceStart].isWhitespace() || text[replaceStart] in opening)) {
            replaceStart++
        }
        while (true) {
            val match = weightPrefix.find(text.substring(replaceStart, nextDelimiter)) ?: break
            replaceStart += match.value.length
        }
        var replaceEnd = nextDelimiter
        while (replaceEnd > replaceStart && (text[replaceEnd - 1].isWhitespace() || text[replaceEnd - 1] in closing)) {
            replaceEnd--
        }
        while (replaceEnd - 2 >= replaceStart && text.substring(replaceEnd - 2, replaceEnd) == "::") {
            replaceEnd -= 2
        }
        val queryEnd = safeCursor.coerceIn(replaceStart, replaceEnd)
        val query = text.substring(replaceStart, queryEnd).trim()
        return query.takeIf(String::isNotBlank)?.let {
            NovelAiActiveTagFragment(it, replaceStart, replaceEnd)
        }
    }

    fun insert(text: String, cursor: Int, tag: String): NovelAiTagInsertion {
        val fragment = activeFragment(text, cursor)
            ?: return NovelAiTagInsertion(text, cursor.coerceIn(0, text.length))
        val result = text.replaceRange(fragment.replaceStart, fragment.replaceEnd, tag)
        return NovelAiTagInsertion(result, fragment.replaceStart + tag.length)
    }

    private inline fun String.indexOfLastBefore(endExclusive: Int, predicate: (Char) -> Boolean): Int {
        for (index in endExclusive - 1 downTo 0) if (predicate(this[index])) return index
        return -1
    }

    private inline fun String.indexOfFirstAfter(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }
}
