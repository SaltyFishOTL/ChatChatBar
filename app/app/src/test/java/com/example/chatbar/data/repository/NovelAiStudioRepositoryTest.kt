package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationRecipe
import com.example.chatbar.domain.image.NovelAiHistoryImageSelection
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelAiStudioRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `delayed flush persists latest staged revision instead of captured older draft`() = runTest {
        val files = temp.newFolder("latest-${System.nanoTime()}")
        val repository = repository(files)
        repository.loadDraft()

        val first = repository.stageDraft { it.copy(basePrompt = "first") }
        val second = repository.stageDraft { it.copy(basePrompt = "second") }
        val flushed = repository.flushLatestDraft()
        val reloaded = repository(files).loadDraft()

        assertTrue(second.contentRevision > first.contentRevision)
        assertEquals("second", flushed.basePrompt)
        assertEquals(second.contentRevision, flushed.contentRevision)
        assertEquals("second", reloaded.basePrompt)
        assertEquals(second.contentRevision, reloaded.contentRevision)
    }

    @Test
    fun `reverse replacement advances prompt revision and invalidates older editor generation`() = runTest {
        val repository = repository(temp.newFolder("reverse-${System.nanoTime()}"))
        repository.loadDraft()
        val edited = repository.stageDraft {
            it.copy(
                stylePrompt = "style",
                basePrompt = "old base",
                characters = listOf(
                    NovelAiCharacterPromptDraft(
                        id = "role",
                        prompt = "old role",
                        negativePrompt = "negative"
                    )
                )
            )
        }

        val applied = repository.applyReversePrompt(
            NovelAiPromptPlan(
                baseCaption = "new base",
                characterCaptions = listOf(
                    NovelAiCharacterCaption(
                        prompt = "new role",
                        center = DesignedCharacterCenter(0.5f, 0.5f)
                    )
                )
            )
        )

        assertTrue(applied.contentRevision > edited.contentRevision)
        assertEquals(applied.contentRevision, applied.promptContentRevision)
        assertEquals("style", applied.stylePrompt)
        assertEquals("new base", applied.basePrompt)
        assertEquals("role", applied.characters.single().id)
        assertEquals("new role", applied.characters.single().prompt)
        assertEquals("negative", applied.characters.single().negativePrompt)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun batchDeletePublishesOnceAndPreservesUnselectedMediaAndRecipes() = runTest {
        val files = temp.newFolder()
        val context = TestContext(files)
        val images = NovelAiImageStorage(context)
        val repository = repository(files)
        val a = NovelAiGenerationHistoryEntry(
            id = "a", createdAt = 3L,
            images = List(3) { index -> NovelAiGenerationHistoryImage(images.save("a", byteArrayOf(index.toByte())), index.toLong()) },
            recipe = NovelAiGenerationRecipe(basePrompt = "keep recipe")
        )
        val b = NovelAiGenerationHistoryEntry(id = "b", createdAt = 2L,
            images = listOf(NovelAiGenerationHistoryImage(images.save("b", byteArrayOf(4)), 4L)))
        val c = NovelAiGenerationHistoryEntry(id = "c", createdAt = 1L,
            images = listOf(NovelAiGenerationHistoryImage(images.save("c", byteArrayOf(5)), 5L)))
        listOf(a, b, c).forEach { repository.saveHistory(it) }
        val survivorBytes = File(a.images[1].path).readBytes()
        val snapshots = mutableListOf<List<NovelAiGenerationHistoryEntry>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.history.collect { snapshots += it }
        }

        val result = repository.deleteHistoryImages(listOf(
            NovelAiHistoryImageSelection("a", a.images[0].path),
            NovelAiHistoryImageSelection("b", b.images[0].path),
            NovelAiHistoryImageSelection("a", a.images[2].path),
            NovelAiHistoryImageSelection("a", a.images[0].path)
        ))
        runCurrent()
        val retainedA = a.copy(images = listOf(a.images[1]))
        assertEquals(3, result.deletedCount)
        assertEquals(0, result.cleanupFailureCount)
        assertEquals(listOf(listOf(a, b, c), listOf(retainedA, c)), snapshots)
        assertArrayEquals(survivorBytes, File(a.images[1].path).readBytes())
        assertTrue(File(c.images.single().path).isFile)
        assertFalse(File(a.images[0].path).exists())
        assertFalse(File(files, "images/generated/b").exists())
        val reloaded = repository(files)
        reloaded.initialize()
        assertEquals(listOf(retainedA, c), reloaded.history.first())
    }

    @Test
    fun stagingFailureRestoresEarlierFilesAndLeavesHistoryUntouched() = runTest {
        val files = temp.newFolder()
        val owned = NovelAiImageStorage(TestContext(files)).save("batch", byteArrayOf(1, 2, 3))
        val external = temp.newFile().apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val repository = repository(files)
        val entry = NovelAiGenerationHistoryEntry(id = "batch", images = listOf(
            NovelAiGenerationHistoryImage(owned, 1L), NovelAiGenerationHistoryImage(external.absolutePath, 2L)
        ))
        repository.saveHistory(entry)
        val error = runCatching { repository.deleteHistoryImages(entry.images.map {
            NovelAiHistoryImageSelection(entry.id, it.path)
        }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(owned).readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), external.readBytes())
        assertEquals(listOf(entry), repository.history.first())
        assertEquals(1, File(owned).parentFile!!.list()!!.size)
    }

    private fun repository(files: File): NovelAiStudioRepository {
        val context = TestContext(files)
        return NovelAiStudioRepository(
            storage = JsonFileStorage(context),
            imageStorage = NovelAiImageStorage(context)
        )
    }

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
