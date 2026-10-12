package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.memory.MemoryContinuityPolicy
import com.example.chatbar.domain.memory.MemoryHashes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupMergeEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(root: File, name: String, text: String) = File(root, name).also { it.parentFile!!.mkdirs(); it.writeText(text) }
    private fun asset(root: File, name: String, value: Byte) = File(root, name).also {
        it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(65536) { value })
    }
    private fun card(root: File, id: String, name: String = "角色", speaker: String = "speaker") {
        write(root, "entities/character_cards/$id.json", backupJson.encodeToString(CharacterCard(id = id, name = name,
            characters = listOf(CharacterInfo(speaker, "人物")), createdAt = 1, updatedAt = 2)))
    }
    private fun session(root: File, id: String, owner: String, title: String = "会话") {
        write(root, "entities/chat_sessions/$id.json", backupJson.encodeToString(ChatSession(id = id, characterCardId = owner, title = title, createdAt = 3, updatedAt = 4)))
    }
    private fun message(root: File, id: String, session: String, content: String, path: String? = null) {
        write(root, "entities/chat_messages/${session}_$id.json", backupJson.encodeToString(ChatMessage(id = id, sessionId = session,
            role = MessageRole.USER, content = content, images = listOfNotNull(path), sourceTurnId = "source", sourceTurnOrder = 1,
            createdAt = 5, updatedAt = 6)))
    }
    private fun archive() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
    private fun incoming(source: File, local: File, selection: AppBackupSelection? = null): Pair<File, AppBackupSummary> {
        val selected = selection ?: BackupExportCatalog.read(source) {}.allSelection()
        val file = temporary.newFile()
        file.outputStream().use { archive().create(source, temporary.newFolder(), it, null, 10, "fixture", BackupPreferences(),
            BackupExportCatalog.read(source) {}.plan(selected)) }
        val stage = temporary.newFolder()
        val summary = file.inputStream().use { archive().extract(it, null, stage, local, 10) }
        return stage to summary
    }
    private fun merge(local: File, incoming: Pair<File, AppBackupSummary>, mode: AppBackupRestoreMode): Pair<File, BackupRestorePreview> {
        val work = temporary.newFolder()
        val preview = BackupMergeEngine(local, incoming.first, work, incoming.second, mode, {}).build()
        BackupPreparedSeal.write(work, {})
        BackupPreparedSeal.validate(work)
        return File(work, "files") to preview
    }
    private inline fun <reified T> records(root: File, type: String): List<T> = File(root, "entities/$type").listFiles()
        .orEmpty().filter { it.extension == "json" }.map { backupJson.decodeFromString<T>(it.readText()) }

    @Test fun uniqueNamesUpdateWithinMappedCardAndKeepUnrelatedSessionsAndLocalSourceIntact() {
        val local = temporary.newFolder()
        card(local, "local-card", speaker = "local-speaker")
        session(local, "local-session", "local-card")
        session(local, "kept-session", "local-card", "本机额外会话")
        message(local, "obsolete", "local-session", "旧内容")
        message(local, "kept", "kept-session", "保留内容")
        val before = File(local, "entities/chat_messages/local-session_obsolete.json").readText()
        val source = temporary.newFolder()
        card(source, "source-card", speaker = "source-speaker")
        session(source, "source-session", "source-card")
        message(source, "new-message", "source-session", "更新内容")
        val (staged, preview) = merge(local, incoming(source, local), AppBackupRestoreMode.UPDATE_EXISTING)
        assertEquals(1, preview.counts["character_cards"]!!.updated)
        assertEquals(1, preview.counts["chat_sessions"]!!.updated)
        assertEquals(setOf("local-session", "kept-session"), records<ChatSession>(staged, "chat_sessions").map { it.id }.toSet())
        val changed = records<ChatMessage>(staged, "chat_messages").single { it.content == "更新内容" }
        assertEquals("local-session", changed.sessionId)
        assertTrue(File(staged, "entities/chat_messages/local-session_${changed.id}.json").isFile)
        assertFalse(File(staged, "entities/chat_messages/local-session_obsolete.json").exists())
        assertEquals("保留内容", records<ChatMessage>(staged, "chat_messages").single { it.id == "kept" }.content)
        assertEquals("local-speaker", records<CharacterCard>(staged, "character_cards").single().characters.single().id)
        assertEquals(before, File(local, "entities/chat_messages/local-session_obsolete.json").readText())
    }

    @Test fun ambiguousNamesCreateCopiesAndNeverOverwriteEitherCandidate() {
        val local = temporary.newFolder()
        card(local, "one", "相同名称", "one-speaker")
        card(local, "two", "相同名称", "two-speaker")
        val source = temporary.newFolder()
        card(source, "incoming", "相同名称", "new-speaker")
        val (staged, preview) = merge(local, incoming(source, local), AppBackupRestoreMode.UPDATE_EXISTING)
        val restored = records<CharacterCard>(staged, "character_cards")
        assertEquals(3, restored.size)
        assertTrue(restored.any { it.id == "one" } && restored.any { it.id == "two" })
        assertNotEquals("incoming", restored.single { it.id !in setOf("one", "two") }.id)
        assertTrue(records<CharacterCard>(staged, "character_cards").any { it.name == "相同名称（副本）" })
        assertEquals(0, preview.counts["character_cards"]!!.updated)
        assertEquals(1, preview.counts["character_cards"]!!.copies)
        assertTrue(preview.warnings.isNotEmpty())
    }

    @Test fun copiedSessionsRebindMessagesReadingPositionMemoryHistoryAndIndependentMedia() {
        val local = temporary.newFolder()
        card(local, "card")
        session(local, "session", "card")
        val originalImage = asset(local, "images/generated/session/picture.png", 1)
        message(local, "message", "session", "旧内容", originalImage.path)
        val source = temporary.newFolder()
        card(source, "card")
        session(source, "session", "card")
        val image = asset(source, "images/generated/session/picture.png", 2)
        message(source, "message", "session", "新内容 ${image.path}", image.path)
        val audio = asset(source, "audio/generated/session/voice.mp3", 7)
        val voice = GeneratedVoiceMessage.create(sessionId = "session", messageId = "message", anchorId = "anchor", sourceOrder = 1,
            sourceSegmentKind = "dialogue", sourceSpeakerName = "人物", sourceText = "原语音正文", taggedText = "原语音正文",
            characterId = "speaker", characterName = "人物", voice = FishAudioVoiceBinding("remote-reference", "voice"),
            fishModelId = "remote-model", audioPath = audio.path, durationMs = 100, byteLength = audio.length())
        write(source, "entities/generated_voice_messages/${voice.id}.json", backupJson.encodeToString(voice))
        val anchor = VoiceAnchorState("message", sessionId = "session", displayContentSnapshot = "原语音正文",
            anchors = listOf(VoiceAnchor("anchor", "dialogue", "人物", "原语音正文", 0, 5, 1)))
        write(source, "entities/voice_anchor_states/message.json", backupJson.encodeToString(anchor))
        write(source, "entities/chat_scroll_positions/session.json", backupJson.encodeToString(ChatScrollPosition("session", "message", 0, 27, 1)))
        val leaf = MemoryNode("leaf", "session", MemoryTier.EPISODE, sourceTurnIds = listOf("source"), content = "保留记忆正文",
            sourceHashes = mapOf("source" to "evidence"), sourceFingerprints = mapOf("source" to "fingerprint"), sourceHash = "leaf-proof",
            coverageHash = MemoryHashes.episodeCoverage(listOf("source"), mapOf("source" to "evidence"), "保留记忆正文"))
        val units = MemoryHashes.parentCoverageUnits(listOf(leaf))
        val parent = MemoryNode("parent", "session", MemoryTier.ARC, sourceTurnIds = leaf.sourceTurnIds, childIds = listOf(leaf.id),
            coverageUnits = units, content = "父记忆正文", sourceHash = MemoryHashes.text("${leaf.id}:${leaf.sourceHash}"),
            coverageHash = MemoryHashes.parentCoverage(listOf(leaf), units))
        listOf(leaf, parent).forEach { write(source, "entities/memory_nodes/${it.id}.json", backupJson.encodeToString(it)) }
        val revision = MemoryTierRevision("revision", "session", MemoryTier.ARC, operation = MemoryRevisionOperation.USER_EDIT,
            author = MemoryAuthor.USER, snapshotNodeIds = listOf("parent"), createdAt = 8)
        write(source, "entities/memory_tier_revisions/revision.json", backupJson.encodeToString(revision))
        val state = MemorySessionState("session", arcPage = MemoryPageState(MemoryTier.ARC, listOf("parent"), "revision"),
            timeline = listOf(MemoryTimelineEntry("source", 1, 1)), staleSourcesByNodeId = mapOf("leaf" to listOf("source")))
        write(source, "entities/memory_states/session.json", backupJson.encodeToString(state))
        val (staged, preview) = merge(local, incoming(source, local), AppBackupRestoreMode.ADD_COPIES)
        val copied = records<ChatSession>(staged, "chat_sessions").single { it.id != "session" }
        assertEquals(1, preview.counts["chat_sessions"]!!.copies)
        assertTrue(copied.displayTitleOverride!!.contains("副本"))
        val copiedMessage = records<ChatMessage>(staged, "chat_messages").single { it.sessionId == copied.id }
        assertNotEquals("message", copiedMessage.id)
        assertEquals("source", copiedMessage.sourceTurnId)
        assertTrue(copiedMessage.content.contains(image.path))
        assertNotEquals(originalImage.path, copiedMessage.images.single())
        val relative = File(copiedMessage.images.single()).relativeTo(local).invariantSeparatorsPath
        assertArrayEquals(image.readBytes(), File(staged, relative).readBytes())
        assertArrayEquals(originalImage.readBytes(), File(staged, "images/generated/session/picture.png").readBytes())
        val position = records<ChatScrollPosition>(staged, "chat_scroll_positions").single()
        assertEquals(copied.id, position.sessionId)
        assertEquals(copiedMessage.id, position.anchorMessageId)
        val nodes = records<MemoryNode>(staged, "memory_nodes")
        val newParent = nodes.single { it.tier == MemoryTier.ARC }
        val newLeaf = nodes.single { it.tier == MemoryTier.EPISODE }
        assertNotEquals("leaf", newLeaf.id)
        assertEquals(listOf(newLeaf.id), newParent.childIds)
        assertEquals(newLeaf.sourceFingerprints, leaf.sourceFingerprints)
        assertEquals(listOf(newLeaf.id), newParent.coverageUnits.map { it.sourceId })
        assertTrue(MemoryContinuityPolicy.validateNode(newParent, nodes.associateBy { it.id }, state.timeline).valid)
        val importedState = records<MemorySessionState>(staged, "memory_states").single()
        val importedRevision = records<MemoryTierRevision>(staged, "memory_tier_revisions").single()
        assertEquals(copied.id, importedState.sessionId)
        assertEquals(listOf(newParent.id), importedState.arcPage.activeNodeIds)
        assertEquals(importedRevision.id, importedState.arcPage.currentRevisionId)
        assertEquals(listOf(newParent.id), importedRevision.snapshotNodeIds)
        assertEquals(setOf(newLeaf.id), importedState.staleSourcesByNodeId.keys)
        assertEquals("旧内容", records<ChatMessage>(staged, "chat_messages").single { it.id == "message" }.content)
        val copiedVoice = records<GeneratedVoiceMessage>(staged, "generated_voice_messages").single()
        assertNotEquals(voice.id, copiedVoice.id)
        assertEquals(copied.id, copiedVoice.sessionId)
        assertEquals(copiedMessage.id, copiedVoice.messageId)
        assertEquals(records<CharacterCard>(staged, "character_cards").single { it.id == copied.characterCardId }.characters.single().id, copiedVoice.characterId)
        assertEquals("remote-reference", copiedVoice.voice.referenceId)
        assertEquals("remote-model", copiedVoice.fishModelId)
        assertArrayEquals(audio.readBytes(), File(staged, File(copiedVoice.audioPath).relativeTo(local).path).readBytes())
        val copiedAnchor = records<VoiceAnchorState>(staged, "voice_anchor_states").single()
        assertEquals(copiedMessage.id, copiedAnchor.messageId)
        assertEquals(copied.id, copiedAnchor.sessionId)
        assertEquals(copiedVoice.anchorId, copiedAnchor.anchors.single().id)
        assertTrue(File(staged, "entities/voice_anchor_states/${copiedMessage.id}.json").isFile)
    }

    @Test fun partialSettingsAndUnselectedCredentialsRemainLocalInBothIncrementalModes() {
        for (mode in AppBackupRestoreMode.entries) {
            val local = temporary.newFolder()
            val localSettings = AppSettings(themeMode = ThemeMode.LIGHT, siliconFlowApiKey = "local-secret", chatBubbleFontScale = 1.7f,
                webSearchSettingsVersion = CURRENT_WEB_SEARCH_SETTINGS_VERSION, webSearchEnabled = false,
                characterAutoFillWebSearchEnabled = false, characterRewriteWebSearchEnabled = false,
                characterAutoFillResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS,
                characterRewriteResearchSourceMode = CharacterResearchSourceMode.NONE, webSearchMaxResultsPerQuery = 4,
                momentsAutoStartConfirmed = true)
            write(local, "entities/app_settings.json", backupJson.encodeToString(localSettings))
            write(local, "entities/novelai_studio_draft.json", backupJson.encodeToString(NovelAiStudioDraft(basePrompt = "本机草稿")))
            val source = temporary.newFolder()
            write(source, "entities/app_settings.json", backupJson.encodeToString(AppSettings(themeMode = ThemeMode.DARK, siliconFlowApiKey = "source-secret")))
            val stage = incoming(source, local, AppBackupSelection(globalOptions = setOf("themeMode")))
            val (staged, _) = merge(local, stage, mode)
            val settings = backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText())
            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals("local-secret", settings.siliconFlowApiKey)
            assertEquals(1.7f, settings.chatBubbleFontScale)
            assertEquals(localSettings.copy(themeMode = ThemeMode.DARK), settings)
            assertEquals("本机草稿", backupJson.decodeFromString<NovelAiStudioDraft>(File(staged, "entities/novelai_studio_draft.json").readText()).basePrompt)
            val preferences = backupJson.decodeFromString<BackupPreferences>(File(staged.parentFile, "preferences.json").readText())
            assertEquals(BackupDataRegistry.preferences, preferences.retainedPreferences)
        }
    }

    @Test fun scopedReplacementKeepsUnselectedMediaFamiliesAndUnselectedSessions() {
        val local = temporary.newFolder()
        card(local, "card"); session(local, "session", "card"); session(local, "kept", "card", "未选会话")
        val image = asset(local, "images/generated/session/original.png", 4)
        val audio = asset(local, "audio/generated/session/original.mp3", 5)
        val metadata = GeneratedImageMetadata(image.path, "原图参数", negativePrompt = "", sizePreset = "custom", width = 64, height = 64)
        val original = ChatMessage("message", "session", MessageRole.USER, "本机正文", images = listOf(image.path),
            generatedImageMetadata = listOf(metadata), createdAt = 1, updatedAt = 2)
        write(local, "entities/chat_messages/session_message.json", backupJson.encodeToString(original))
        message(local, "local-only", "session", "未选图片的原消息", image.path)
        message(local, "kept-message", "kept", "未选会话正文")
        val voice = GeneratedVoiceMessage.create(sessionId = "session", messageId = "message", anchorId = "anchor", sourceOrder = 1,
            sourceSegmentKind = "dialogue", sourceSpeakerName = "人物", sourceText = "原文", taggedText = "原文",
            characterId = "speaker", characterName = "人物", voice = FishAudioVoiceBinding("remote", "声音"),
            fishModelId = "remote-model", audioPath = audio.path, durationMs = 100, byteLength = audio.length())
        write(local, "entities/generated_voice_messages/${voice.id}.json", backupJson.encodeToString(voice))
        val anchor = VoiceAnchorState("message", sessionId = "session", displayContentSnapshot = "原文")
        write(local, "entities/voice_anchor_states/message.json", backupJson.encodeToString(anchor))
        val slot = SaveSlot(id = "slot", sessionId = "session", name = "未选存档", createdAt = 1)
        write(local, "entities/save_slots/slot.json", backupJson.encodeToString(slot))
        val post = MomentPost("post", "card", "session", senderName = "人物", text = "旧动态", imagePath = image.path,
            generatedImageMetadata = metadata, scheduledAt = 1, generatedAt = 2)
        write(local, "entities/moment_posts/post.json", backupJson.encodeToString(post))
        write(local, "entities/moment_posts/local-post.json", backupJson.encodeToString(post.copy(id = "local-post", text = "未选配图原动态")))
        val history = NovelAiGenerationHistoryEntry("history", listOf(NovelAiGenerationHistoryImage(image.path, 42)),
            NovelAiGenerationRecipe(basePrompt = "旧参数"), createdAt = 3)
        write(local, "entities/novelai_generation_history/history.json", backupJson.encodeToString(history))
        write(local, "entities/novelai_generation_history/local-history.json", backupJson.encodeToString(history.copy(id = "local-history")))

        val source = temporary.newFolder()
        card(source, "card"); session(source, "session", "card")
        message(source, "message", "session", "新正文")
        message(source, "new-message", "session", "新增无图消息")
        write(source, "entities/moment_posts/post.json", backupJson.encodeToString(post.copy(text = "新动态", imagePath = null, generatedImageMetadata = null)))
        write(source, "entities/novelai_generation_history/history.json", backupJson.encodeToString(history.copy(
            images = listOf(NovelAiGenerationHistoryImage("/old-device/no-image.png", 42)), recipe = NovelAiGenerationRecipe(basePrompt = "新参数"))))
        val selection = AppBackupSelection(characterIds = setOf("card"), sessions = mapOf("session" to BackupSessionChoice(false, false, false)),
            moments = true, momentsImages = false, studioHistory = true, studioHistoryImages = false)
        val (staged, preview) = merge(local, incoming(source, local, selection), AppBackupRestoreMode.REPLACE_ALL)
        val messages = records<ChatMessage>(staged, "chat_messages")
        val changed = messages.single { it.id == "message" }
        assertEquals("新正文", changed.content)
        assertEquals(original.images, changed.images)
        assertEquals(original.generatedImageMetadata, changed.generatedImageMetadata)
        assertTrue(messages.single { it.id == "new-message" }.images.isEmpty())
        assertEquals("未选图片的原消息", messages.single { it.id == "local-only" }.content)
        assertEquals("未选会话正文", messages.single { it.id == "kept-message" }.content)
        assertEquals(voice, records<GeneratedVoiceMessage>(staged, "generated_voice_messages").single())
        assertEquals(anchor, records<VoiceAnchorState>(staged, "voice_anchor_states").single())
        assertEquals(slot, records<SaveSlot>(staged, "save_slots").single())
        val changedPost = records<MomentPost>(staged, "moment_posts").single { it.id == "post" }
        assertEquals("新动态", changedPost.text)
        assertEquals(post.imagePath, changedPost.imagePath)
        assertEquals(post.generatedImageMetadata, changedPost.generatedImageMetadata)
        val changedHistory = records<NovelAiGenerationHistoryEntry>(staged, "novelai_generation_history").single { it.id == "history" }
        assertEquals("新参数", changedHistory.recipe.basePrompt)
        assertEquals(history.images, changedHistory.images)
        assertEquals("未选配图原动态", records<MomentPost>(staged, "moment_posts").single { it.id == "local-post" }.text)
        assertEquals(history.images, records<NovelAiGenerationHistoryEntry>(staged, "novelai_generation_history").single { it.id == "local-history" }.images)
        assertEquals(1, preview.counts["chat_sessions"]!!.updated)
        listOf(image, audio).forEach { file -> assertArrayEquals(file.readBytes(), File(staged, file.relativeTo(local).path).readBytes()) }
        assertEquals(original, backupJson.decodeFromString<ChatMessage>(File(local, "entities/chat_messages/session_message.json").readText()))
    }

    @Test fun scopedReplacementDistinguishesSelectedEmptyCollectionsFromUnselectedOnes() {
        for (selected in listOf(false, true)) {
            val local = temporary.newFolder()
            val post = MomentPost("post", "card", "session", senderName = "人物", text = "动态", scheduledAt = 1, generatedAt = 2)
            write(local, "entities/moment_posts/post.json", backupJson.encodeToString(post))
            val history = NovelAiGenerationHistoryEntry("history", emptyList(), NovelAiGenerationRecipe(), createdAt = 3)
            write(local, "entities/novelai_generation_history/history.json", backupJson.encodeToString(history))
            write(local, "entities/novelai_studio_draft.json", backupJson.encodeToString(NovelAiStudioDraft(basePrompt = "未选工作室")))
            val source = temporary.newFolder()
            val selection = AppBackupSelection(globalOptions = setOf("themeMode"), moments = selected, studioHistory = selected)
            val (staged, preview) = merge(local, incoming(source, local, selection), AppBackupRestoreMode.REPLACE_ALL)
            assertEquals(!selected, File(staged, "entities/moment_posts/post.json").exists())
            assertEquals(!selected, File(staged, "entities/novelai_generation_history/history.json").exists())
            assertEquals("未选工作室", backupJson.decodeFromString<NovelAiStudioDraft>(File(staged, "entities/novelai_studio_draft.json").readText()).basePrompt)
            if (selected) {
                assertEquals(1, preview.counts["moment_posts"]!!.removed)
                assertEquals(1, preview.counts["novelai_generation_history"]!!.removed)
            }
            assertTrue(File(local, "entities/moment_posts/post.json").exists())
        }
    }

    @Test fun explicitlyIncludedEmptySessionMediaReplacesWhileUnselectedVoiceKeepsItsMessage() {
        for (voiceSelected in listOf(false, true)) {
            val local = temporary.newFolder()
            card(local, "card"); session(local, "session", "card")
            val audio = asset(local, "audio/generated/session/clip.mp3", 6)
            message(local, "voice-message", "session", "语音原消息")
            val voice = GeneratedVoiceMessage.create(sessionId = "session", messageId = "voice-message", anchorId = "anchor", sourceOrder = 1,
                sourceSegmentKind = "dialogue", sourceSpeakerName = "人物", sourceText = "原文", taggedText = "原文",
                characterId = "speaker", characterName = "人物", voice = FishAudioVoiceBinding("remote", "声音"),
                fishModelId = "remote-model", audioPath = audio.path, durationMs = 100, byteLength = audio.length())
            write(local, "entities/generated_voice_messages/${voice.id}.json", backupJson.encodeToString(voice))
            write(local, "entities/save_slots/slot.json", backupJson.encodeToString(SaveSlot(id = "slot", sessionId = "session", name = "旧存档", createdAt = 1)))
            val source = temporary.newFolder()
            card(source, "card"); session(source, "session", "card")
            val selection = AppBackupSelection(characterIds = setOf("card"), sessions = mapOf("session" to BackupSessionChoice(true, voiceSelected, true)))
            val (staged, _) = merge(local, incoming(source, local, selection), AppBackupRestoreMode.REPLACE_ALL)
            assertEquals(!voiceSelected, File(staged, "entities/generated_voice_messages/${voice.id}.json").isFile)
            assertEquals(!voiceSelected, File(staged, "entities/chat_messages/session_voice-message.json").isFile)
            assertFalse(File(staged, "entities/save_slots/slot.json").exists())
        }
    }

    @Test fun selectedEmptyModelsKeepDependenciesOfUnselectedLocalSettings() {
        val local = temporary.newFolder()
        write(local, "entities/app_settings.json", backupJson.encodeToString(AppSettings(defaultModelId = "needed", themeMode = ThemeMode.DARK,
            webSearchSettingsVersion = CURRENT_WEB_SEARCH_SETTINGS_VERSION)))
        val model = ModelConfig("needed", "需要的模型", "https://example.test", "local-key", "model", createdAt = 1)
        write(local, "entities/model_configs/needed.json", backupJson.encodeToString(model))
        write(local, "entities/model_configs/spare.json", backupJson.encodeToString(model.copy(id = "spare")))
        val source = temporary.newFolder()
        val selection = AppBackupSelection(globalOptions = setOf("models", "themeMode"))
        val (staged, preview) = merge(local, incoming(source, local, selection), AppBackupRestoreMode.REPLACE_ALL)
        assertTrue(File(staged, "entities/model_configs/needed.json").isFile)
        assertFalse(File(staged, "entities/model_configs/spare.json").exists())
        val settings = backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText())
        assertEquals("needed", settings.defaultModelId)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(1, preview.counts["model_configs"]!!.removed)
        assertTrue(preview.warnings.isNotEmpty())
    }

    @Test fun metadataOnlyLocalHistoryUpdatesItsSeedsWhenNoOriginalMediaExistsToRetain() {
        val local = temporary.newFolder()
        val old = NovelAiGenerationHistoryEntry("history", listOf(NovelAiGenerationHistoryImage("chatbar-save-slot-omitted-image:old", 7)),
            NovelAiGenerationRecipe(basePrompt = "old"), createdAt = 1)
        write(local, "entities/novelai_generation_history/history.json", backupJson.encodeToString(old))
        val source = temporary.newFolder()
        write(source, "entities/novelai_generation_history/history.json", backupJson.encodeToString(old.copy(
            images = listOf(NovelAiGenerationHistoryImage("/source/omitted.png", 99)), recipe = NovelAiGenerationRecipe(basePrompt = "new"))))
        val input = incoming(source, local, AppBackupSelection(studioHistory = true, studioHistoryImages = false))
        val (staged, _) = merge(local, input, AppBackupRestoreMode.REPLACE_ALL)
        val changed = records<NovelAiGenerationHistoryEntry>(staged, "novelai_generation_history").single()
        assertEquals(99L, changed.images.single().seed)
        assertTrue(changed.images.single().path.startsWith("chatbar-save-slot-omitted-image:"))
        assertEquals("new", changed.recipe.basePrompt)
    }

    @Test fun unselectedDependencyConfigsAndCompletedMigrationsStayLocalDuringScopedReplacement() {
        val local = temporary.newFolder()
        val original = ModelConfig("model", "本机模型", "https://local.example.test", "local-secret", "local-model", createdAt = 1)
        write(local, "entities/model_configs/model.json", backupJson.encodeToString(original))
        write(local, "entities/model_configuration_migration.json", """{"version":5}""")
        val source = temporary.newFolder()
        card(source, "card")
        write(source, "entities/chat_sessions/session.json", backupJson.encodeToString(ChatSession("session", "card", "会话", modelId = "model", createdAt = 1, updatedAt = 2)))
        write(source, "entities/model_configs/model.json", backupJson.encodeToString(original.copy(apiKey = "source-secret", modelName = "source-model")))
        write(source, "entities/model_configuration_migration.json", """{"version":1}""")
        val selection = AppBackupSelection(characterIds = setOf("card"), sessions = mapOf("session" to BackupSessionChoice()))
        val input = incoming(source, local, selection)
        assertTrue(input.second.requiresRestorePreview(AppBackupRestoreMode.REPLACE_ALL))
        assertFalse(input.second.copy(scope = null).requiresRestorePreview(AppBackupRestoreMode.REPLACE_ALL))
        val (staged, _) = merge(local, input, AppBackupRestoreMode.REPLACE_ALL)
        assertEquals(original, records<ModelConfig>(staged, "model_configs").single())
        assertEquals("model", records<ChatSession>(staged, "chat_sessions").single().modelId)
        assertEquals("""{"version":5}""", File(staged, "entities/model_configuration_migration.json").readText())
    }

    @Test fun legacySearchSettingsMigrateBeforeOverlayWithoutResettingOtherLocalSearchFields() {
        val local = temporary.newFolder()
        val before = AppSettings(webSearchSettingsVersion = CURRENT_WEB_SEARCH_SETTINGS_VERSION,
            characterAutoFillResearchSourceMode = CharacterResearchSourceMode.MANUAL_URLS,
            characterRewriteResearchSourceMode = CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS,
            webSearchMaxResultsPerQuery = 4)
        write(local, "entities/app_settings.json", backupJson.encodeToString(before))
        val source = temporary.newFolder()
        write(source, "entities/app_settings.json", backupJson.encodeToString(AppSettings(webSearchSettingsVersion = 3, webSearchEnabled = false)))
        val input = incoming(source, local, AppBackupSelection(globalOptions = setOf("characterAutoFillResearchSourceMode")))
        val (staged, _) = merge(local, input, AppBackupRestoreMode.UPDATE_EXISTING)
        val after = backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText())
        assertEquals(before.copy(characterAutoFillResearchSourceMode = CharacterResearchSourceMode.NONE), after)
    }

    @Test fun updateMatchesHistoryByOriginalMediaBytesAcrossPathsAndKeepsLocalOnlyRecords() {
        val local = temporary.newFolder()
        val localImage = asset(local, "images/generated/history/local.png", 3)
        val recipe = NovelAiGenerationRecipe(basePrompt = "prompt")
        val old = NovelAiGenerationHistoryEntry("local-id", listOf(NovelAiGenerationHistoryImage(localImage.path, 42)), recipe, createdAt = 10)
        write(local, "entities/novelai_generation_history/local-id.json", backupJson.encodeToString(old))
        val source = temporary.newFolder()
        val sourceImage = asset(source, "images/generated/history/source.png", 3)
        val copy = old.copy(id = "source-id", images = listOf(NovelAiGenerationHistoryImage(sourceImage.path, 42)))
        write(source, "entities/novelai_generation_history/source-id.json", backupJson.encodeToString(copy))
        val input = incoming(source, local, AppBackupSelection(studioHistory = true))
        val (staged, preview) = merge(local, input, AppBackupRestoreMode.UPDATE_EXISTING)
        assertEquals(1, preview.counts["novelai_generation_history"]!!.updated)
        assertEquals("local-id", records<NovelAiGenerationHistoryEntry>(staged, "novelai_generation_history").single().id)
        val (duplicates, duplicated) = merge(local, input, AppBackupRestoreMode.ADD_COPIES)
        assertEquals(2, records<NovelAiGenerationHistoryEntry>(duplicates, "novelai_generation_history").size)
        assertEquals(0, duplicated.counts["novelai_generation_history"]!!.updated)
        assertEquals(old, records<NovelAiGenerationHistoryEntry>(duplicates, "novelai_generation_history").single { it.id == "local-id" })
    }

    @Test fun v8ArchiveCopyRewritesNestedIdentitiesAndCopiesMediaBytesWithoutReencoding() {
        val local = temporary.newFolder()
        card(local, "card"); session(local, "session", "card")
        val source = temporary.newFolder()
        card(source, "card"); session(source, "session", "card")
        message(source, "message", "session", "正文")
        val slot = SaveSlot(schemaVersion = 8, id = "slot", sessionId = "session", name = "存档", createdAt = 12,
            packageRef = SaveSlotPackageRef("slot.cbsave", messageCount = 1, imageCount = 1))
        val bytes = ByteArray(65536) { (it % 251).toByte() }
        val packageFile = File(source, "save_slot_packages/slot.cbsave").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(packageFile.outputStream()).use { out ->
            fun put(name: String, data: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
            put("manifest.json", backupJson.encodeToString(slot).toByteArray())
            put("messages.jsonl", (backupJson.encodeToString(ChatMessage(id = "message", sessionId = "session", role = MessageRole.USER,
                content = "正文", images = listOf("chatbar-save-slot-image:original"), createdAt = 1, updatedAt = 2)) + "\n").toByteArray())
            put("rag.jsonl", byteArrayOf())
            put("media/images/original.png", bytes)
        }
        write(source, "entities/save_slots/slot.json", backupJson.encodeToString(slot.copy(packageRef = slot.packageRef!!.copy(byteLength = packageFile.length()))))
        val (staged, _) = merge(local, incoming(source, local), AppBackupRestoreMode.ADD_COPIES)
        val copied = records<SaveSlot>(staged, "save_slots").single()
        assertNotEquals("slot", copied.id)
        assertNotEquals("session", copied.sessionId)
        val packageCopy = File(staged, "save_slot_packages/${copied.packageRef!!.fileName}")
        assertEquals(packageCopy.length(), copied.packageRef!!.byteLength)
        ZipFile(packageCopy).use { zip ->
            assertArrayEquals(bytes, zip.getInputStream(zip.getEntry("media/images/original.png")).readBytes())
            val message = zip.getInputStream(zip.getEntry("messages.jsonl")).bufferedReader().use { backupJson.decodeFromString<ChatMessage>(it.readLine()) }
            assertEquals(copied.sessionId, message.sessionId)
            assertNotEquals("message", message.id)
            assertEquals(listOf("chatbar-save-slot-image:original"), message.images)
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { backupJson.decodeFromString<SaveSlot>(it.readText()) }
            assertEquals(copied.id, manifest.id)
            assertEquals(copied.sessionId, manifest.sessionId)
        }
    }

    @Test fun cancellationDuringStagingNeverChangesAnyCurrentFile() {
        val local = temporary.newFolder()
        card(local, "card"); session(local, "session", "card"); message(local, "message", "session", "原记录")
        val source = temporary.newFolder()
        card(source, "card"); session(source, "session", "card"); message(source, "message", "session", "新记录")
        val input = incoming(source, local)
        val before = local.walkTopDown().filter(File::isFile).associate { it.relativeTo(local).path to it.readBytes() }
        var active = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            BackupMergeEngine(local, input.first, temporary.newFolder(), input.second, AppBackupRestoreMode.ADD_COPIES,
                { if (++active > 10) throw kotlinx.coroutines.CancellationException("synthetic cancel") }).build()
        }
        before.forEach { (path, bytes) -> assertArrayEquals(bytes, File(local, path).readBytes()) }
    }
}
