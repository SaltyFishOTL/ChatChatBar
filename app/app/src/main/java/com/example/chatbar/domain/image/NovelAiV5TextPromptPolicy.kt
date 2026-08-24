package com.example.chatbar.domain.image

internal object NovelAiV5TextPromptPolicy {
    private val explicitTextBlock = Regex("(?i)(?:^|[^A-Za-z0-9_])text\\s*:")
    private val closingQuotes = mapOf(
        '"' to '"',
        '“' to '”',
        '「' to '」',
        '『' to '』'
    )

    fun apply(prompt: NovelAiPromptPlan, model: NovelAiImageModel): NovelAiPromptPlan {
        if (model != NovelAiImageModel.V5_FULL) return prompt
        val positivePrompts = buildList {
            add(prompt.baseCaption)
            prompt.characterCaptions.forEach { add(it.prompt) }
        }
        if (positivePrompts.any(explicitTextBlock::containsMatchIn)) return prompt

        val renderedTexts = positivePrompts.flatMap(::quotedTexts)
        if (renderedTexts.isEmpty()) return prompt
        val textBlock = "Text: ${renderedTexts.joinToString("\n\n")}"
        val baseCaption = prompt.baseCaption.trimEnd().let { base ->
            if (base.isBlank()) textBlock else "$base\n\n$textBlock"
        }
        return prompt.copy(baseCaption = baseCaption)
    }

    private fun quotedTexts(source: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            val opener = source[index]
            val closer = closingQuotes[opener]
            if (closer == null || opener == '"' && source.isEscaped(index)) {
                index += 1
                continue
            }
            var closingIndex = index + 1
            while (closingIndex < source.length) {
                if (source[closingIndex] == closer && (closer != '"' || !source.isEscaped(closingIndex))) break
                closingIndex += 1
            }
            if (closingIndex >= source.length) {
                index += 1
                continue
            }
            source.substring(index + 1, closingIndex).trim().takeIf(String::isNotEmpty)?.let(result::add)
            index = closingIndex + 1
        }
        return result
    }

    private fun String.isEscaped(index: Int): Boolean {
        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && this[cursor] == '\\') {
            slashCount += 1
            cursor -= 1
        }
        return slashCount % 2 == 1
    }
}
