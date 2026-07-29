package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatOutputTokenPolicyTest {
    @Test
    fun utf8EstimatorCoversChineseAsciiMarkdownEmojiAndBlankContent() {
        assertEquals(2, ChatOutputTokenPolicy.estimateFormatCardTokens("中文"))
        assertEquals(2, ChatOutputTokenPolicy.estimateFormatCardTokens("abcd"))
        assertEquals(2, ChatOutputTokenPolicy.estimateFormatCardTokens("# 😀"))
        assertEquals(0, ChatOutputTokenPolicy.estimateFormatCardTokens(" \n "))
        assertEquals(0, ChatOutputTokenPolicy.estimateFormatCardTokens(null))
    }

    @Test
    fun budgetAddsReplyFormatThinkingAndTolerance() {
        val budget = ChatOutputTokenPolicy.resolve(
            replyLengthChars = 300,
            formatCardContent = "中文",
            modelConfig = model(
                customParams = mapOf(
                    "enable_thinking" to ParamValue.BooleanValue(true),
                    "thinking_budget" to ParamValue.NumberValue(1024.2)
                )
            )
        )

        assertEquals(300, budget.replyLengthTokens)
        assertEquals(2, budget.formatCardTokens)
        assertEquals(1025, budget.thinkingBudgetTokens)
        assertEquals(500, budget.toleranceTokens)
        assertEquals(1827, budget.maxTokens)
    }

    @Test
    fun explicitThinkingOffRemovesBudgetAndNamedFlagWinsOverCustomFlag() {
        val customOff = ChatOutputTokenPolicy.resolve(
            replyLengthChars = 300,
            formatCardContent = null,
            modelConfig = model(
                customParams = mapOf(
                    "enable_thinking" to ParamValue.BooleanValue(false),
                    "thinking_budget" to ParamValue.NumberValue(1024.0)
                )
            )
        )
        val namedOff = ChatOutputTokenPolicy.resolve(
            replyLengthChars = 300,
            formatCardContent = null,
            modelConfig = model(
                enableThinking = false,
                customParams = mapOf(
                    "enable_thinking" to ParamValue.BooleanValue(true),
                    "thinking_budget" to ParamValue.NumberValue(1024.0)
                )
            )
        )

        assertEquals(0, customOff.thinkingBudgetTokens)
        assertEquals(0, namedOff.thinkingBudgetTokens)
    }

    @Test
    fun ignoresOtherReasoningAliasesAndInvalidThinkingBudget() {
        val aliases = ChatOutputTokenPolicy.resolve(
            modelConfig = model(
                customParams = mapOf(
                    "max_thinking_tokens" to ParamValue.NumberValue(999.0),
                    "reasoning_effort" to ParamValue.StringValue("high")
                )
            ),
            formatCardContent = null
        )
        val invalid = ChatOutputTokenPolicy.resolve(
            modelConfig = model(
                customParams = mapOf(
                    "thinking_budget" to ParamValue.NumberValue(Double.NaN)
                )
            ),
            formatCardContent = null
        )

        assertEquals(0, aliases.thinkingBudgetTokens)
        assertEquals(0, invalid.thinkingBudgetTokens)
    }

    @Test
    fun staleSessionFormatFallbackFeedsRawDefaultCardIntoBudget() {
        val defaultCard = FormatCard(
            id = "default",
            name = "默认格式",
            content = "中文",
            createdAt = 1
        )
        val resolved = resolveFormatCardForRequest(
            sessionFormatCardId = "missing",
            defaultFormatCardId = defaultCard.id,
            availableCards = listOf(defaultCard)
        )

        val budget = ChatOutputTokenPolicy.resolve(
            replyLengthChars = 300,
            formatCardContent = resolved?.content,
            modelConfig = model()
        )

        assertEquals(2, budget.formatCardTokens)
    }

    @Test
    fun totalSaturatesAtIntMaxValue() {
        val budget = ChatOutputTokenPolicy.resolve(
            replyLengthChars = 40_000,
            formatCardContent = "中文",
            modelConfig = model(
                customParams = mapOf(
                    "thinking_budget" to ParamValue.NumberValue(Int.MAX_VALUE.toDouble())
                )
            ),
            toleranceTokens = Int.MAX_VALUE
        )

        assertEquals(Int.MAX_VALUE, budget.maxTokens)
    }

    private fun model(
        customParams: Map<String, ParamValue> = emptyMap(),
        enableThinking: Boolean? = null
    ) = ModelConfig(
        id = "model",
        displayName = "Model",
        baseUrl = "https://example.test/v1",
        apiKey = "key",
        modelName = "model",
        customParams = customParams,
        enableThinking = enableThinking,
        createdAt = 0
    )
}
