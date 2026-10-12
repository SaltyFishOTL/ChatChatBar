package com.example.chatbar.data.local

import android.content.ContextWrapper
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.repository.SettingsRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileStorageSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun failedStreamingWriteKeepsPreviousSingletonAndRemovesTemporaryFile() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        storage.saveSingleton("settings", "old", String.serializer())
        val original = File(dir, "entities/settings.json").readBytes()
        expectFailure<IOException> { storage.saveSingleton("settings", "fail", failingSerializer) }
        assertArrayEquals(original, File(dir, "entities/settings.json").readBytes())
        assertEquals(listOf("settings.json"), File(dir, "entities").list()!!.toList())
        assertEquals("old", storage(dir).loadSingleton("settings", String.serializer()))
    }

    @Test
    fun corruptSettingsAreNeitherMissingNorOverwritableEvenAfterRestart() = runTest {
        for (type in listOf("app_settings", "player_setting")) {
            val dir = temp.newFolder()
            val file = File(dir, "entities/$type.json")
            file.parentFile!!.mkdirs()
            file.writeText("{\"unfinished\":")
            val before = file.readBytes()
            val storage = storage(dir)
            val repository = SettingsRepository(storage)
            val error = expectFailure<JsonFileStorage.SingletonReadException> { repository.initialize() }
            assertEquals(type, error.entityType)
            assertTrue(error.corrupt)
            assertFalse(repository.isInitialized.first())
            assertTrue(storage.singletonReadFailures.value.containsKey(type))
            expectFailure<JsonFileStorage.SingletonReadException> {
                val restarted = SettingsRepository(storage(dir))
                if (type == "app_settings") restarted.saveAppSettings(AppSettings())
                else restarted.savePlayerSetting(PlayerSetting())
            }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test
    fun unreadablePathIsAnIoFailureAndSuccessfulRetryClearsError() = runTest {
        val dir = temp.newFolder()
        val path = File(dir, "entities/settings.json")
        path.mkdirs()
        val storage = storage(dir)
        val error = expectFailure<JsonFileStorage.SingletonReadException> {
            storage.loadSingleton("settings", String.serializer())
        }
        assertFalse(error.corrupt)
        expectFailure<JsonFileStorage.SingletonReadException> {
            storage.saveSingleton("settings", "replacement", String.serializer())
        }
        assertTrue(path.isDirectory)
        assertTrue(path.delete())
        path.writeText("\"restored\"")
        storage.retryFailedSingletonReads()
        assertTrue(storage.singletonReadFailures.value.isEmpty())
        assertEquals("restored", storage.loadSingleton("settings", String.serializer()))
        assertTrue(storage.singletonReadFailures.value.isEmpty())
    }

    @Test
    fun missingSettingsInitializeNormallyAndConcurrentUpdatesRemainSerialized() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        assertNull(storage.loadSingleton("app_settings", AppSettings.serializer()))
        val repository = SettingsRepository(storage)
        (1..40).map {
            async(Dispatchers.Default) {
                repository.updateAppSettings { it.copy(lastSeenChatAt = it.lastSeenChatAt + 1) }
            }
        }.awaitAll()
        assertEquals(40L, repository.currentAppSettings.lastSeenChatAt)
        assertEquals(40L, SettingsRepository(storage(dir)).getAppSettings().lastSeenChatAt)
        assertTrue(repository.isInitialized.first())
        assertTrue(File(dir, "entities/player_setting.json").isFile)
    }

    @Test
    fun concurrentSingletonReadsOnlySeeCompleteWrites() = runTest {
        val storage = storage(temp.newFolder())
        val values = (0..30).map { "$it:" + "x".repeat(20000) }
        storage.saveSingleton("settings", values.first(), String.serializer())
        values.drop(1).map { value ->
            async(Dispatchers.Default) {
                storage.saveSingleton("settings", value, String.serializer())
                assertTrue(storage.loadSingleton("settings", String.serializer()) in values)
            }
        }.awaitAll()
        assertTrue(storage.singletonReadFailures.value.isEmpty())
    }

    @Test
    fun cancellationIsNotConvertedIntoCorruption() = runTest {
        val storage = storage(temp.newFolder())
        storage.saveSingleton("settings", "old", String.serializer())
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun deserialize(decoder: Decoder): String = throw CancellationException("cancelled")
        }
        expectFailure<CancellationException> { storage.loadSingleton("settings", serializer) }
        assertTrue(storage.singletonReadFailures.value.isEmpty())
        assertEquals("old", storage.loadSingleton("settings", String.serializer()))
    }

    @Test
    fun partialBatchFailurePublishesCompletedFilesAndPreservesFailedAndUnvisitedValues() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        storage.saveAll("items", linkedMapOf("a" to "old-a", "b" to "old-b", "c" to "old-c"), String.serializer())
        expectFailure<IOException> {
            storage.saveAll("items", linkedMapOf("a" to "new-a", "b" to "fail", "c" to "new-c"), failingSerializer)
        }
        val expected = setOf("new-a", "old-b", "old-c")
        assertEquals(expected, storage.observeAll("items", String.serializer()).first().toSet())
        assertEquals(expected, storage(dir).loadAll("items", String.serializer()).toSet())
    }

    @Test
    fun observationDoesNotReadDiskUntilExplicitLoad() = runTest {
        val dir = temp.newFolder()
        storage(dir).saveEntity("items", "a", "saved", String.serializer())
        val storage = storage(dir)
        assertTrue(storage.observeAll("items", String.serializer()).first().isEmpty())
        storage.loadAll("items", String.serializer())
        assertEquals(listOf("saved"), storage.observeAll("items", String.serializer()).first())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun mixedBatchPublishesOnlyCommittedSnapshot() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        storage.saveAll("items", linkedMapOf("a" to "old-a", "b" to "old-b", "c" to "keep"), String.serializer())
        val snapshots = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            storage.observeAll("items", String.serializer()).collect { snapshots += it.toSet() }
        }
        storage.applyEntityChanges("items", linkedMapOf("a" to "new-a", "b" to null), String.serializer())
        runCurrent()
        assertEquals(listOf(setOf("old-a", "old-b", "keep"), setOf("new-a", "keep")), snapshots)
        assertEquals(setOf("new-a", "keep"), storage(dir).loadAll("items", String.serializer()).toSet())
        assertEquals(setOf("a.json", "c.json"), File(dir, "entities/items").list()!!.toSet())
    }

    @Test
    fun failedBatchStagingKeepsAllOriginalFilesAndCache() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        storage.saveAll("items", linkedMapOf("a" to "old-a", "b" to "old-b", "c" to "keep"), String.serializer())
        expectFailure<IOException> {
            storage.applyEntityChanges("items", linkedMapOf("a" to "new-a", "b" to "fail", "c" to null), failingSerializer)
        }
        val expected = setOf("old-a", "old-b", "keep")
        assertEquals(expected, storage.observeAll("items", String.serializer()).first().toSet())
        assertEquals(expected, storage(dir).loadAll("items", String.serializer()).toSet())
        assertEquals(setOf("a.json", "b.json", "c.json"), File(dir, "entities/items").list()!!.toSet())
    }

    @Test
    fun failedBatchInstallRestoresUpdatesAndDeletedFilesWithoutPublishing() = runTest {
        val dir = temp.newFolder()
        val storage = storage(dir)
        storage.saveAll("items", linkedMapOf("a" to "old-a", "c" to "old-c"), String.serializer())
        val blocker = File(dir, "entities/items/b.json/foreign")
        blocker.parentFile!!.mkdirs()
        blocker.writeText("keep foreign file")
        val beforeA = File(dir, "entities/items/a.json").readBytes()
        val beforeC = File(dir, "entities/items/c.json").readBytes()
        expectFailure<IOException> {
            storage.applyEntityChanges("items", linkedMapOf("a" to "new-a", "c" to null, "b" to "new-b"), String.serializer())
        }
        assertArrayEquals(beforeA, File(dir, "entities/items/a.json").readBytes())
        assertArrayEquals(beforeC, File(dir, "entities/items/c.json").readBytes())
        assertEquals("keep foreign file", blocker.readText())
        assertEquals(setOf("old-a", "old-c"), storage.observeAll("items", String.serializer()).first().toSet())
        assertEquals(setOf("a.json", "b.json", "c.json"), File(dir, "entities/items").list()!!.toSet())
    }

    private val failingSerializer = object : KSerializer<String> by String.serializer() {
        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(if (value == "fail") "x".repeat(100000) else value)
            if (value == "fail") throw IOException("injected write failure")
        }
    }

    private fun storage(dir: File) = JsonFileStorage(object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    })

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
