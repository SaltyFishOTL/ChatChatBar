package com.example.chatbar.domain.model

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EffectiveModelConfigurationFlowTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `open selectors track new models capability edits credentials and deletion without settings changes`() = runTest {
        val (repository, settings, resolver) = fixture()
        val studio = Channel<EffectiveModelConfiguration>(Channel.UNLIMITED)
        val editor = Channel<EffectiveModelConfiguration>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            resolver.observeConfiguration().collect { studio.send(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            resolver.observeConfiguration().collect { editor.send(it) }
        }
        assertEquals(studio.receive(), editor.receive())
        val originalSettings = settings.getAppSettings()
        val added = model("vision", multimodal = true)
        repository.saveModel(added)
        val afterAdd = studio.receive()
        assertEquals(afterAdd, editor.receive())
        assertTrue(afterAdd.chatModels.contains(added))
        assertTrue(afterAdd.multimodalModels.contains(added))
        assertEquals(added, resolver.configuration().imageDesignModel(added.id))

        val changed = added.copy(displayName = "Updated", baseUrl = "https://new.example.test/v1", apiKey = "new-key")
        repository.saveModel(changed)
        val afterEdit = studio.receive()
        assertEquals(afterEdit, editor.receive())
        assertEquals(changed, afterEdit.imageDesignModel(added.id))
        assertEquals(changed, resolver.configuration().imageDesignModel(added.id))

        repository.saveModel(changed.copy(isMultimodal = false, visionModelId = "linked"))
        val afterCapabilityChange = studio.receive()
        assertEquals(afterCapabilityChange, editor.receive())
        assertTrue(afterCapabilityChange.multimodalModels.isEmpty())
        assertFalse(afterCapabilityChange.imageDesignModel(added.id)!!.isMultimodal)
        assertEquals("linked", afterCapabilityChange.imageDesignModel(added.id)!!.visionModelId)

        repository.saveModel(changed)
        assertEquals(studio.receive(), editor.receive())
        assertTrue(resolver.configuration().imageDesignModel(added.id)!!.isMultimodal)
        repository.deleteModel(added.id)
        val afterDelete = studio.receive()
        assertEquals(afterDelete, editor.receive())
        assertNull(afterDelete.imageDesignModel(added.id))
        assertTrue(afterDelete.multimodalModels.isEmpty())
        assertEquals(originalSettings, settings.getAppSettings())
    }

    @Test
    fun `default selection and inherited key changes reach existing collectors and fresh requests together`() = runTest {
        val (repository, settings, resolver) = fixture()
        val vision = model("vision", multimodal = true).copy(apiKey = "")
        repository.saveModel(vision)
        val snapshots = Channel<EffectiveModelConfiguration>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            resolver.observeConfiguration().collect { snapshots.send(it) }
        }
        snapshots.receive()
        settings.updateAppSettings { it.copy(defaultImageModelId = vision.id, siliconFlowApiKey = "global-key") }
        val updated = snapshots.receive()
        assertEquals(vision.id, updated.imageDesignModel(null)?.id)
        assertEquals("global-key", updated.imageDesignModel(null)?.apiKey)
        assertEquals(updated, resolver.configuration())
        assertEquals("anchor", updated.defaultChatModel?.id)
        assertNull(updated.imageDesignModel("removed-explicit-selection"))
    }

    private suspend fun fixture(): Triple<ModelRepository, SettingsRepository, EffectiveModelResolver> {
        val directory = temp.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        }
        val storage = JsonFileStorage(context)
        val repository = ModelRepository(storage)
        // Inline fixtures keep all resolution on repository data; no bundled assets are read.
        repository.saveModel(model("anchor"))
        repository.saveEmbeddingModel(EmbeddingConfig(
            id = "embedding", displayName = "Embedding", baseUrl = "https://example.test/v1",
            apiKey = "key", modelName = "embedding-model", dimensions = 8
        ))
        val settings = SettingsRepository(storage)
        settings.saveAppSettings(AppSettings(defaultModelId = "anchor"))
        settings.initialize()
        return Triple(repository, settings, EffectiveModelResolver(
            repository, settings, PresetModelCatalogService(context, Json { ignoreUnknownKeys = true })
        ))
    }

    private fun model(id: String, multimodal: Boolean = false) = ModelConfig(
        id = id, displayName = id, baseUrl = "https://example.test/v1", apiKey = "key",
        modelName = "test-model", isMultimodal = multimodal, createdAt = 1L
    )
}
