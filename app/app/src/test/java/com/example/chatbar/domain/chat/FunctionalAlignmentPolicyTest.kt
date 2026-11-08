package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.moment.MomentMediaRetention
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FunctionalAlignmentPolicyTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val first = CharacterInfo("p1", "合成人物甲", profile = "synthetic-profile-first")
    private val second = CharacterInfo("p2", "合成人物乙", profile = "synthetic-profile-second")
    private val card = CharacterCard.create("synthetic-card").copy(characters = listOf(first, second))
    private val session = ChatSession.create(card.id, "synthetic-session")

    @Test fun manualParticipationChangesRequestViewWithoutChangingCardOrOtherSettings() {
        val changed = SessionCharacterParticipationPolicy.change(session, card, first.id, false)
        val effective = SessionCharacterParticipationPolicy.effectiveCard(changed, card)
        assertEquals(listOf(second), effective.characters)
        assertEquals(listOf(first, second), card.characters)
        assertEquals(session, changed.copy(characterParticipation = emptyList()))
        val enabled = SessionCharacterParticipationPolicy.change(changed, card, first.id, true)
        assertEquals(session, enabled)
    }

    @Test fun cardGateWinsAndFreeformTextIsNotRewritten() {
        val disabled = card.copy(characters = listOf(first.copy(enabled = false), second))
        assertThrows(IllegalArgumentException::class.java) {
            SessionCharacterParticipationPolicy.change(session, disabled, first.id, true)
        }
        assertEquals(listOf(second), SessionCharacterParticipationPolicy.effectiveCard(session, disabled).characters)
        val freeform = disabled.copy(editMode = CharacterEditMode.FREEFORM, freeformCharacterText = "synthetic-freeform")
        assertSame(freeform, SessionCharacterParticipationPolicy.effectiveCard(session, freeform))
    }

    @Test fun promptPathsOmitDisabledStructuredProfileAndRetainWorldbookData() {
        val disabled = card.copy(characters = listOf(first.copy(enabled = false), second))
        val assembler = PromptAssembler()
        val marker = "synthetic-worldbook-for-disabled-person"
        val full = assembler.assembleSystemPrompt(disabled, worldBookPrompt = marker)
        val cached = assembler.assembleCachePromptLayers(disabled, worldBookPrompt = marker)
        for (text in listOf(full, cached.stableContextSystemPrompt)) {
            assertFalse(text.contains(first.profile))
            assertTrue(text.contains(second.profile))
        }
        assertTrue(full.contains(marker))
    }

    @Test fun replySpeakerRequirementsUseTheSameEffectivePeopleAsTheProfile() {
        val changed = SessionCharacterParticipationPolicy.change(session, card, first.id, false)
        val effective = SessionCharacterParticipationPolicy.effectiveCard(changed, card)
        val tail = com.example.chatbar.domain.prompt.PromptTemplates.replyTailSystemPrompt(
            replyLength = 900, roleplaySpeakerFormatEnabled = true, characterNames = effective.characters.map { it.name }
        )
        assertFalse(tail.contains(first.name))
        assertTrue(tail.contains(second.name))
        assertEquals(listOf(first, second), card.characters)
    }

    @Test fun anotherCardDoesNotInheritManualDisabledPeopleAndSwitchingBackKeepsThem() {
        val off = SessionCharacterParticipationPolicy.change(session, card, first.id, false)
        val other = card.copy(id = "other-card")
        assertEquals(other, SessionCharacterParticipationPolicy.effectiveCard(off.copy(characterCardId = other.id), other))
        assertEquals(listOf(second), SessionCharacterParticipationPolicy.effectiveCard(off, card).characters)
        assertThrows(IllegalArgumentException::class.java) {
            SessionCharacterParticipationPolicy.change(session, card, "unknown", false)
        }
    }

    @Test fun legacyDesktopGatesReadIntoCanonicalPortableFields() {
        val person = json.decodeFromString(CharacterInfo.serializer(),
            """{"id":"legacy-person","name":"synthetic","desktopEnabled":false}""")
        assertFalse(person.enabled)
        val encoded = json.encodeToString(CharacterInfo.serializer(), person)
        assertTrue(encoded.contains("\"enabled\":false"))
        assertFalse(encoded.contains("desktopEnabled"))
        val legacy = json.decodeFromString(ChatSession.serializer(),
            """{"id":"s","characterCardId":"c","title":"synthetic","createdAt":1,"updatedAt":1,"desktopCharacterParticipation":[{"cardId":"c","disabledPersonIds":["p"]}]}""")
        assertEquals(setOf("p"), legacy.characterParticipation.single().disabledPersonIds)
    }

    @Test fun galleryReferencesSurvivePortableCardRoundTripAndMissingImagesAreRejected() {
        val pkg = CharacterCardPackage(card = PackagedCharacterCard("synthetic", chatBackgroundResourceId = "a",
            chatBackgroundImageResourceIds = listOf("a", "b")),
            images = mapOf("a" to PackagedImage("a.png", "synthetic-a"), "b" to PackagedImage("b.png", "synthetic-b")))
        val decoded = json.decodeFromString(CharacterCardPackage.serializer(), json.encodeToString(CharacterCardPackage.serializer(), pkg))
        decoded.validateForImport()
        assertEquals(listOf("a", "b"), decoded.card.chatBackgroundImageResourceIds)
        assertThrows(IllegalArgumentException::class.java) { decoded.copy(images = decoded.images - "b").validateForImport() }
    }

    @Test fun galleryCleanupRetainsConversationAndArchiveReferences() {
        val a = "synthetic-background-a.png"
        val b = "synthetic-background-b.png"
        val refs = CharacterBackgroundRetention.references(emptyList(), listOf(session.copy(chatBackground = a)),
            emptyList(), listOf(SaveSlotSummary("slot", session.id, "synthetic", null, 0, 1, chatBackground = b)))
        assertTrue(MomentMediaRetention.unreferencedPaths(listOf(a, b), emptyList(), refs).isEmpty())
        assertEquals(listOf(b), MomentMediaRetention.unreferencedPaths(listOf(a, b), emptyList(), listOf(a)))
    }
}
