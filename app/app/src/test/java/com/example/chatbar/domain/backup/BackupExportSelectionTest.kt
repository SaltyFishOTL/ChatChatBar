package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.*
import java.io.File
import java.io.StringWriter
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupExportSelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(root: File, path: String, value: String) = File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(value) }
    private fun media(root: File, path: String, bytes: Int) = File(root, path).also {
        it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(bytes) { index -> (index % 251).toByte() })
    }
    private fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
    private fun fixture(): File {
        val root = temporary.newFolder()
        for (id in listOf("a", "b")) {
            write(root, "entities/character_cards/$id.json", backupJson.encodeToString(CharacterCard(id = id, name = id, createdAt = 1, updatedAt = 2)))
            write(root, "entities/chat_sessions/$id.json", backupJson.encodeToString(ChatSession(id = id, characterCardId = id, title = id, createdAt = 3, updatedAt = 4)))
        }
        return root
    }
    private fun roundTrip(root: File, selection: AppBackupSelection, password: CharArray? = null): Pair<File, AppBackupSummary> {
        val catalog = BackupExportCatalog.read(root) {}
        val archive = temporary.newFile()
        val summary = archive.outputStream().use {
            engine().create(root, temporary.newFolder(), it, password, 10, "fixture", BackupPreferences(), catalog.plan(selection))
        }
        val work = temporary.newFolder()
        val restored = archive.inputStream().use { engine().extract(it, password, work, temporary.newFolder(), 10) }
        assertEquals(summary, restored)
        assertEquals(2, restored.formatVersion)
        restored.requireCompatibleWith(10)
        return File(work, "files") to restored
    }

    @Test fun sessionsRequireTheirCardAndCardRemovalClearsDependentChoices() {
        val catalog = BackupExportCatalog.read(fixture()) {}
        val choice = catalog.allSelection().withCharacter("a", false, catalog)
        assertFalse("a" in choice.characterIds)
        assertFalse("a" in choice.sessions)
        assertTrue("b" in choice.sessions)
        assertThrows(IllegalArgumentException::class.java) { catalog.plan(choice.copy(sessions = choice.sessions + ("a" to BackupSessionChoice()))) }
    }

    @Test fun globalImageChoiceCoversHiddenAndFutureSessionsWithoutChangingVoiceArchivesOrSelection() {
        val catalog = BackupExportCatalog.read(fixture()) {}
        val original = catalog.allSelection().copy(sessions = mapOf(
            "a" to BackupSessionChoice(images = true, voice = false, archives = true),
            "b" to BackupSessionChoice(images = false, voice = true, archives = false)
        ))
        val disabled = original.withAllSessionImages(false)
        assertEquals(original.characterIds, disabled.characterIds)
        assertEquals(original.sessions.keys, disabled.sessions.keys)
        original.sessions.forEach { (id, choice) -> assertEquals(choice.copy(images = false), disabled.sessions[id]) }
        val reselected = disabled.withCharacter("b", false, catalog).withCharacter("b", true, catalog)
            .withSessions(listOf("b"), true)
        assertFalse(reselected.sessions.getValue("b").images)
        assertFalse(reselected.withSessions(listOf("a"), false).withAllSessions(catalog).sessions.getValue("a").images)
        val allEnabled = disabled.withAllSessionImages(true)
        original.sessions.forEach { (id, choice) -> assertEquals(choice.copy(images = true), allEnabled.sessions[id]) }
        assertTrue(allEnabled.newSessionChoice().images)
        assertFalse(AppBackupSelection().withAllSessionImages(false).withAllSessions(catalog).sessions.getValue("a").images)
        assertFalse(catalog.lightSelection().newSessionChoice().images)
    }

    @Test fun voiceAndArchiveBulkChoicesCoverAllSelectedAndFutureSessionsIndependently() {
        val catalog = BackupExportCatalog.read(fixture()) {}
        var selection = catalog.allSelection()
        selection = selection.withAllSessionContent(BackupSessionContent.VOICE, false)
        assertTrue(selection.sessions.values.all { !it.voice && it.images && it.archives })
        selection = selection.withAllSessionContent(BackupSessionContent.ARCHIVES, false)
        assertTrue(selection.sessions.values.all { !it.voice && !it.archives && it.images })
        selection = selection.withSessions(listOf("b"), false).withSessions(listOf("b"), true)
        assertFalse(selection.sessions.getValue("b").voice)
        assertFalse(selection.sessions.getValue("b").archives)
        selection = selection.withAllSessionContent(BackupSessionContent.VOICE, true)
        assertTrue(selection.sessions.values.all { it.voice && !it.archives && it.images })
        selection = selection.withAllSessionContent(BackupSessionContent.ARCHIVES, true)
        assertTrue(selection.sessions.values.all { it.voice && it.archives && it.images })
        assertEquals(BackupSessionChoice(false, false, false), catalog.lightSelection().newSessionChoice())
    }

    @Test fun momentImageChoiceOmitsBodyMediaOnlyAndKeepsHistoricalIdentityAndProgress() {
        val root = fixture()
        val avatar = media(root, "images/moment-avatar.png", 4096)
        val picture = media(root, "images/moment-body.png", 128 * 1024)
        val post = MomentPost(id = "post", characterCardId = "deleted-card", sessionId = "old-session",
            senderCharacterId = "old-speaker", senderName = "历史角色", senderAvatar = avatar.path,
            text = "正文保留 ${picture.path}", imagePath = picture.path, imagePrompt = "可重新生图的提示词", imageBrief = "配图意图",
            generatedImageMetadata = GeneratedImageMetadata(picture.path, "prompt", negativePrompt = "negative", sizePreset = "fixture", width = 10, height = 20),
            userLiked = true, baseLikeCount = 23, generationCheckpoint = "{\"draft\":{\"text\":\"断点\"}}",
            scheduledAt = 10, generatedAt = 20, createdAt = 30, updatedAt = 40)
        val source = write(root, "entities/moment_posts/post.json", backupJson.encodeToString(post))
        val before = source.readText()
        val task = MomentTask(id = "task", characterCardId = "deleted-card", sessionId = "old-session", scheduledAt = 10,
            status = MomentTaskStatus.COMPLETED, postId = post.id, createdAt = 1, updatedAt = 20)
        write(root, "entities/moment_tasks/task.json", backupJson.encodeToString(task))
        val without = AppBackupSelection(moments = true, momentsImages = false)
        val (staged, summary) = roundTrip(root, without)
        val restored = backupJson.decodeFromString<MomentPost>(File(staged, "entities/moment_posts/post.json").readText())
        assertEquals(post.copy(senderAvatar = restored.senderAvatar, imagePath = null, generatedImageMetadata = null), restored)
        assertArrayEquals(avatar.readBytes(), File(staged, "images/moment-avatar.png").readBytes())
        assertFalse(File(staged, "images/moment-body.png").exists())
        assertEquals(task, backupJson.decodeFromString<MomentTask>(File(staged, "entities/moment_tasks/task.json").readText()))
        assertFalse(summary.scope!!.momentsImages)
        assertEquals(before, source.readText())
        val catalog = BackupExportCatalog.read(root) {}
        val with = without.copy(momentsImages = true)
        assertTrue(catalog.estimate(with).bytes - catalog.estimate(without).bytes >= picture.length())
        val (full, _) = roundTrip(root, with)
        assertArrayEquals(picture.readBytes(), File(full, "images/moment-body.png").readBytes())
        val restoredFull = backupJson.decodeFromString<MomentPost>(File(full, "entities/moment_posts/post.json").readText())
        assertNotNull(restoredFull.generatedImageMetadata)
        assertNotNull(restoredFull.imagePath)
    }

    @Test fun earlierScopeWithoutMomentImageFlagDefaultsToIncluded() {
        val oldScope = "{\"globalOptions\":[],\"characters\":0,\"sessions\":0,\"sessionsWithImages\":0,\"sessionsWithVoice\":0,\"sessionsWithArchives\":0,\"studioSettings\":false,\"studioHistory\":false,\"studioHistoryImages\":true,\"moments\":true}"
        assertTrue(backupJson.decodeFromString<AppBackupExportScope>(oldScope).momentsImages)
    }

    @Test fun imageChoicesRemoveOnlyProjectedAttachmentsAndRetainSharedOriginalOnce() {
        val root = fixture()
        val shared = media(root, "images/shared.png", 128 * 1024)
        val unused = media(root, "images/unreferenced.png", 512 * 1024)
        for (id in listOf("a", "b")) write(root, "entities/chat_messages/$id.json", backupJson.encodeToString(ChatMessage(
            id = id, sessionId = id, role = MessageRole.USER, content = "正文路径不替换 ${shared.path}",
            images = listOf(shared.path), alternatives = listOf("版本一", "版本二"), currentAlternativeIndex = 1,
            generatedImageMetadata = listOf(GeneratedImageMetadata(shared.path, "prompt", negativePrompt = "negative", sizePreset = "fixture", width = 10, height = 20)),
            createdAt = 5, updatedAt = 6, sourceTurnId = "turn-$id", sourceTurnOrder = 7
        )))
        val before = File(root, "entities/chat_messages/a.json").readText()
        val catalog = BackupExportCatalog.read(root) {}
        val chosen = catalog.allSelection().copy(sessions = mapOf("a" to BackupSessionChoice(images = false), "b" to BackupSessionChoice()))
        val (staged, summary) = roundTrip(root, chosen, "fixture-password".toCharArray())
        val a = backupJson.decodeFromString<ChatMessage>(File(staged, "entities/chat_messages/a.json").readText())
        val b = backupJson.decodeFromString<ChatMessage>(File(staged, "entities/chat_messages/b.json").readText())
        assertEquals(emptyList<String>(), a.images)
        assertTrue(a.generatedImageMetadata.isEmpty())
        assertEquals(listOf("版本一", "版本二"), a.alternatives)
        assertEquals(1, a.currentAlternativeIndex)
        assertEquals("turn-a", a.sourceTurnId)
        assertEquals(7L, a.sourceTurnOrder)
        assertTrue(a.content.contains(shared.path))
        assertEquals(1, b.images.size)
        assertArrayEquals(shared.readBytes(), File(staged, "images/shared.png").readBytes())
        assertFalse(File(staged, "images/unreferenced.png").exists())
        assertTrue(unused.isFile)
        assertEquals(before, File(root, "entities/chat_messages/a.json").readText())
        assertEquals(1, summary.scope!!.sessionsWithImages)
        val noImages = chosen.copy(sessions = chosen.sessions.mapValues { it.value.copy(images = false) })
        assertTrue(catalog.estimate(chosen).bytes - catalog.estimate(noImages).bytes >= shared.length())
    }

    @Test fun metadataOnlyStudioPreservesRecipesDistinctImageIdentitiesAndSeeds() {
        val root = fixture()
        val first = media(root, "images/generated/history/first.png", 4096)
        val second = media(root, "images/generated/history/second.png", 4096)
        val history = NovelAiGenerationHistoryEntry(id = "history", images = listOf(
            NovelAiGenerationHistoryImage(first.path, 42), NovelAiGenerationHistoryImage(second.path, 43)
        ), recipe = NovelAiGenerationRecipe(stylePrompt = "画风", basePrompt = "正文", negativePrompt = "negative",
            settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 42, steps = 36, count = 2)), createdAt = 100)
        write(root, "entities/novelai_generation_history/history.json", backupJson.encodeToString(history))
        val (staged, summary) = roundTrip(root, AppBackupSelection(studioHistory = true, studioHistoryImages = false))
        val restored = backupJson.decodeFromString<NovelAiGenerationHistoryEntry>(File(staged, "entities/novelai_generation_history/history.json").readText())
        assertEquals(history.recipe, restored.recipe)
        assertEquals(history.createdAt, restored.createdAt)
        assertEquals(listOf(42L, 43L), restored.images.map { it.seed })
        assertTrue(restored.images.all { it.originalOmitted })
        assertEquals(2, restored.images.map { it.path }.distinct().size)
        assertFalse(File(staged, "images").exists())
        assertFalse(summary.scope!!.studioHistoryImages)
        val remaining = NovelAiHistoryDeletionPolicy.apply(listOf(restored), listOf(NovelAiHistoryImageSelection("history", restored.images.first().path)))
        assertEquals(listOf(restored.images.last()), remaining["history"]!!.images)
    }

    @Test fun voiceAndArchivesAreIndependentAndProgressTravelsWithSession() {
        val root = fixture()
        val audio = media(root, "audio/generated/voice.mp3", 8192)
        val voice = GeneratedVoiceMessage.create(sessionId = "a", messageId = "message", anchorId = "anchor", sourceOrder = 1,
            sourceSegmentKind = "dialogue", sourceSpeakerName = "角色", sourceText = "正文", taggedText = "正文",
            characterId = "speaker", characterName = "角色", voice = FishAudioVoiceBinding("reference", "voice"),
            fishModelId = "fixture", audioPath = audio.path, durationMs = 100, byteLength = audio.length())
        write(root, "entities/generated_voice_messages/voice.json", backupJson.encodeToString(voice))
        write(root, "entities/chat_drafts/a.json", backupJson.encodeToString(ChatDraft("a", "未发送草稿", 80)))
        write(root, "entities/chat_scroll_positions/a.json", backupJson.encodeToString(ChatScrollPosition(sessionId = "a")))
        write(root, "entities/memory_states/a.json", backupJson.encodeToString(MemorySessionState("a")))
        val packageFile = media(root, "save_slot_packages/retained.cbsave", 4096)
        write(root, "entities/save_slots/slot.json", backupJson.encodeToString(SaveSlot(schemaVersion = 8, id = "slot", sessionId = "a",
            name = "snapshot", packageRef = SaveSlotPackageRef(packageFile.name, byteLength = packageFile.length()), createdAt = 90)))
        val selection = AppBackupSelection(characterIds = setOf("a"), sessions = mapOf("a" to BackupSessionChoice(voice = false, archives = true)))
        val (staged, _) = roundTrip(root, selection)
        assertFalse(File(staged, "entities/generated_voice_messages").exists())
        assertFalse(File(staged, "audio").exists())
        assertTrue(File(staged, "entities/chat_drafts/a.json").isFile)
        assertTrue(File(staged, "entities/chat_scroll_positions/a.json").isFile)
        assertTrue(File(staged, "entities/memory_states/a.json").isFile)
        assertArrayEquals(packageFile.readBytes(), File(staged, "save_slot_packages/retained.cbsave").readBytes())
        val (other, _) = roundTrip(root, selection.copy(sessions = mapOf("a" to BackupSessionChoice(voice = true, archives = false))))
        assertArrayEquals(audio.readBytes(), File(other, "audio/generated/voice.mp3").readBytes())
        assertFalse(File(other, "save_slot_packages").exists())
        assertFalse(File(other, "entities/save_slots").exists())
    }

    @Test fun individualGlobalChoicesResetOmittedFieldsWithoutEditingSource() {
        val root = fixture()
        val settings = AppSettings(themeMode = ThemeMode.DARK, chatBubbleFontScale = 1.8f, siliconFlowApiKey = "secret")
        val source = write(root, "entities/app_settings.json", backupJson.encodeToString(settings))
        val before = source.readText()
        val (staged, _) = roundTrip(root, AppBackupSelection(globalOptions = setOf("themeMode")))
        val restored = backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText())
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(AppSettings().chatBubbleFontScale, restored.chatBubbleFontScale)
        assertEquals("", restored.siliconFlowApiKey)
        assertEquals(before, source.readText())
    }

    @Test fun selectedSessionBindsOmittedDefaultsAndKeepsReferencedArchivedDependencies() {
        val root = fixture()
        write(root, "entities/app_settings.json", backupJson.encodeToString(AppSettings(defaultModelId = "default-model", defaultFormatCardId = "default-format")))
        write(root, "entities/chat_sessions/a.json", "{\"id\":\"a\",\"characterCardId\":\"a\",\"title\":\"a\",\"createdAt\":1,\"updatedAt\":2}")
        write(root, "entities/model_configs/default-model.json", "{\"id\":\"default-model\",\"displayName\":\"fixture\"}")
        write(root, "entities/model_configs/archived-model.json", "{\"id\":\"archived-model\",\"displayName\":\"fixture\"}")
        write(root, "entities/format_cards/default-format.json", "{\"id\":\"default-format\",\"name\":\"fixture\"}")
        write(root, "entities/save_slots/slot.json", backupJson.encodeToString(SaveSlot(id = "slot", sessionId = "a", name = "snapshot", modelId = "archived-model", createdAt = 1)))
        val (staged, _) = roundTrip(root, AppBackupSelection(characterIds = setOf("a"), sessions = mapOf("a" to BackupSessionChoice())))
        val restored = backupJson.decodeFromString<ChatSession>(File(staged, "entities/chat_sessions/a.json").readText())
        assertEquals("default-model", restored.modelId)
        assertEquals("default-format", restored.formatCardId)
        assertTrue(File(staged, "entities/model_configs/archived-model.json").isFile)
        assertTrue(File(staged, "entities/model_configs/default-model.json").isFile)
        assertTrue(File(staged, "entities/format_cards/default-format.json").isFile)
        assertNull(backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText()).defaultModelId)
    }

    @Test fun registeredRelativeMediaPathsAreCollectedAndMappedAcrossDirectories() {
        val root = fixture()
        val original = media(root, "images/relative.png", 1024)
        write(root, "entities/chat_messages/a.json", backupJson.encodeToString(ChatMessage(id = "a", sessionId = "a", role = MessageRole.USER,
            content = "fixture", images = listOf("images/relative.png"), createdAt = 1, updatedAt = 2)))
        val (staged, _) = roundTrip(root, AppBackupSelection(characterIds = setOf("a"), sessions = mapOf("a" to BackupSessionChoice())))
        val restored = backupJson.decodeFromString<ChatMessage>(File(staged, "entities/chat_messages/a.json").readText())
        assertTrue(File(restored.images.single()).isAbsolute)
        assertArrayEquals(original.readBytes(), File(staged, "images/relative.png").readBytes())
    }

    @Test fun streamingReplacementSkipsMediaCallbacksAndCountsUtf8AcrossSurrogates() {
        val out = StringWriter()
        val mapped = mutableListOf<String>()
        BackupJsonStream("{\"images\":[\"missing.png\"],\"content\":\"正文🙂\",\"id\":\"keep\"}".reader(), out,
            replaceValue = { fields -> "[]".takeIf { fields == listOf("images") } }, extraRootFields = mapOf("modelId" to "\"model\"")
        ) { path, _ -> mapped.add(path); path }.copy()
        assertTrue(mapped.isEmpty())
        assertTrue(out.toString().contains("正文🙂"))
        assertTrue(out.toString().contains("\"modelId\":\"model\""))
        val counter = ByteCountingWriter()
        out.toString().forEach { counter.write(it.code) }
        counter.flush()
        assertEquals(out.toString().toByteArray(Charsets.UTF_8).size.toLong(), counter.bytes)
    }
}
