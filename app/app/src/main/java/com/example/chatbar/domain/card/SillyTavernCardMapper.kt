package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import java.util.UUID

object SillyTavernCardMapper {

    fun toCharacterCardPackage(st: SillyTavernCard): CharacterCardPackage {
        val freeformText = buildString {
            appendLine("【角色名称】")
            appendLine(st.name)
            appendLine()

            if (st.description.isNotBlank()) {
                appendLine("【人物描述】")
                appendLine(translatePlaceholders(st.description))
                appendLine()
            }
            if (st.personality.isNotBlank()) {
                appendLine("【性格特点】")
                appendLine(translatePlaceholders(st.personality))
                appendLine()
            }
            if (st.scenario.isNotBlank()) {
                appendLine("【背景场景】")
                appendLine(translatePlaceholders(st.scenario))
                appendLine()
            }
            if (st.mesExample.isNotBlank()) {
                appendLine("【对话示例】")
                appendLine(translatePlaceholders(st.mesExample))
                appendLine()
            }
        }.trim()

        val mesExample = if (st.mesExample.isNotBlank()) translatePlaceholders(st.mesExample) else ""
        val systemPrompt = st.systemPrompt.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""
        val postHistory = st.postHistoryInstructions.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""

        val card = PackagedCharacterCard(
            name = st.name,
            greeting = translatePlaceholders(st.firstMes),
            alternateGreetings = st.alternateGreetings.map { translatePlaceholders(it) },
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = freeformText,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistory,
            mesExample = mesExample,
            creatorNotes = st.creatorNotes,
            tags = st.tags,
            creator = st.creator,
            characterVersion = st.characterVersion,
            extensions = st.extensions
        )

        return CharacterCardPackage(
            schemaVersion = 4,
            card = card,
            documents = emptyList(),
            images = emptyMap()
        )
    }

    private fun translatePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")
}
