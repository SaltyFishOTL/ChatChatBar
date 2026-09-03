package com.example.chatbar.domain.image

import com.example.chatbar.domain.prompt.NovelAiCodexEvidence
import com.example.chatbar.domain.prompt.NovelAiTagSearchEvidence
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class NovelAiDesignTurnStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class NovelAiDesignTagEvidenceSnapshot(
    val query: String = "",
    val name: String = "",
    val translatedName: String = "",
    val count: Long = 0L,
    val category: String = ""
) {
    fun toPromptEvidence(): NovelAiTagSearchEvidence = NovelAiTagSearchEvidence(
        query = query,
        name = name,
        translatedName = translatedName,
        count = count,
        category = category
    )

    companion object {
        fun from(value: NovelAiTagSearchEvidence): NovelAiDesignTagEvidenceSnapshot =
            NovelAiDesignTagEvidenceSnapshot(
                query = value.query,
                name = value.name,
                translatedName = value.translatedName,
                count = value.count,
                category = value.category
            )
    }
}

@Serializable
data class NovelAiDesignCodexEvidenceSnapshot(
    val id: String = "",
    val kind: String = "",
    val title: String = "",
    val category: String = "",
    val prompt: String = "",
    val matchedQueries: List<String> = emptyList()
) {
    fun toPromptEvidence(): NovelAiCodexEvidence = NovelAiCodexEvidence(
        id = id,
        kind = kind,
        title = title,
        category = category,
        prompt = prompt,
        matchedQueries = matchedQueries
    )

    companion object {
        fun from(value: NovelAiCodexEvidence): NovelAiDesignCodexEvidenceSnapshot =
            NovelAiDesignCodexEvidenceSnapshot(
                id = value.id,
                kind = value.kind,
                title = value.title,
                category = value.category,
                prompt = value.prompt,
                matchedQueries = value.matchedQueries
            )
    }
}

@Serializable
data class NovelAiDesignResearchSnapshot(
    val tagEvidence: List<NovelAiDesignTagEvidenceSnapshot> = emptyList(),
    val codexEvidence: List<NovelAiDesignCodexEvidenceSnapshot> = emptyList()
) {
    fun promptTagEvidence(): List<NovelAiTagSearchEvidence> =
        tagEvidence.map(NovelAiDesignTagEvidenceSnapshot::toPromptEvidence)

    fun promptCodexEvidence(): List<NovelAiCodexEvidence> =
        codexEvidence.map(NovelAiDesignCodexEvidenceSnapshot::toPromptEvidence)

    companion object {
        fun from(
            tagEvidence: List<NovelAiTagSearchEvidence>,
            codexEvidence: List<NovelAiCodexEvidence>
        ): NovelAiDesignResearchSnapshot = NovelAiDesignResearchSnapshot(
            tagEvidence = tagEvidence.map(NovelAiDesignTagEvidenceSnapshot::from),
            codexEvidence = codexEvidence.map(NovelAiDesignCodexEvidenceSnapshot::from)
        )
    }
}

@Serializable
data class NovelAiDesignReply(
    val plan: NovelAiPromptPlan,
    val targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val designModelId: String = "",
    val naturalLanguageMode: Boolean = false,
    val sceneDescription: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayText: String
        get() = if (naturalLanguageMode) {
            sceneDescription.ifBlank { plan.baseCaption }
        } else {
            plan.baseCaption
        }

    val effectiveSceneDescription: String
        get() = sceneDescription.ifBlank {
            listOf(
                plan.baseCaption,
                plan.characterCaptions.joinToString("\n") { it.prompt }
            ).filter(String::isNotBlank).joinToString("\n")
        }.trim()
}

@Serializable
data class NovelAiDesignTurn(
    val id: String = UUID.randomUUID().toString(),
    val userText: String = "",
    val attachedStudioPrompt: NovelAiPositivePromptSnapshot? = null,
    val designModelId: String = "",
    val targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
    val naturalLanguageMode: Boolean = false,
    val reply: NovelAiDesignReply? = null,
    val status: NovelAiDesignTurnStatus = NovelAiDesignTurnStatus.PENDING,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Serializable
data class NovelAiDesignContextSnapshot(
    val characterPrompt: String = "",
    val characterImagePrompts: List<NovelAiCharacterPromptSource> = emptyList(),
    val finalPromptRequirement: String = ""
)

@Serializable
data class NovelAiDesignConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val turns: List<NovelAiDesignTurn> = emptyList(),
    val initialResearch: NovelAiDesignResearchSnapshot? = null,
    val designContext: NovelAiDesignContextSnapshot = NovelAiDesignContextSnapshot(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    val lastReply: NovelAiDesignReply?
        get() = turns.asReversed().firstNotNullOfOrNull(NovelAiDesignTurn::reply)

    val hasBlockingTurn: Boolean
        get() = turns.lastOrNull()?.let { it.status != NovelAiDesignTurnStatus.COMPLETED } == true

    val latestRegeneratableTurnId: String?
        get() = turns.lastOrNull()
            ?.takeIf { it.status == NovelAiDesignTurnStatus.COMPLETED && it.reply != null }
            ?.id

    fun revisionBaselineFor(turnIndex: Int): NovelAiPromptPlan? {
        val turn = turns.getOrNull(turnIndex) ?: return null
        return turn.attachedStudioPrompt?.toPromptPlan()
            ?: turns.take(turnIndex).asReversed().firstNotNullOfOrNull { it.reply?.plan }
    }

    fun revisionResearchFor(turnIndex: Int): NovelAiDesignResearchSnapshot =
        if (turns.take(turnIndex + 1).any { it.attachedStudioPrompt != null }) {
            NovelAiDesignResearchSnapshot()
        } else {
            initialResearch ?: NovelAiDesignResearchSnapshot()
        }

    companion object {
        fun titleFrom(text: String): String = text
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .take(30)
            .ifBlank { "新对话" }
    }
}

@Serializable
data class NovelAiDesignCurrentState(
    val currentConversationId: String? = null
)

data class NovelAiPromptToolDesignResult(
    val plan: NovelAiPromptPlan,
    val research: NovelAiDesignResearchSnapshot,
    val rawResponse: String = ""
)
