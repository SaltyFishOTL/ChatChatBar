package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTaskCommitPolicyTest {
    @Test
    fun unchangedHeadEvidenceRemainsCurrentWithoutSessionRevisionInput() {
        assertTrue(
            MemoryTaskCommitPolicy.headEvidenceCurrent(
                expectedHeadVersion = 3,
                currentHeadVersion = 3,
                expectedSourceHash = "source",
                currentSourceHash = "source",
                expectedArchive = "archive",
                currentArchive = "archive"
            )
        )
    }

    @Test
    fun changedHeadInputInvalidatesHeadEvidence() {
        assertFalse(
            MemoryTaskCommitPolicy.headEvidenceCurrent(
                expectedHeadVersion = 3,
                currentHeadVersion = 3,
                expectedSourceHash = "before",
                currentSourceHash = "after"
            )
        )
    }

    @Test
    fun changedTargetHeadInvalidatesHeadEvidence() {
        assertFalse(
            MemoryTaskCommitPolicy.headEvidenceCurrent(
                expectedHeadVersion = 3,
                currentHeadVersion = 4,
                expectedSourceHash = "source",
                currentSourceHash = "source"
            )
        )
    }

    @Test
    fun changedBackfillArchiveInvalidatesHeadEvidence() {
        assertFalse(
            MemoryTaskCommitPolicy.headEvidenceCurrent(
                expectedHeadVersion = 3,
                currentHeadVersion = 3,
                expectedSourceHash = "source",
                currentSourceHash = "source",
                expectedArchive = "before",
                currentArchive = "after"
            )
        )
    }

    @Test
    fun `unrelated episode changes do not invalidate episode generation`() {
        val unrelatedChanged = node("other", "s9", "new body")

        MemoryTaskCommitPolicy.requireEpisodeTargetCurrent(
            sourceTurnIds = listOf("s1", "s2"),
            expectedSourceHash = "target-hash",
            currentSourceHash = "target-hash",
            pendingSourceTurnIds = listOf("s1", "s2", "s3"),
            activeNodes = listOf(unrelatedChanged),
            label = "Episode"
        )
    }

    @Test
    fun `target source change invalidates only its episode generation`() {
        assertThrows(IllegalStateException::class.java) {
            MemoryTaskCommitPolicy.requireEpisodeTargetCurrent(
                sourceTurnIds = listOf("s1"),
                expectedSourceHash = "old",
                currentSourceHash = "new",
                pendingSourceTurnIds = listOf("s1"),
                activeNodes = emptyList(),
                label = "Episode"
            )
        }
    }

    @Test
    fun `new pending tail does not invalidate current episode batch`() {
        MemoryTaskCommitPolicy.requireEpisodeTargetCurrent(
            sourceTurnIds = listOf("s1", "s2"),
            expectedSourceHash = "same",
            currentSourceHash = "same",
            pendingSourceTurnIds = listOf("s1", "s2", "s3", "s4"),
            activeNodes = emptyList(),
            label = "补录Episode"
        )
    }

    @Test
    fun `removed target pending source invalidates episode generation`() {
        assertThrows(IllegalStateException::class.java) {
            MemoryTaskCommitPolicy.requireEpisodeTargetCurrent(
                sourceTurnIds = listOf("s1", "s2"),
                expectedSourceHash = "same",
                currentSourceHash = "same",
                pendingSourceTurnIds = listOf("s2", "s3"),
                activeNodes = emptyList(),
                label = "补录Episode"
            )
        }
    }

    @Test
    fun `unrelated pending insertion does not invalidate current episode batch`() {
        MemoryTaskCommitPolicy.requireEpisodeTargetCurrent(
            sourceTurnIds = listOf("s1", "s2"),
            expectedSourceHash = "same",
            currentSourceHash = "same",
            pendingSourceTurnIds = listOf("unrelated", "s1", "s2"),
            activeNodes = emptyList(),
            label = "补录Episode"
        )
    }

    @Test
    fun `unrelated node edit or addition does not invalidate compression evidence`() {
        val target = node("target", "s1", "target body")
        val unrelated = node("other-new", "s9", "changed body")

        MemoryTaskCommitPolicy.requireNodeEvidenceCurrent(
            expectedNodes = listOf(target),
            currentNodesById = listOf(target, unrelated).associateBy { it.id },
            activeNodeIds = setOf(target.id, unrelated.id),
            label = "压缩"
        )
    }

    @Test
    fun `target node edit invalidates compression evidence`() {
        val target = node("target", "s1", "old body")
        val changed = target.copy(content = "new body")

        assertThrows(IllegalStateException::class.java) {
            MemoryTaskCommitPolicy.requireNodeEvidenceCurrent(
                expectedNodes = listOf(target),
                currentNodesById = mapOf(changed.id to changed),
                activeNodeIds = setOf(target.id),
                label = "压缩"
            )
        }
    }

    private fun node(id: String, sourceId: String, body: String) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = listOf(sourceId),
        content = body,
        sourceHash = "$id-source",
        coverageHash = "$id-coverage"
    )
}
