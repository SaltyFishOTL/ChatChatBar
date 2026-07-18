package com.example.chatbar.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.rag.RagRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JsonFileStorageUncachedTest {

    private lateinit var testFilesDir: File
    private lateinit var storage: JsonFileStorage

    @Before
    fun setUp() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        testFilesDir = File(baseContext.cacheDir, "json-storage-${UUID.randomUUID()}")
        storage = JsonFileStorage(object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = testFilesDir
        })
    }

    @After
    fun tearDown() {
        testFilesDir.deleteRecursively()
    }

    @Test
    fun uncachedOperationsPreserveDataWithoutPopulatingSharedCache() = runBlocking {
        val entities = mapOf(
            "target" to LargeEntity("target", "wanted", listOf(1f, 2f)),
            "other" to LargeEntity("other", "ignored", listOf(3f, 4f))
        )

        storage.saveAllUncached(ENTITY_TYPE, entities, LargeEntity.serializer())

        assertTrue(storage.observeAll(ENTITY_TYPE, LargeEntity.serializer()).first().isEmpty())
        assertEquals(
            listOf("target"),
            storage.queryUncached(ENTITY_TYPE, LargeEntity.serializer()) { it.group == "wanted" }
                .map { it.id }
        )
        assertTrue(storage.observeAll(ENTITY_TYPE, LargeEntity.serializer()).first().isEmpty())

        assertEquals(
            1,
            storage.deleteWhereUncached(ENTITY_TYPE, LargeEntity.serializer()) { it.group == "ignored" }
        )
        assertNotNull(storage.loadEntity(ENTITY_TYPE, "target", LargeEntity.serializer()))
        assertNull(storage.loadEntity(ENTITY_TYPE, "other", LargeEntity.serializer()))
    }

    @Test
    fun ragRepositoryKeepsVectorChunksOutOfSharedCache() = runBlocking {
        val repository = RagRepository(storage)
        repository.saveChunks(
            listOf(
                vectorChunk("session-a", "chunk-a"),
                vectorChunk("session-b", "chunk-b")
            )
        )

        assertEquals(
            listOf("chunk-a"),
            repository.getAllChunksForSession("session-a").map { it.id }
        )
        assertTrue(
            storage.observeAll("vector_chunks", VectorChunk.serializer()).first().isEmpty()
        )

        repository.deleteChunkById("chunk-a")

        assertNull(repository.getChunkById("chunk-a"))
        assertNotNull(repository.getChunkById("chunk-b"))
        assertTrue(
            storage.observeAll("vector_chunks", VectorChunk.serializer()).first().isEmpty()
        )
    }

    private fun vectorChunk(sessionId: String, id: String) = VectorChunk(
        id = id,
        sourceType = ChunkSourceType.CHAT_MEMORY,
        sourceId = sessionId,
        content = "content-$id",
        embedding = listOf(0.1f, 0.2f),
        createdAt = 1L
    )

    @Serializable
    private data class LargeEntity(
        val id: String,
        val group: String,
        val values: List<Float>
    )

    private companion object {
        const val ENTITY_TYPE = "large_entities_test"
    }
}
