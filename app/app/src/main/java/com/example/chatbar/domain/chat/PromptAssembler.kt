package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import com.example.chatbar.domain.prompt.PromptTemplates

/**
 * 提示词组装器 — 将各部分设定组装成完整的 system prompt
 */
class PromptAssembler {

    /**
     * 组装完整的系统提示词
     *
     * 组装顺序（最重要/靠下原则）：
     * 1. 角色卡描述、RAG上下文、格式要求、回复字数与语言约束（顶部 - 其他信息）
     * 2. 补充设定 (中下)
     * 3. 个人设定与玩家名称 (下)
     * 4. 核心系统指令 (最下层，最接近对话消息，起强约束效果)
     *
     * @param characterCard       角色卡
     * @param playerSetting       玩家角色设定（可选）
     * @param playerName          玩家名字（可选，用于替换 ${"$"}username）
     * @param supplementarySetting 补充设定（可选）
     * @param formatCard          格式卡片（prompt 模板，可选）
     * @param ragResults          RAG 检索结果
     * @param replyLength         回复长度要求（如"简短"、"详细"）
     * @param replyLanguage       回复语言（如"中文"、"日语"）
     * @return 组装好的系统提示词（${"$"}username 与 ${"$"}botname 已替换为对应名称）
     */
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
        worldBookPrompt: String? = null,
        worldBookOutlets: Map<String, String> = emptyMap()
    ): String {
        val rawPrompt = buildString {
            // 1. 其他设定（角色描述、RAG上下文、格式要求等）
            val characterSection = buildCharacterSection(characterCard)
            if (characterSection.isNotBlank()) {
                appendLine("==================================================")
                appendLine("1. 角色卡基本描述与设定")
                appendLine("==================================================")
                appendSection(characterSection)
            }

            if (!worldBookPrompt.isNullOrBlank()) {
                appendLine("\n==================================================")
                appendLine("1.5 世界设定 (World Book)")
                appendLine("==================================================")
                appendSection(worldBookPrompt)
            }

            val ragSection = buildRagCardsSection(ragResults, ragInjectionMode)
            if (ragSection.isNotBlank()) {
                appendLine("\n==================================================")
                appendLine("2. 检索参考背景与知识库信息 (RAG Context)")
                appendLine("==================================================")
                appendSection(ragSection)
            }

            if (formatCard != null && formatCard.content.isNotBlank()) {
                appendLine("\n==================================================")
                appendLine("3. 输出格式与规范要求 (Format Constraint)")
                appendLine("==================================================")
                appendSection("严格遵循以下【格式要求】，但不要重复输出示例，示例仅为参考\n${formatCard.content}")
            }

            val constraintBuilder = StringBuilder()
            if (!replyLength.isNullOrBlank()) {
                constraintBuilder.appendLine(PromptTemplates.replyLengthConstraint(replyLength))
            }
            if (!replyLanguage.isNullOrBlank()) {
                constraintBuilder.appendLine(PromptTemplates.replyLanguageConstraint(replyLanguage))
            }
            val constraints = constraintBuilder.toString().trim()
            if (constraints.isNotBlank()) {
                appendLine("\n==================================================")
                appendLine("4. 回复偏好与语言约束")
                appendLine("==================================================")
                appendSection("【回复约束】【字数长度要求仅影响输出正文部分，确保正文字数符合字数要求，状态栏等格式文本不计入字数】\n$constraints")
            }
            else {
                appendLine("\n==================================================")
                appendLine("4. 回复偏好与语言约束")
                appendLine("==================================================")
                appendSection("【回复约束】【字数长度要求仅影响输出正文部分，确保正文字数符合字数要求，状态栏等格式文本不计入字数】\n请按照【300字短篇】的长度要求构建正文进行回复")
            }

            // 2. 补充设定
            if (!supplementarySetting.isNullOrBlank()) {
                appendLine("\n==================================================")
                appendLine("5. 本次对话临时/补充设定 (Supplementary Settings)")
                appendLine("==================================================")
                appendSection("【补充设定】\n$supplementarySetting")
            }

            // 3. 个人设定 (含玩家名称)
            val personalBuilder = StringBuilder()
            if (!playerName.isNullOrBlank()) {
                personalBuilder.appendLine("玩家名称: $playerName")
            }
            if (!playerSetting.isNullOrBlank()) {
                personalBuilder.appendLine(playerSetting)
            }
            val personalStr = personalBuilder.toString().trim()
            if (personalStr.isNotBlank()) {
                appendLine("\n==================================================")
                appendLine("6. 玩家人设与身份设定 (Player Profile)")
                appendLine("==================================================")
                appendSection("【个人设定】\n$personalStr")
            }

            // 4. 系统指令沉底
            val systemPrompt = characterCard.systemPrompt.takeIf { it.isNotBlank() }
                ?.replace("{{original}}", PromptTemplates.systemPromptTemplate().trimIndent().trim())
                ?: PromptTemplates.systemPromptTemplate().trimIndent().trim()

            val postHistory = characterCard.postHistoryInstructions.takeIf { it.isNotBlank() }
                ?.replace("{{original}}", PromptTemplates.postHistoryInstructionsTemplate().trimIndent().trim())
                ?: PromptTemplates.postHistoryInstructionsTemplate().trimIndent().trim()

            appendLine("\n==================================================")
            appendLine("7. 系统核心行为指令 (System Instruction)")
            appendLine("==================================================")
            appendSection(systemPrompt)

            appendLine("\n==================================================")
            appendLine("8. 后置强制指令 (Post-History Instructions)")
            appendLine("==================================================")
            appendSection(postHistory)
        }.trim()

        // 全局替换玩家与当前角色卡名称占位符
        val normalizedPrompt = normalizePlaceholders(rawPrompt)
        val promptWithPlayerName = if (!playerName.isNullOrBlank()) {
            normalizedPrompt.replace("\$username", playerName)
        } else {
            normalizedPrompt
        }
        val promptWithBotName = promptWithPlayerName.replace("\$botname", characterCard.name)
        return if (worldBookOutlets.isNotEmpty()) {
            val outletRegex = Regex("\\{\\{outlet::(\\w+)}}")
            outletRegex.replace(promptWithBotName) { mr ->
                worldBookOutlets[mr.groupValues[1]] ?: mr.value
            }
        } else {
            promptWithBotName
        }
    }

    // ========================= 内部方法 =========================

    /** 构建角色设定部分 */
    private fun buildCharacterSection(card: CharacterCard): String {
        val sections = mutableListOf<String>()
        card.basicSetting.trim().takeIf { it.isNotEmpty() }?.let {
            sections += "【基本设定】\n$it"
        }
        val mesExample = card.mesExample.trim().takeIf { it.isNotEmpty() }?.let { "\n\n【对话示例】\n$it" } ?: ""
        val characterText = (when (card.editMode) {
            CharacterEditMode.FREEFORM -> card.freeformCharacterText.trim()
            CharacterEditMode.STRUCTURED -> card.characters.mapNotNull { char ->
                buildList {
                    char.name.trim().takeIf { it.isNotEmpty() }?.let { add("角色名称: $it") }
                    char.profile.trim().takeIf { it.isNotEmpty() }?.let { add("简介: $it") }
                    char.appearance.trim().takeIf { it.isNotEmpty() }?.let { add("外貌: $it") }
                    char.clothing.trim().takeIf { it.isNotEmpty() }?.let { add("服装: $it") }
                    char.abilities.trim().takeIf { it.isNotEmpty() }?.let { add("能力: $it") }
                    char.habits.trim().takeIf { it.isNotEmpty() }?.let { add("习惯与爱好: $it") }
                    char.background.trim().takeIf { it.isNotEmpty() }?.let { add("背景经历: $it") }
                    char.relationships.trim().takeIf { it.isNotEmpty() }?.let { add("人际关系: $it") }
                    char.speakingStyle.trim().takeIf { it.isNotEmpty() }?.let { add("语气与口癖: $it") }
                }.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }.joinToString("\n\n")
        }) + mesExample
        if (characterText.isNotBlank()) sections += "【人物设定】\n$characterText"
        return sections.joinToString("\n\n")
    }

    /** 追加带分隔的段落 */
    private fun buildRagCardsSection(ragResults: List<RetrievedKnowledgeCard>, ragInjectionMode: String): String {
        if (ragResults.isEmpty()) return ""
        if (ragInjectionMode.equals("OFF", ignoreCase = true)) return ""

        return buildString {
            appendLine("【可参考设定/记忆卡片】")
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

    private fun ragInjectionInstruction(mode: String): String {
        return when (mode.uppercase()) {
            "LIGHT" -> "以下内容是系统主动联想到的背景信息。仅在强相关时自然使用；禁止生硬逐条复述，更禁止主动说明来源。"
            "STRONG" -> "以下内容是系统主动联想到的背景信息。优先保持角色扮演自然流畅；当前话题相关时应该主动参考这些设定/记忆，但避免生硬复述。"
            else -> "以下内容是系统主动联想到的背景信息。只在与当前对话自然相关时使用；不要逐条复述，不要强行提及来源，不要把不确定信息当成当前事实。"
        }
    }

    private fun StringBuilder.appendSection(section: String) {
        if (section.isNotBlank()) {
            if (this.isNotEmpty()) appendLine("\n")
            append(section)
        }
    }

    private fun normalizePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")
}
