package com.example.chatbar.domain.card

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.repository.ModelRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelTemplateTransferServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun importedTemplateAlwaysCreatesNewModelWithoutApiKey() = runTest {
        val repository = ModelRepository(JsonFileStorage(TestContext(temp.newFolder("files"))))
        val service = ModelTemplateTransferService(repository, json)
        val packageData = ModelTemplatePackage(
            displayName = "共享模型",
            baseUrl = "https://example.com/v1",
            modelName = "shared-model",
            isMultimodal = true,
            templateType = ModelTemplate.OPENAI,
            customParams = emptyMap()
        )

        val first = service.importNew(packageData)
        val second = service.importNew(packageData)

        assertNotEquals(first.id, second.id)
        assertEquals("", first.apiKey)
        assertEquals("共享模型 (Imported Template)", first.displayName)
        assertEquals(packageData, service.decode(json.encodeToString(ModelTemplatePackage.serializer(), packageData)))
    }

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
