package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiCharacterPromptSource
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignResearchSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTagEvidenceSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiPositivePromptSnapshot
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiStudioDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptToolUiStateTest {
    @Test
    fun `AI design attachment captures only ordered positive prompts`() {
        val draft = NovelAiStudioDraft(
            stylePrompt = "artist:style",
            basePrompt = "2girls, beach",
            characters = listOf(
                NovelAiCharacterPromptDraft(prompt = "first", negativePrompt = "first negative"),
                NovelAiCharacterPromptDraft(prompt = "second", negativePrompt = "second negative")
            ),
            negativePrompt = "base negative"
        )

        assertEquals(
            NovelAiPositivePromptSnapshot("2girls, beach", listOf("first", "second")),
            novelAiDesignPromptAttachment(draft)
        )
        assertEquals(null, novelAiDesignPromptAttachmentError(draft, NovelAiImageModel.V5_FULL))
    }

    @Test
    fun `AI design attachment rejects incomplete or over-limit studio prompt`() {
        assertTrue(
            novelAiDesignPromptAttachmentError(
                NovelAiStudioDraft(basePrompt = " "),
                NovelAiImageModel.V4_5_FULL
            ).orEmpty().contains("基础 Prompt")
        )
        assertTrue(
            novelAiDesignPromptAttachmentError(
                NovelAiStudioDraft(
                    basePrompt = "scene",
                    characters = listOf(NovelAiCharacterPromptDraft(prompt = ""))
                ),
                NovelAiImageModel.V4_5_FULL
            ).orEmpty().contains("空角色 Prompt")
        )
        assertTrue(
            novelAiDesignPromptAttachmentError(
                NovelAiStudioDraft(
                    basePrompt = "scene",
                    characters = List(7) { NovelAiCharacterPromptDraft(prompt = "role $it") }
                ),
                NovelAiImageModel.V4_5_FULL
            ).orEmpty().contains("最多支持 6 个")
        )
    }

    @Test
    fun `attached prompt overrides old reply and resets old research lineage`() {
        val oldPlan = designPlan("old reply")
        val attached = NovelAiPositivePromptSnapshot("attached base", listOf("attached role"))
        val revisedPlan = designPlan("revised attachment")
        val initialResearch = NovelAiDesignResearchSnapshot(
            tagEvidence = listOf(NovelAiDesignTagEvidenceSnapshot(query = "old", name = "old evidence"))
        )
        val conversation = NovelAiDesignConversation(
            initialResearch = initialResearch,
            turns = listOf(
                NovelAiDesignTurn(
                    reply = NovelAiDesignReply(oldPlan),
                    status = NovelAiDesignTurnStatus.COMPLETED
                ),
                NovelAiDesignTurn(
                    attachedStudioPrompt = attached,
                    reply = NovelAiDesignReply(revisedPlan),
                    status = NovelAiDesignTurnStatus.COMPLETED
                ),
                NovelAiDesignTurn(userText = "继续修改")
            )
        )

        assertEquals("attached base", conversation.revisionBaselineFor(1)?.baseCaption)
        assertTrue(conversation.revisionResearchFor(1).tagEvidence.isEmpty())
        assertEquals("revised attachment", conversation.revisionBaselineFor(2)?.baseCaption)
        assertTrue(conversation.revisionResearchFor(2).tagEvidence.isEmpty())
        assertEquals(oldPlan, conversation.copy(turns = conversation.turns.take(1) + NovelAiDesignTurn()).revisionBaselineFor(1))
        assertEquals(initialResearch, conversation.copy(turns = conversation.turns.take(1) + NovelAiDesignTurn()).revisionResearchFor(1))
    }

    @Test
    fun `fullscreen prompt session rejects an external editor revision`() {
        assertTrue(isStudioFullscreenPromptSessionCurrent(12, 12))
        assertFalse(isStudioFullscreenPromptSessionCurrent(12, 13))
    }

    @Test
    fun `natural language AI design always targets V5`() {
        assertEquals(
            NovelAiImageModel.V5_FULL,
            novelAiDesignTargetModel(
                NovelAiStudioDraft(
                    selectedModel = NovelAiImageModel.V4_5_FULL,
                    aiDesignNaturalLanguageMode = true
                )
            )
        )
        assertEquals(
            NovelAiImageModel.V4_5_FULL,
            novelAiDesignTargetModel(
                NovelAiStudioDraft(
                    selectedModel = NovelAiImageModel.V4_5_FULL,
                    aiDesignNaturalLanguageMode = false
                )
            )
        )
    }

    @Test
    fun `regeneration follows current natural language mode while preserving ordinary target`() {
        val originalTurn = NovelAiDesignTurn(
            targetImageModel = NovelAiImageModel.V4_5_FULL,
            naturalLanguageMode = false
        )

        val natural = novelAiDesignRegenerationMode(
            originalTurn,
            NovelAiStudioDraft(
                selectedModel = NovelAiImageModel.V4_5_FULL,
                aiDesignNaturalLanguageMode = true
            )
        )
        val ordinary = novelAiDesignRegenerationMode(
            originalTurn.copy(
                targetImageModel = NovelAiImageModel.V5_FULL,
                naturalLanguageMode = true
            ),
            NovelAiStudioDraft(
                selectedModel = NovelAiImageModel.V4_5_FULL,
                aiDesignNaturalLanguageMode = false
            )
        )

        assertTrue(natural.naturalLanguageMode)
        assertEquals(NovelAiImageModel.V5_FULL, natural.targetImageModel)
        assertFalse(ordinary.naturalLanguageMode)
        assertEquals(NovelAiImageModel.V5_FULL, ordinary.targetImageModel)
    }

    @Test
    fun `new AI design context excludes editable prompts from earlier conversations`() {
        val original = NovelAiStudioDraft(
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "first character")),
            importedCharacterPromptSources = listOf(
                NovelAiCharacterPromptSource(name = "first card", prompt = "first reference")
            ),
            extraRequirement = "first requirement"
        )

        val snapshot = novelAiDesignContextSnapshot(original)
        val changed = original.copy(
            characters = listOf(NovelAiCharacterPromptDraft(prompt = "other conversation")),
            importedCharacterPromptSources = emptyList(),
            extraRequirement = "other requirement"
        )

        assertEquals("", snapshot.characterPrompt)
        assertEquals("first card", snapshot.characterImagePrompts.single().name)
        assertEquals("first reference", snapshot.characterImagePrompts.single().prompt)
        assertEquals("first requirement", snapshot.finalPromptRequirement)
        assertEquals("other conversation", changed.characters.single().prompt)
    }

    @Test
    fun `AI design conversation requires initialized usable model and nonblank input`() {
        val ready = NovelAiDesignUiState(
            initialized = true,
            selectedDesignModelId = "model",
            input = "雨夜窗边"
        )

        assertTrue(ready.canSend)
        assertFalse(ready.copy(initialized = false).canSend)
        assertFalse(ready.copy(selectedDesignModelId = null).canSend)
        assertFalse(ready.copy(modelError = "模型不可用").canSend)
        assertFalse(ready.copy(input = " ").canSend)
    }

    @Test
    fun `failed AI design turn blocks sending until retry succeeds`() {
        val blocked = NovelAiDesignUiState(
            initialized = true,
            selectedDesignModelId = "model",
            input = "增加月光",
            conversation = NovelAiDesignConversation(
                turns = listOf(
                    NovelAiDesignTurn(
                        userText = "初始画面",
                        status = NovelAiDesignTurnStatus.FAILED,
                        error = "invalid JSON"
                    )
                )
            )
        )

        assertFalse(blocked.canSend)
    }

    @Test
    fun `base prompt enables direct generation without helper model`() {
        val state = ImagePromptToolUiState(
            draft = NovelAiStudioDraft(basePrompt = "1girl, rainy street"),
            modelUsable = false
        )
        assertTrue(state.canGenerate)
    }

    @Test
    fun `selected recent image retains owning recipe for apply actions`() {
        val image = NovelAiGenerationHistoryImage(path = "history/image.png", seed = 42L)
        val entry = NovelAiGenerationHistoryEntry(id = "batch", images = listOf(image))
        val state = ImagePromptToolUiState(
            recentHistoryItems = listOf(NovelAiRecentHistoryItem(entry, image)),
            selectedOutputPath = image.path
        )

        assertEquals(entry, state.selectedRecentHistoryItem?.entry)
        assertEquals(42L, state.selectedRecentHistoryItem?.image?.seed)
    }

    @Test
    fun `history apply blocks editing and generation actions`() {
        val state = ImagePromptToolUiState().copy(
            draft = NovelAiStudioDraft(imageDescription = "scene", basePrompt = "tags"),
            applyingHistory = true
        )

        assertFalse(state.canGenerate)
    }

    @Test
    fun `character card import requires loaded idle draft`() {
        val ready = ImagePromptToolUiState().copy(draftLoaded = true)

        assertTrue(ready.canImportCharacterCard)
        assertFalse(ready.copy(draftLoaded = false).canImportCharacterCard)
        assertFalse(ready.copy(phase = ImagePromptToolPhase.DESIGNING).canImportCharacterCard)
        assertFalse(ready.copy(applyingHistory = true).canImportCharacterCard)
    }

    @Test
    fun `draft sync preserves active task phase`() {
        val activePhases = listOf(
            ImagePromptToolPhase.DESIGNING,
            ImagePromptToolPhase.APPLYING_PROMPT,
            ImagePromptToolPhase.GENERATING,
            ImagePromptToolPhase.STREAMING,
            ImagePromptToolPhase.SAVING,
            ImagePromptToolPhase.CANCELLING
        )

        activePhases.forEach { phase ->
            assertEquals(phase, phase.afterDraftSync(basePromptIsBlank = false))
            assertEquals(phase, phase.afterDraftSync(basePromptIsBlank = true))
        }
    }

    @Test
    fun `draft sync refreshes readiness outside active task`() {
        assertEquals(
            ImagePromptToolPhase.IDLE,
            ImagePromptToolPhase.FINISHED.afterDraftSync(basePromptIsBlank = true)
        )
        assertEquals(
            ImagePromptToolPhase.READY,
            ImagePromptToolPhase.FAILED.afterDraftSync(basePromptIsBlank = false)
        )
    }

    private fun designPlan(base: String) = NovelAiPromptPlan(
        baseCaption = base,
        characterCaptions = listOf(
            NovelAiCharacterCaption("role", DesignedCharacterCenter(0.5f, 0.5f))
        )
    )

}
