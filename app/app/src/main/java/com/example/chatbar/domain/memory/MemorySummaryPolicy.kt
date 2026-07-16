package com.example.chatbar.domain.memory

/** Archive state words must be tied to an explicit T in the same sentence. */
object MemorySummaryPolicy {
    private val stateWord = Regex("现在|目前|仍然")
    private val timelineTag = Regex("T\\d+")

    fun hasOnlyQualifiedStateWords(summary: String): Boolean = summary
        .split('。', '！', '？', '\n')
        .all { sentence -> !stateWord.containsMatchIn(sentence) || timelineTag.containsMatchIn(sentence) }
}
