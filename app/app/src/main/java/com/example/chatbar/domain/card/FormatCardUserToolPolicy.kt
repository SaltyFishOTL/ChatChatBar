package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlin.random.Random

data class FormatCardUserToolValidation(
    val minimumError: String? = null,
    val maximumError: String? = null,
    val textError: String? = null
) {
    val firstError: String?
        get() = minimumError ?: maximumError ?: textError

    val isValid: Boolean
        get() = firstError == null
}

object FormatCardUserToolPolicy {
    fun validate(tool: FormatCardUserToolConfig): FormatCardUserToolValidation = when (tool.type) {
        FormatCardUserToolType.RANDOM_NUMBER -> validateRandomNumber(tool)
        FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> FormatCardUserToolValidation(
            textError = "请输入强提示词尾缀".takeIf { tool.text.isBlank() }
        )
    }

    fun firstValidationError(tools: List<FormatCardUserToolConfig>): String? =
        tools.mapIndexedNotNull { index, tool ->
            validate(tool).firstError?.let { "第 ${index + 1} 个用户工具：$it" }
        }.firstOrNull()

    fun requireValid(tools: List<FormatCardUserToolConfig>) {
        firstValidationError(tools)?.let { error -> throw IllegalArgumentException(error) }
    }

    fun appendRequestSuffix(
        userContent: String,
        tools: List<FormatCardUserToolConfig>,
        nextIntInclusive: (minimum: Int, maximum: Int) -> Int = ::randomIntInclusive
    ): String {
        if (tools.isEmpty()) return userContent
        requireValid(tools)

        val fragments = buildList {
            var index = 0
            while (index < tools.size) {
                val tool = tools[index]
                when (tool.type) {
                    FormatCardUserToolType.STRONG_PROMPT_SUFFIX -> {
                        add(tool.text)
                        index += 1
                    }

                    FormatCardUserToolType.RANDOM_NUMBER -> {
                        val values = mutableListOf<Int>()
                        while (
                            index < tools.size &&
                            tools[index].type == FormatCardUserToolType.RANDOM_NUMBER
                        ) {
                            val randomTool = tools[index]
                            values += nextIntInclusive(
                                randomTool.minimum.toInt(),
                                randomTool.maximum.toInt()
                            )
                            index += 1
                        }
                        add(PromptTemplates.randomNumberUserToolSuffix(values))
                    }
                }
            }
        }
        return PromptTemplates.appendUserToolSuffixBlock(userContent, fragments)
    }

    private fun validateRandomNumber(
        tool: FormatCardUserToolConfig
    ): FormatCardUserToolValidation {
        val minimum = tool.minimum.toIntOrNull()
        val maximum = tool.maximum.toIntOrNull()
        return FormatCardUserToolValidation(
            minimumError = "最小值必须是 32 位整数".takeIf { minimum == null },
            maximumError = when {
                maximum == null -> "最大值必须是 32 位整数"
                minimum != null && maximum < minimum -> "最大值不能小于最小值"
                else -> null
            }
        )
    }

    private fun randomIntInclusive(minimum: Int, maximum: Int): Int =
        Random.nextLong(minimum.toLong(), maximum.toLong() + 1L).toInt()
}
