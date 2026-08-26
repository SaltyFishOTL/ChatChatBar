package com.example.chatbar.domain.memory

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryCommitJournal
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.repository.MemoryRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRepositoryDeletionJournalTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun journalRecoveryAppliesProjectionWhenCrashHappenedBeforeStatePointer() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("before-state")))
        val repository = MemoryRepository(storage)
        val oldState = MemorySessionState(sessionId = "session", revision = 1)
        val nextState = oldState.copy(
            projectedDeletedSourceTurnIds = setOf("s1"),
            revision = 2
        )
        val oldNode = node("old")
        val newNode = node("new")
        val oldRevision = revision("old-revision")
        repository.saveState(oldState)
        repository.saveNodes(listOf(oldNode))
        repository.saveRevision(oldRevision)
        val journal = MemoryCommitJournal(
            id = "journal",
            sessionId = "session",
            expectedStateRevision = 1,
            nodes = listOf(newNode),
            revisions = listOf(revision("new-revision")),
            deleteNodeIds = listOf(oldNode.id),
            deleteRevisionIds = listOf(oldRevision.id),
            nextState = nextState
        )
        storage.saveEntity(
            "memory_commit_journals",
            journal.id,
            journal,
            MemoryCommitJournal.serializer()
        )

        assertEquals(nextState, repository.getState("session"))
        assertNull(repository.getNode(oldNode.id))
        assertEquals(newNode, repository.getNode(newNode.id))
        assertNull(repository.getRevision(oldRevision.id))
        assertEquals("new-revision", repository.allRevisions("session").single().id)
    }

    @Test
    fun journalRecoveryFinishesCleanupWhenStatePointerWasAlreadyWritten() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("after-state")))
        val repository = MemoryRepository(storage)
        val nextState = MemorySessionState(
            sessionId = "session",
            projectedDeletedSourceTurnIds = setOf("s1"),
            revision = 2
        )
        val oldNode = node("old")
        repository.saveState(nextState)
        repository.saveNodes(listOf(oldNode))
        val journal = MemoryCommitJournal(
            id = "journal",
            sessionId = "session",
            expectedStateRevision = 1,
            deleteNodeIds = listOf(oldNode.id),
            nextState = nextState
        )
        storage.saveEntity(
            "memory_commit_journals",
            journal.id,
            journal,
            MemoryCommitJournal.serializer()
        )

        assertEquals(nextState, repository.getState("session"))
        assertNull(repository.getNode(oldNode.id))
    }

    private fun node(id: String) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = listOf("s1")
    )

    private fun revision(id: String) = MemoryTierRevision(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        operation = MemoryRevisionOperation.SOURCE_TURN_DELETE,
        author = MemoryAuthor.MIGRATION,
        snapshotNodeIds = emptyList(),
        visible = false
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
