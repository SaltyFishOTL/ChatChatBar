package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorDraftRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun roundTripUsesSingleNewDraftKey() = runTest {
        val repo = newRepository()

        val first = repo.save(
            repo.formatDraft(
                targetId = null,
                draftSessionId = "session-1",
                payload = formatCard("草稿一", "内容一"),
                base = null
            )
        )
        val second = repo.save(
            repo.formatDraft(
                targetId = null,
                draftSessionId = "session-2",
                payload = formatCard("草稿二", "内容二"),
                base = null
            )
        )

        assertEquals("format_card_new", first.id)
        assertEquals("format_card_new", second.id)
        assertEquals(1, repo.getAll().filter { it.entityType == EditorDraftType.FORMAT_CARD && it.isNew }.size)
        assertEquals("session-2", repo.getLatestNew(EditorDraftType.FORMAT_CARD)?.draftSessionId)
        assertEquals("草稿二", repo.getById("format_card_new")?.formatPayload?.name)
    }

    @Test
    fun deletingDraftDoesNotDeleteFormalFormatCard() = runTest {
        val storage = newStorage()
        val drafts = EditorDraftRepository(storage)
        val formats = FormatCardRepository(storage)
        val formal = formatCard("正式格式", "正式内容")
        formats.save(formal)

        val draft = drafts.save(
            drafts.formatDraft(
                targetId = formal.id,
                draftSessionId = "edit-session",
                payload = formal.copy(content = "草稿内容"),
                base = formal
            )
        )
        drafts.delete(draft.id)

        assertNull(drafts.getForTarget(EditorDraftType.FORMAT_CARD, formal.id))
        assertEquals("正式内容", formats.getById(formal.id)?.content)
    }

    @Test
    fun baseHashDetectsConflictButDeletedBaseBecomesNew() = runTest {
        val repo = newRepository()
        val base = WorldBook.create("原世界书", "旧描述")
        val draft = repo.save(
            repo.worldBookDraft(
                targetId = base.id,
                draftSessionId = "world-session",
                payload = base.copy(description = "草稿描述"),
                base = base,
                openModalState = """{"index":0}"""
            )
        )

        assertFalse(repo.isChanged(base, draft))
        assertTrue(repo.isChanged(base.copy(description = "外部修改"), draft))
        assertFalse(repo.isChanged(null as WorldBook?, draft))
        assertEquals("""{"index":0}""", repo.getForTarget(EditorDraftType.WORLD_BOOK, base.id)?.openModalState)
    }

    @Test
    fun deleteForTargetRemovesEditDraft() = runTest {
        val repo = newRepository()
        val base = formatCard("编辑目标", "旧内容")
        repo.save(
            repo.formatDraft(
                targetId = base.id,
                draftSessionId = "edit-session",
                payload = base.copy(content = "草稿内容"),
                base = base
            )
        )

        assertNotNull(repo.getForTarget(EditorDraftType.FORMAT_CARD, base.id))
        repo.deleteForTarget(EditorDraftType.FORMAT_CARD, base.id)
        assertNull(repo.getForTarget(EditorDraftType.FORMAT_CARD, base.id))
    }

    private fun newRepository(): EditorDraftRepository = EditorDraftRepository(newStorage())

    private fun newStorage(): JsonFileStorage =
        JsonFileStorage(TestContext(temp.newFolder("files-${System.nanoTime()}")))

    private fun formatCard(name: String, content: String): FormatCard =
        FormatCard(
            id = "format-${name.hashCode()}-${content.hashCode()}",
            name = name,
            content = content,
            createdAt = 1L
        )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
