package com.example.chatbar.domain.model

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.LlmApiConnection
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.LlmApiAccountRepository
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.data.security.LlmApiAccountVault
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LlmApiAccountsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `old inline keys and the SiliconFlow default migrate without changing model identity`() = runTest {
        val data = Fixture(temp.newFolder())
        val pro = model("pro", "https://llm.example/v1", "synthetic-pro").copy(visionModelId = "flash")
        val flash = model("flash", "https://llm.example/v1", "synthetic-flash").copy(isMultimodal = true)
        val anonymous = model("anonymous", "http://localhost:33710/v1", "")
        val foreign = model("foreign", "https://different.example/v1", "")
        val bundled = model("bundled", SILICON_FLOW_LLM_ENDPOINT, "")
        listOf(pro, flash, anonymous, foreign, bundled).forEach { data.writeModel(it) }
        data.writeSettings(AppSettings(defaultModelId = "pro", siliconFlowApiKey = "synthetic-silicon",
            allowCleartextModelApi = true))
        val first = data.open()
        val configuration = first.resolver.configuration()
        val storedPro = first.models.getModel("pro")!!
        val storedFlash = first.models.getModel("flash")!!
        assertEquals("pro", configuration.defaultChatModel?.id)
        assertEquals("flash", storedPro.visionModelId)
        assertTrue(storedPro.llmAccountId != null)
        assertNotEquals(storedPro.llmAccountId, storedFlash.llmAccountId)
        assertEquals("", data.readModel("pro")!!.apiKey)
        assertEquals("", data.readSettings()!!.siliconFlowApiKey)
        assertEquals("synthetic-pro", configuration.defaultChatModel?.apiKey)
        assertEquals("synthetic-flash", first.resolver.auxiliaryChatModel("flash")?.apiKey)
        assertEquals("synthetic-silicon", first.resolver.auxiliaryChatModel("bundled")?.apiKey)
        assertEquals("synthetic-silicon", configuration.embeddingModel?.apiKey)
        for (id in listOf("anonymous", "foreign")) {
            assertEquals("", first.resolver.auxiliaryChatModel(id)?.apiKey)
            assertNull(first.models.getModel(id)?.llmAccountId)
        }
        val reopened = data.open()
        assertEquals(first.accounts.accounts.value, reopened.accounts.accounts.value)
        assertEquals(storedPro, reopened.models.getModel("pro"))
    }

    @Test fun `readback failure leaves old plaintext source untouched and retry adopts the same account`() = runTest {
        val data = Fixture(temp.newFolder())
        val old = model("legacy", "https://llm.example/v1", "synthetic-old")
        data.writeModel(old)
        data.vault.failNextReadAfterWrite = true
        val interrupted = data.open()
        assertEquals(old, interrupted.models.getModel("legacy"))
        assertTrue(interrupted.models.llmMigrationPending.value)
        val writtenAccount = data.vault.saved.single().account
        assertEquals(old, data.readModel("legacy"))

        val restarted = data.open()
        val migrated = restarted.models.getModel("legacy")!!
        assertEquals(writtenAccount.id, migrated.llmAccountId)
        assertEquals("", migrated.apiKey)
        assertEquals(listOf(writtenAccount), restarted.accounts.accounts.value)
        assertEquals("synthetic-old", restarted.resolver.auxiliaryChatModel("legacy")?.apiKey)
    }

    @Test fun `failed key vault leaves old defaults usable and refuses a new plaintext write`() = runTest {
        val data = Fixture(temp.newFolder())
        data.writeSettings(AppSettings(siliconFlowApiKey = "synthetic-legacy-default"))
        data.vault.readUnavailable = true
        val instance = data.open()
        assertTrue(instance.settings.siliconFlowMigrationPending.value)
        instance.settings.updateAppSettings { it.copy(defaultContextWindowSize = 45) }
        assertEquals(45, data.readSettings()!!.defaultContextWindowSize)
        assertEquals("synthetic-legacy-default", data.readSettings()!!.siliconFlowApiKey)
        assertTrue(runCatching { instance.settings.setSiliconFlowApiKey("synthetic-new-default") }.isFailure)
        assertEquals("synthetic-legacy-default", data.readSettings()!!.siliconFlowApiKey)
        data.vault.readUnavailable = false
        instance.settings.retrySiliconFlowKeyMigration()
        assertEquals("", data.readSettings()!!.siliconFlowApiKey)
        assertEquals("synthetic-legacy-default", instance.resolver.effectivePresetApiKey(instance.settings.getAppSettings()))
        assertFalse(instance.settings.siliconFlowMigrationPending.value)
    }

    @Test fun `two named keys can share an endpoint while models link by stable ID`() = runTest {
        val data = Fixture(temp.newFolder())
        val instance = data.open()
        val first = instance.accounts.create("Shared", "https://llm.example/v1/", "synthetic-shared")
        val second = instance.accounts.create("Spare", "https://llm.example/v1", "synthetic-spare")
        val pro = model("pro", "https://llm.example/v1", "").copy(visionModelId = "flash", llmAccountId = first.id)
        val flash = model("flash", "https://llm.example/v1", "").copy(
            isMultimodal = true, llmAccountId = first.id, selectableForChat = false
        )
        instance.models.saveModel(pro)
        instance.models.saveModel(flash)
        instance.settings.updateAppSettings { it.copy(defaultModelId = pro.id) }
        instance.accounts.update(first.id, "Renamed", "synthetic-rotated")
        assertEquals(first.id, instance.models.getModel(pro.id)?.llmAccountId)
        assertEquals(first.id, instance.models.getModel(flash.id)?.llmAccountId)
        val configuration = instance.resolver.configuration()
        assertEquals(pro.id, configuration.defaultChatModel?.id)
        assertEquals("synthetic-rotated", configuration.defaultChatModel?.apiKey)
        assertEquals("synthetic-rotated", instance.resolver.auxiliaryChatModel(flash.id)?.apiKey)
        assertEquals(first.baseUrl, configuration.defaultChatModel?.baseUrl)
        assertEquals("synthetic-spare", instance.accounts.connection(second.id)?.apiKey)
        assertTrue(runCatching { instance.settings.removeLlmApiAccount(first.id, instance.models) }.isFailure)
        assertEquals("", data.readModel(pro.id)?.apiKey)
        assertEquals("", data.readModel(flash.id)?.apiKey)
        instance.settings.removeLlmApiAccount(second.id, instance.models)
        assertNull(instance.accounts.connection(second.id))
    }

    @Test fun `a missing or mismatched restored binding never returns the default key or another model`() = runTest {
        val data = Fixture(temp.newFolder())
        val original = data.open()
        val account = original.accounts.create("Local", "https://llm.example/v1", "synthetic-local")
        val default = model("wrong", "https://different.example/v1", "").copy(llmAccountId = account.id)
        data.writeModel(default)
        data.writeSettings(AppSettings(defaultModelId = "wrong", siliconFlowApiKey = "synthetic-default"))
        val restored = data.open()
        val resolved = restored.resolver.configuration().defaultChatModel
        assertEquals("wrong", resolved?.id)
        assertEquals("", resolved?.apiKey)
        assertEquals("", resolved?.baseUrl)
        assertFalse(restored.resolver.status("wrong", restored.settings.getAppSettings()).isUsable)
        assertTrue(restored.resolver.effectivePresetApiKey(restored.settings.getAppSettings()).isNotBlank())
    }

    private class Fixture(directory: File) {
        private val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        }
        private val storage = JsonFileStorage(context)
        val vault = FakeVault()

        suspend fun writeModel(model: ModelConfig) =
            storage.saveEntity("model_configs", model.id, model, ModelConfig.serializer())
        suspend fun readModel(id: String) = storage.loadEntity("model_configs", id, ModelConfig.serializer())
        suspend fun writeSettings(settings: AppSettings) =
            storage.saveSingleton("app_settings", settings, AppSettings.serializer())
        suspend fun readSettings() = storage.loadSingleton("app_settings", AppSettings.serializer())

        suspend fun open(): Instance {
            val accounts = LlmApiAccountRepository(vault)
            val models = ModelRepository(storage, accounts)
            val settings = SettingsRepository(storage, accounts)
            models.initialize()
            models.saveEmbeddingModel(EmbeddingConfig(
                id = "embedding", displayName = "Embedding", baseUrl = "https://embeddings.example/v1",
                apiKey = "", modelName = "embedding-model", dimensions = 8
            ))
            settings.initialize()
            val resolver = EffectiveModelResolver(models, settings, PresetModelCatalogService(
                context, Json { ignoreUnknownKeys = true }
            ), accounts)
            return Instance(accounts, models, settings, resolver)
        }
    }

    private data class Instance(
        val accounts: LlmApiAccountRepository,
        val models: ModelRepository,
        val settings: SettingsRepository,
        val resolver: EffectiveModelResolver
    )

    private class FakeVault : LlmApiAccountVault {
        var saved = emptyList<LlmApiConnection>()
        var readUnavailable = false
        var failNextReadAfterWrite = false
        private var hasWritten = false

        override fun read(): List<LlmApiConnection> {
            if (readUnavailable) throw IOException("Synthetic read failure")
            if (hasWritten && failNextReadAfterWrite) {
                failNextReadAfterWrite = false
                throw IOException("Synthetic failure after durable storage")
            }
            return saved
        }

        override fun write(connections: List<LlmApiConnection>) {
            saved = connections.toList()
            hasWritten = true
        }
    }

    private fun model(id: String, baseUrl: String, apiKey: String) = ModelConfig(
        id = id, displayName = id, baseUrl = baseUrl, apiKey = apiKey, modelName = id, createdAt = 1L
    )
}
