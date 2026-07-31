package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.DEFAULT_REPLY_LENGTH_CHARS
import com.example.chatbar.data.local.entity.MAX_REPLY_LENGTH_CHARS
import com.example.chatbar.data.local.entity.MIN_REPLY_LENGTH_CHARS
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.PromptTemplates
import java.nio.charset.StandardCharsets
import kotlin.math.ceil

data class ChatOutputTokenBudget(
    val replyLengthTokens: Int,
    val formatCardTokens: Int,
    val thinkingBudgetTokens: Int,
    val toleranceTokens: Int,
    val maxTokens: Int
)

object ChatOutputTokenPolicy {
    private const val UTF8_BYTES_PER_ESTIMATED_TOKEN = 2L
    private const val DEFAULT_THINKING_BUDGET_TOKENS = 1024

    fun resolve(
        replyLengthChars: Int = DEFAULT_REPLY_LENGTH_CHARS,
        formatCardContent: String?,
        modelConfig: ModelConfig,
        toleranceTokens: Int = PromptTemplates.CHAT_MAX_TOKEN_TOLERANCE
    ): ChatOutputTokenBudget {
        val normalizedReplyLength = replyLengthChars.coerceIn(
            MIN_REPLY_LENGTH_CHARS,
            MAX_REPLY_LENGTH_CHARS
        )
        val formatTokens = estimateFormatCardTokens(formatCardContent)
        val thinkingTokens = effectiveThinkingBudget(modelConfig)
        val normalizedTolerance = toleranceTokens.coerceAtLeast(0)
        val total = normalizedReplyLength.toLong() +
            formatTokens.toLong() +
            thinkingTokens.toLong() +
            normalizedTolerance.toLong()
        return ChatOutputTokenBudget(
            replyLengthTokens = normalizedReplyLength,
            formatCardTokens = formatTokens,
            thinkingBudgetTokens = thinkingTokens,
            toleranceTokens = normalizedTolerance,
            maxTokens = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    internal fun estimateFormatCardTokens(content: String?): Int {
        val normalized = content?.trim().orEmpty()
        if (normalized.isEmpty()) return 0
        val byteCount = normalized.toByteArray(StandardCharsets.UTF_8).size.toLong()
        return ((byteCount + UTF8_BYTES_PER_ESTIMATED_TOKEN - 1L) /
            UTF8_BYTES_PER_ESTIMATED_TOKEN)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    internal fun effectiveThinkingBudget(modelConfig: ModelConfig): Int {
        val customThinkingEnabled =
            (modelConfig.customParams["enable_thinking"] as? ParamValue.BooleanValue)?.value
        val effectiveThinkingEnabled = modelConfig.enableThinking ?: customThinkingEnabled
        if (effectiveThinkingEnabled == false) return 0

        val value =
            (modelConfig.customParams["thinking_budget"] as? ParamValue.NumberValue)?.value
                ?: return if (effectiveThinkingEnabled == true) {
                    DEFAULT_THINKING_BUDGET_TOKENS
                } else {
                    0
                }
        if (!value.isFinite() || value <= 0.0) return 0
        return ceil(value)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
    }
}
