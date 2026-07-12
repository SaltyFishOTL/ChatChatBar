package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard

data class PromptCachePromptLayers(
    val stableSystemPrompt: String,
    val dynamicSystemPrompt: String,
    val tailSystemPrompt: String,
    val stablePrefixCacheable: Boolean
)

private enum class PromptLayer {
    STABLE,
    DYNAMIC,
    TAIL
}

private data class PromptSection(
    val layer: PromptLayer,
    val title: String,
    val content: String
)

/** 将角色设定、动态资料与回复约束组装为分层 system prompt。 */
class PromptAssembler {

    fun assembleSystemPrompt(
        characterCard: CharacterCard,
        playerSetting: String? = null,
        playerName: String? = null,
        supplementarySetting: String? = null,
        formatCard: FormatCard? = null,
        ragResults: List<RetrievedKnowledgeCard> = emptyList(),
        ragInjectionMode: String = "STANDARD",
        replyLength: String? = null,
        replyLanguage: String? = null,
        longTermMemory: String? = null,
        worldBookPrompt: String? = null,
        worldBookOutlets: Map<String, String> = emptyMap(),
        includePostHistory: Boolean = true
    ): String {
        val sections = collectSections(
            characterCard = characterCard,
            playerSetting = playerSetting,
            playerName = playerName,
            supplementarySetting = supplementarySetting,
            formatCard = formatCard,
            ragResults = ragResults,
            ragInjectionMode = ragInjectionMode,
            replyLength = replyLength,
            replyLanguage = replyLanguage,
            longTermMemory = longTermMemory,
            worldBookPrompt = worldBookPrompt
        ).filter { includePostHistory || it.layer != PromptLayer.TAIL }
        return renderLayer(
            raw = renderSections(sections),
            playerName = playerName,
            botName = characterCard.name,
            worldBookOutlets = worldBookOutlets
        )
    }

    /**
     * 稳定设定在前，动态资料靠近末尾，后置指令与上一轮位于最终热区。
     * 稳定区含动态 World Book outlet 时，完整 system prompt 退回动态层。
     */
    fun assembleCachePromptLayers(
        characterCard: CharacterCard,
        playerSetting: String? = null,
        playerName: String? = null,
        supplementarySetting: String? = null,
        formatCard: FormatCard? = null,
        ragResults: List<RetrievedKnowledgeCard> = emptyList(),
        ragInjectionMode: String = "STANDARD",
        replyLength: String? = null,
        replyLanguage: String? = null,
        longTermMemory: String? = null,
        worldBookPrompt: String? = null,
        worldBookOutlets: Map<String, String> = emptyMap(),
        hasHistoryMessages: Boolean = false,
        hasPreviousTurn: Boolean = false
    ): PromptCachePromptLayers {
        val sections = collectSections(
            characterCard = characterCard,
            playerSetting = playerSetting,
            playerName = playerName,
            supplementarySetting = supplementarySetting,
            formatCard = formatCard,
            ragResults = ragResults,
            ragInjectionMode = ragInjectionMode,
            replyLength = replyLength,
            replyLanguage = replyLanguage,
            longTermMemory = longTermMemory,
            worldBookPrompt = worldBookPrompt
        )
        val stableRaw = renderSections(sections.filter { it.layer == PromptLayer.STABLE })
            .appendHeadingIf(hasHistoryMessages, PromptTemplates.SECTION_CHAT_HISTORY)
        val dynamicRaw = renderSections(sections.filter { it.layer == PromptLayer.DYNAMIC })
        val tailRaw = renderSections(sections.filter { it.layer == PromptLayer.TAIL })
            .appendHeadingIf(hasPreviousTurn, PromptTemplates.SECTION_PREVIOUS_TURN)

        if (OUTLET_TOKEN_REGEX.containsMatchIn(stableRaw)) {
            val fallbackRaw = renderSections(sections.filter { it.layer != PromptLayer.TAIL })
                .appendHeadingIf(hasHistoryMessages, PromptTemplates.SECTION_CHAT_HISTORY)
            return PromptCachePromptLayers(
                stableSystemPrompt = "",
                dynamicSystemPrompt = renderLayer(
                    fallbackRaw,
                    playerName,
                    characterCard.name,
                    worldBookOutlets
                ),
                tailSystemPrompt = renderLayer(
                    tailRaw,
                    playerName,
                    characterCard.name,
                    worldBookOutlets
                ),
                stablePrefixCacheable = false
            )
        }

        return PromptCachePromptLayers(
            stableSystemPrompt = renderLayer(
                stableRaw,
                playerName,
                characterCard.name,
                worldBookOutlets
            ),
            dynamicSystemPrompt = renderLayer(
                dynamicRaw,
                playerName,
                characterCard.name,
                worldBookOutlets
            ),
            tailSystemPrompt = renderLayer(
                tailRaw,
                playerName,
                characterCard.name,
                worldBookOutlets
            ),
            stablePrefixCacheable = true
        )
    }

    private fun collectSections(
        characterCard: CharacterCard,
        playerSetting: String?,
        playerName: String?,
        supplementarySetting: String?,
        formatCard: FormatCard?,
        ragResults: List<RetrievedKnowledgeCard>,
        ragInjectionMode: String,
        replyLength: String?,
        replyLanguage: String?,
        longTermMemory: String?,
        worldBookPrompt: String?
    ): List<PromptSection> = buildList {
        addSection(
            PromptLayer.STABLE,
            PromptTemplates.SECTION_CHARACTER,
            buildCharacterSection(characterCard)
        )
        addSection(
            PromptLayer.DYNAMIC,
            PromptTemplates.SECTION_WORLD_BOOK,
            worldBookPrompt.orEmpty()
        )
        addSection(
            PromptLayer.DYNAMIC,
            PromptTemplates.SECTION_REFERENCE,
            buildRagCardsSection(ragResults, ragInjectionMode)
        )
        if (formatCard != null && formatCard.content.isNotBlank()) {
            addSection(
                PromptLayer.STABLE,
                PromptTemplates.SECTION_FORMAT,
                "严格遵循以下【格式要求】，但不要重复输出示例，示例仅为参考\n${formatCard.content}"
            )
        }
        addSection(
            PromptLayer.STABLE,
            PromptTemplates.SECTION_REPLY,
            buildReplyConstraints(replyLength, replyLanguage)
        )
        if (!longTermMemory.isNullOrBlank()) {
            addSection(
                PromptLayer.DYNAMIC,
                PromptTemplates.SECTION_LONG_TERM_MEMORY,
                "以下是本次扮演截至目前的长期记忆" +
                    "参考长期记忆来完成扮演设计.\n$longTermMemory"
            )
        }
        addSection(
            PromptLayer.STABLE,
            PromptTemplates.SECTION_SUPPLEMENTARY,
            supplementarySetting.orEmpty()
        )
        val personal = buildString {
            if (!playerName.isNullOrBlank()) appendLine("玩家名称: $playerName")
            if (!playerSetting.isNullOrBlank()) appendLine(playerSetting)
        }.trim()
        addSection(PromptLayer.STABLE, PromptTemplates.SECTION_PLAYER, personal)
        addSection(
            PromptLayer.STABLE,
            PromptTemplates.SECTION_CORE,
            resolveSystemPrompt(characterCard)
        )
        addSection(
            PromptLayer.TAIL,
            PromptTemplates.SECTION_POST_HISTORY,
            resolvePostHistory(characterCard)
        )
    }

    private fun MutableList<PromptSection>.addSection(
        layer: PromptLayer,
        title: String,
        content: String
    ) {
        content.trim().takeIf(String::isNotEmpty)?.let { normalized ->
            add(PromptSection(layer, title, normalized))
        }
    }

    private fun renderSections(sections: List<PromptSection>): String = sections.joinToString("\n\n") {
        "【${it.title}】\n${it.content}"
    }

    private fun String.appendHeadingIf(condition: Boolean, title: String): String {
        if (!condition) return this
        return listOf(this, "【$title】")
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    private fun buildReplyConstraints(replyLength: String?, replyLanguage: String?): String {
        val constraints = buildString {
            if (!replyLength.isNullOrBlank()) {
                appendLine(PromptTemplates.replyLengthConstraint(replyLength))
            }
            if (!replyLanguage.isNullOrBlank()) {
                appendLine(PromptTemplates.replyLanguageConstraint(replyLanguage))
            }
        }.trim()
        val effectiveConstraints = constraints.ifBlank {
            "请按照【300字短篇】的长度要求构建正文进行回复"
        }
        return "【字数长度要求仅影响输出正文部分，确保正文字数符合字数要求，状态栏等格式文本不计入字数】\n$effectiveConstraints"
    }

    private fun resolveSystemPrompt(characterCard: CharacterCard): String =
        characterCard.systemPrompt.takeIf { it.isNotBlank() }
            ?.replace("{{original}}", PromptTemplates.systemPromptTemplate().trimIndent().trim())
            ?: PromptTemplates.systemPromptTemplate().trimIndent().trim()

    private fun resolvePostHistory(characterCard: CharacterCard): String =
        characterCard.postHistoryInstructions.takeIf { it.isNotBlank() }
            ?.replace("{{original}}", PromptTemplates.postHistoryInstructionsTemplate().trimIndent().trim())
            ?: PromptTemplates.postHistoryInstructionsTemplate().trimIndent().trim()

    private fun renderLayer(
        raw: String,
        playerName: String?,
        botName: String,
        worldBookOutlets: Map<String, String>
    ): String {
        val withOutlets = if (worldBookOutlets.isEmpty()) raw else {
            OUTLET_TOKEN_REGEX.replace(raw) { match ->
                worldBookOutlets[match.groupValues[1]] ?: match.value
            }
        }
        return PlaceholderRenderer.render(withOutlets, playerName, botName)
    }

    private fun buildCharacterSection(card: CharacterCard): String {
        val sections = mutableListOf<String>()
        card.basicSetting.trim().takeIf { it.isNotEmpty() }?.let {
            sections += "【基本设定】\n$it"
        }
        val mesExample = card.mesExample.trim().takeIf { it.isNotEmpty() }
            ?.let { "\n\n【对话示例】\n$it" }
            ?: ""
        val characterText = (when (card.editMode) {
            CharacterEditMode.FREEFORM -> card.freeformCharacterText.trim()
            CharacterEditMode.STRUCTURED -> card.characters.mapNotNull { character ->
                buildList {
                    character.name.trim().takeIf { it.isNotEmpty() }?.let { add("角色名称: $it") }
                    character.profile.trim().takeIf { it.isNotEmpty() }?.let { add("简介: $it") }
                    character.appearance.trim().takeIf { it.isNotEmpty() }?.let { add("外貌: $it") }
                    character.clothing.trim().takeIf { it.isNotEmpty() }?.let { add("服装: $it") }
                    character.abilities.trim().takeIf { it.isNotEmpty() }?.let { add("能力: $it") }
                    character.habits.trim().takeIf { it.isNotEmpty() }?.let { add("习惯与爱好: $it") }
                    character.background.trim().takeIf { it.isNotEmpty() }?.let { add("背景经历: $it") }
                    character.relationships.trim().takeIf { it.isNotEmpty() }?.let { add("人际关系: $it") }
                    character.speakingStyle.trim().takeIf { it.isNotEmpty() }?.let { add("语气与口癖: $it") }
                }.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }.joinToString("\n\n")
        }) + mesExample
        if (characterText.isNotBlank()) sections += "【人物设定】\n$characterText"
        return sections.joinToString("\n\n")
    }

    private fun buildRagCardsSection(
        ragResults: List<RetrievedKnowledgeCard>,
        ragInjectionMode: String
    ): String {
        if (ragResults.isEmpty() || ragInjectionMode.equals("OFF", ignoreCase = true)) return ""
        return buildString {
            appendLine(ragInjectionInstruction(ragInjectionMode))
            ragResults.forEachIndexed { index, chunk ->
                appendLine()
                appendLine("[卡片 ${index + 1}]")
                appendLine("类型: ${chunk.typeLabel}")
                appendLine("来源: ${chunk.sourceLabel}")
                appendLine("内容:")
                appendLine(chunk.content)
            }
        }.trimEnd()
    }

    private fun ragInjectionInstruction(mode: String): String = when (mode.uppercase()) {
        "LIGHT" -> "以下内容是系统主动联想到的背景信息。仅在强相关时自然使用；禁止生硬逐条复述，更禁止主动说明来源。"
        "STRONG" -> "以下内容是系统主动联想到的背景信息。优先保持角色扮演自然流畅；当前话题相关时应该主动参考这些设定/记忆，但避免生硬复述。"
        else -> "以下内容是系统主动联想到的背景信息。只在与当前对话自然相关时使用；不要逐条复述，不要强行提及来源，不要把不确定信息当成当前事实。"
    }
}

private val OUTLET_TOKEN_REGEX = Regex("\\{\\{outlet::(\\w+)\\}\\}")
