package com.example.chatbar.domain.memory

object MemoryEpisodeSummaryPolicy {
    const val MIN_SOURCE_TURNS = 1
    const val MAX_SOURCE_TURNS = 6
    private const val FIRST_TURN_MAX_CHARS = 50
    private const val EACH_ADDITIONAL_TURN_MAX_CHARS = 20
    private const val HARD_LIMIT_MULTIPLIER = 2

    fun promptMaxChars(sourceTurnCount: Int): Int {
        val turns = sourceTurnCount.coerceIn(MIN_SOURCE_TURNS, MAX_SOURCE_TURNS)
        return FIRST_TURN_MAX_CHARS + (turns - 1) * EACH_ADDITIONAL_TURN_MAX_CHARS
    }

    fun hardMaxChars(sourceTurnCount: Int): Int =
        promptMaxChars(sourceTurnCount) * HARD_LIMIT_MULTIPLIER

    fun characterCount(text: String): Int = text.codePointCount(0, text.length)

    fun isWithinHardLimit(text: String, sourceTurnCount: Int): Boolean =
        characterCount(text.trim()) <= hardMaxChars(sourceTurnCount)
}
