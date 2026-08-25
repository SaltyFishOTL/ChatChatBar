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
        var replaceStart = segmentStart
        while (replaceStart < safeCursor && (text[replaceStart].isWhitespace() || text[replaceStart] in opening)) {
            replaceStart++
        }
        while (true) {
            val match = weightPrefix.find(text.substring(replaceStart, safeCursor)) ?: break
            replaceStart += match.value.length
        }
        var replaceEnd = safeCursor
        while (replaceEnd > replaceStart && (text[replaceEnd - 1].isWhitespace() || text[replaceEnd - 1] in closing)) {
            replaceEnd--
        }
        while (replaceEnd - 2 >= replaceStart && text.substring(replaceEnd - 2, replaceEnd) == "::") {
            replaceEnd -= 2
        }
        val query = text.substring(replaceStart, replaceEnd).trim()
        return query.takeIf(String::isNotBlank)?.let {
            NovelAiActiveTagFragment(it, replaceStart, replaceEnd)
        }
    }

    fun insert(text: String, cursor: Int, tag: String): NovelAiTagInsertion {
        val fragment = activeFragment(text, cursor)
            ?: return NovelAiTagInsertion(text, cursor.coerceIn(0, text.length))
        val suffix = text.substring(fragment.replaceEnd)
        val firstSuffixContent = suffix.firstOrNull { !it.isWhitespace() }
        val insertBeforeExistingTag = firstSuffixContent != null &&
            firstSuffixContent !in delimiters && firstSuffixContent !in closing &&
            !suffix.trimStart().startsWith("::")
        val consumedWhitespace = if (insertBeforeExistingTag) suffix.indexOfFirst { !it.isWhitespace() } else 0
        val replacement = if (insertBeforeExistingTag) "$tag, " else tag
        val result = text.replaceRange(
            fragment.replaceStart,
            fragment.replaceEnd + consumedWhitespace.coerceAtLeast(0),
            replacement
        )
        return NovelAiTagInsertion(result, fragment.replaceStart + replacement.length)
    }

    private inline fun String.indexOfLastBefore(endExclusive: Int, predicate: (Char) -> Boolean): Int {
        for (index in endExclusive - 1 downTo 0) if (predicate(this[index])) return index
        return -1
    }

}
