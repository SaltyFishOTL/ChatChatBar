package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.image.NovelAiPromptPlan
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
