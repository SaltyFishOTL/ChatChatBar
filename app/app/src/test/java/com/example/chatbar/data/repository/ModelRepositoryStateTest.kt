package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryStateTest {
    @get:Rule val temp = TemporaryFolder()

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
