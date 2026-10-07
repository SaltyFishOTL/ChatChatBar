package com.example.chatbar.domain.image

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelAiStudioLargeLoadTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun draftAndHistoryLoadWithoutKeepingInlinePayloadsInHeap() = runTest {
        val root = temp.newFolder()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = root }
        val storage = JsonFileStorage(context)
        val megabytes = if (System.getProperty("chatbar.largeMemoryRegression") == "true") 160 else 8
        fun writePayload(file: File, prefix: String, suffix: String) {
            requireNotNull(file.parentFile).mkdirs()
            file.bufferedWriter().use { out ->
                out.write(prefix)
                val block = "A".repeat(8192)
                repeat(megabytes * 128) { out.write(block) }
                out.write(suffix)
            }
        }
        val draftFile = File(root, "entities/novelai_studio_draft.json")
        writePayload(draftFile,
            """{"stylePrompt":"same group","styleExpanded":true,"characters":[{"id":"role","prompt":"person","enabled":false}],"imageGuidance":{"vibes":[{"encodedVibe":"""",
            """"}]}}""")
        val draft = requireNotNull(storage.loadSingleton("novelai_studio_draft", NovelAiStudioDraft.serializer()))
        assertTrue(draft.styleExpanded)
        assertFalse(draft.characters.single().enabled)
        assertEquals("person", draft.characters.single().prompt)
        val reference = draft.imageGuidance.vibes.single().encodedVibe!!
        assertTrue(reference.startsWith(NovelAiInlinePayloadStore.PREFIX))
        assertTrue(reference.length < 100)
        val historyFile = File(root, "entities/novelai_generation_history/legacy.json")
        writePayload(historyFile,
            """{"id":"legacy","recipe":{"stylePrompt":"same group","imageGuidance":{"vibes":[{"encodedVibe":"""",
            """"}]}}}""")
        val entries = storage.loadAll("novelai_generation_history", NovelAiGenerationHistoryEntry.serializer())
        assertEquals(1, entries.size)
        assertEquals("same group", entries.single().recipe.stylePrompt)
        assertEquals(reference, entries.single().recipe.imageGuidance.vibes.single().encodedVibe)
        assertEquals(1, File(root, "novelai-vibe-payloads").listFiles()!!.size)
        assertTrue(draftFile.length() > megabytes * 1024L * 1024L)
        assertTrue(historyFile.length() > megabytes * 1024L * 1024L)
    }
}
