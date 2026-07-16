package com.example.chatbar.domain.memory

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.data.repository.MemoryRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryRevisionServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `middle-node replacement materializes at original position`() = runTest {
        val repository = MemoryRepository(
            JsonFileStorage(TestContext(temp.newFolder("files")))
        )
        val service = MemoryRevisionService(repository)
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(
                tier = MemoryTier.EPISODE,
                activeNodeIds = listOf("early", "wrong-middle", "late")
            )
        )

        val checkpoint = service.checkpoint(
            initialState = state,
            tier = MemoryTier.EPISODE,
            afterNodeIds = listOf("early", "fixed-middle", "late"),
            operation = MemoryRevisionOperation.USER_EDIT,
            author = MemoryAuthor.USER
        )

        assertNotNull(checkpoint.revision.snapshotNodeIds)
        assertEquals(
            listOf("early", "fixed-middle", "late"),
            repository.materializeRevision(checkpoint.revision.id)
        )
    }

    @Test
    fun `checkpoint snapshots when historical parent already materializes in wrong order`() = runTest {
        val repository = MemoryRepository(
            JsonFileStorage(TestContext(temp.newFolder("broken-parent")))
        )
        val baseline = revision(
            id = "baseline",
            snapshotNodeIds = listOf("early", "wrong-middle", "late")
        )
        val brokenEdit = revision(
            id = "broken-edit",
            parentRevisionId = baseline.id,
            addedNodeIds = listOf("fixed-middle"),
            removedNodeIds = listOf("wrong-middle")
        )
        repository.saveRevisions(listOf(baseline, brokenEdit))
        val service = MemoryRevisionService(repository)
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(
                tier = MemoryTier.EPISODE,
                activeNodeIds = listOf("early", "fixed-middle", "late"),
                currentRevisionId = brokenEdit.id,
                revisionSequence = 2
            )
        )

        val checkpoint = service.checkpoint(
            initialState = state,
            tier = MemoryTier.EPISODE,
            afterNodeIds = listOf("fixed-middle", "late"),
            operation = MemoryRevisionOperation.COMPRESSION_SOURCE,
            author = MemoryAuthor.AI
        )

        assertEquals(listOf("fixed-middle", "late"), checkpoint.revision.snapshotNodeIds)
        assertEquals(
            listOf("fixed-middle", "late"),
            repository.materializeRevision(checkpoint.revision.id)
        )
    }

    @Test
    fun `pure-addition sync snapshots when historical parent order is already wrong`() = runTest {
        val repository = MemoryRepository(
            JsonFileStorage(TestContext(temp.newFolder("broken-pure-parent")))
        )
        val baseline = revision(
            id = "baseline",
            snapshotNodeIds = listOf("early", "wrong-middle", "late")
        )
        val brokenEdit = revision(
            id = "broken-edit",
            parentRevisionId = baseline.id,
            addedNodeIds = listOf("fixed-middle"),
            removedNodeIds = listOf("wrong-middle")
        )
        repository.saveRevisions(listOf(baseline, brokenEdit))
        val service = MemoryRevisionService(repository)
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(
                tier = MemoryTier.EPISODE,
                activeNodeIds = listOf("early", "fixed-middle", "late", "new"),
                currentRevisionId = brokenEdit.id,
                uncheckpointedAddedNodeIds = listOf("new"),
                revisionSequence = 2
            )
        )

        val synced = service.syncPureAdditions(state, MemoryTier.EPISODE)

        assertEquals(
            listOf("early", "fixed-middle", "late", "new"),
            repository.materializeRevision(requireNotNull(synced.episodePage.currentRevisionId))
        )
    }

    @Test
    fun `restore normalizes an old broken revision by current derived T`() = runTest {
        val repository = MemoryRepository(
            JsonFileStorage(TestContext(temp.newFolder("broken-restore")))
        )
        val baseline = revision(
            id = "baseline",
            snapshotNodeIds = listOf("early", "wrong-middle", "late")
        )
        val brokenEdit = revision(
            id = "broken-edit",
            parentRevisionId = baseline.id,
            addedNodeIds = listOf("fixed-middle"),
            removedNodeIds = listOf("wrong-middle")
        )
        val current = revision(id = "current", snapshotNodeIds = listOf("late"))
        repository.saveRevisions(listOf(baseline, brokenEdit, current))
        repository.saveNodes(
            listOf(
                node("early", 0),
                node("fixed-middle", 1),
                node("late", 2)
            )
        )
        val service = MemoryRevisionService(repository)
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(
                tier = MemoryTier.EPISODE,
                activeNodeIds = listOf("late"),
                currentRevisionId = current.id,
                revisionSequence = 1
            ),
            timeline = (0..2).map { t ->
                MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
            }
        )

        val restored = service.restore(state, brokenEdit.id)

        assertEquals(
            listOf("early", "fixed-middle", "late"),
            restored.state.episodePage.activeNodeIds
        )
        assertEquals(
            restored.state.episodePage.activeNodeIds,
            repository.materializeRevision(restored.revision.id)
        )
    }

    private fun revision(
        id: String,
        parentRevisionId: String? = null,
        addedNodeIds: List<String> = emptyList(),
        removedNodeIds: List<String> = emptyList(),
        snapshotNodeIds: List<String>? = null
    ) = MemoryTierRevision(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        parentRevisionId = parentRevisionId,
        operation = MemoryRevisionOperation.USER_EDIT,
        author = MemoryAuthor.USER,
        addedNodeIds = addedNodeIds,
        removedNodeIds = removedNodeIds,
        snapshotNodeIds = snapshotNodeIds
    )

    private fun node(id: String, t: Int) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = listOf("s$t")
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
