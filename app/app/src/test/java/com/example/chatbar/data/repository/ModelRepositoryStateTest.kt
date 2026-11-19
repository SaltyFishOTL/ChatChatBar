package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PresetChatModel
import com.example.chatbar.data.local.entity.PresetModelCatalog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryStateTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun visionPairReusesOnlyTheSameEndpointAndKeyAndPersistsTheEffectiveLink() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        })
        val repository = ModelRepository(storage)
        val text = ModelConfig.create("Pro", "https://a.test/v1", "chat-key", "pro")
        val vision = ModelConfig.create("Visual", "https://b.test/v1", "vision-key", "flash", isMultimodal = true)
        val otherKey = vision.copy(id = "other", apiKey = "a-different-key")
        val staged = vision.copy(id = "staged", baseUrl = "https://B.TEST:443/v1/")
        repository.saveModel(vision)
        repository.saveModel(otherKey)
        repository.saveModelWithVision(text.copy(visionModelId = staged.id), staged)

        val cold = ModelRepository(storage)
        assertEquals(vision.id, cold.getModel(text.id)?.visionModelId)
        assertEquals(vision, cold.getModel(vision.id))
        assertEquals(otherKey, cold.getModel(otherKey.id))
        assertNull(cold.getModel(staged.id))

        val separateAlias = staged.copy(displayName = "My visual alias", selectableForChat = false)
        repository.saveModelWithVision(text.copy(visionModelId = separateAlias.id), separateAlias)
        val afterAlias = ModelRepository(storage)
        assertEquals(separateAlias, afterAlias.getModel(separateAlias.id))
        assertEquals(separateAlias.id, afterAlias.getModel(text.id)?.visionModelId)

        val otherEndpoint = staged.copy(id = "another-endpoint", baseUrl = "https://another.test/v1")
        repository.saveModelWithVision(text.copy(visionModelId = otherEndpoint.id), otherEndpoint)
        val afterSwitch = ModelRepository(storage)
        assertEquals(otherEndpoint.id, afterSwitch.getModel(text.id)?.visionModelId)
        assertEquals(otherEndpoint, afterSwitch.getModel(otherEndpoint.id))
    }

    @Test
    fun failedPairSaveDoesNotPublishOrLeaveTheNewVisionModel() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        })
        val repository = ModelRepository(storage)
        val blocked = File(directory, "entities/model_configs/blocked.json").apply { mkdirs() }
        File(blocked, "keep").writeText("synthetic")
        val vision = ModelConfig.create("Visual", "https://b.test/v1", "vision-key", "flash", isMultimodal = true)
        val text = ModelConfig.create("Pro", "https://a.test/v1", "chat-key", "pro")
            .copy(id = "blocked", visionModelId = vision.id)
        assertTrue(runCatching { repository.saveModelWithVision(text, vision) }.isFailure)
        assertNull(repository.getModel(vision.id))
        assertNull(ModelRepository(storage).getModel(vision.id))
        assertTrue(File(blocked, "keep").isFile)
    }

    @Test
    fun coldStartFinishesOrDiscardsPendingLinksAccordingToTheDurableOwner() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        })
        val repository = ModelRepository(storage)
        val owner = ModelConfig.create("Pro", "https://a.test/v1", "pro-key", "pro")
        val interrupted = ModelConfig.create("Staged", "https://b.test/v1", "vision-key", "flash", isMultimodal = true)
        val committed = interrupted.copy(id = "already-referenced")
        val linked = owner.copy(id = "committed-owner", visionModelId = committed.id)
        listOf(owner, linked, interrupted, committed).forEach { repository.saveModel(it) }
        val pending = File(directory, "entities/model_vision_links_pending").apply { mkdirs() }
        for (vision in listOf(interrupted, committed)) {
            File(pending, "${vision.id}.json").writeText(
                """{"visionId":"${vision.id}","visionCreatedAt":${vision.createdAt}}"""
            )
        }
        val cold = ModelRepository(storage)
        assertEquals(owner, cold.getModel(owner.id))
        assertEquals(linked, cold.getModel(linked.id))
        assertNull(cold.getModel(interrupted.id))
        assertEquals(committed, cold.getModel(committed.id))
        assertTrue(pending.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun restoringAPresetDoesNotCarryACustomEndpointsKeyToThePresetEndpoint() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        })
        val repository = ModelRepository(storage)
        val preset = PresetModelCatalog(chatModels = listOf(PresetChatModel("vision", "Vision", "visual", isMultimodal = true)))
        val saved = repository.ensurePresetChatModels(preset, 1).single()
        repository.saveModel(saved.copy(baseUrl = "https://elsewhere.test/v1", apiKey = "other-key"))
        assertEquals("", repository.restorePresetChatModels(preset, 1).single().apiKey)
        repository.saveModel(saved.copy(apiKey = "official-key"))
        assertEquals("official-key", repository.restorePresetChatModels(preset, 1).single().apiKey)
    }

    @Test
    fun `concurrent writes publish durable models and concurrent copies keep unique names`() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        })
        val repository = ModelRepository(storage)
        val models = (1..8).map { index -> ModelConfig(
            id = "model-$index", displayName = "Model $index", baseUrl = "https://example.test/v1",
            apiKey = "key", modelName = "test-model", isMultimodal = index % 2 == 0, createdAt = 1L
        ) }
        models.map { model -> async(Dispatchers.Default) { repository.saveModel(model) } }.awaitAll()
        assertEquals(models.toSet(), repository.models.first().toSet())
        models.forEach { assertEquals(it, repository.getModel(it.id)) }
        val copies = (1..4).map {
            async(Dispatchers.Default) { repository.duplicateModel(models.first().id) }
        }.awaitAll()
        assertEquals(copies.size, copies.map { it.displayName }.distinct().size)
        assertTrue(repository.models.first().containsAll(copies))
        assertEquals(repository.getAllModels(), ModelRepository(storage).getAllModels())
    }
}
