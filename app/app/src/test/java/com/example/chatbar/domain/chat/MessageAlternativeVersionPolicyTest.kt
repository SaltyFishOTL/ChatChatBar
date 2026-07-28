package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageAlternativeVersionPolicyTest {
    @Test
    fun `new version id skips regenerated bubble message id collision`() {
        val message = message(
            alternatives = listOf("原版本"),
            alternativeVersionIds = listOf("message-1"),
            currentAlternativeIndex = 0,
            currentAlternativeVersionId = "message-1"
        )
        val candidates = ArrayDeque(listOf("message-1", "version-2"))

        val versionId = MessageAlternativeVersionPolicy.newVersionId(message) {
            candidates.removeFirst()
        }

        assertEquals("version-2", versionId)
    }

    @Test
    fun `legacy alternatives receive deterministic stable ids`() {
        val message = message(
            alternatives = listOf("版本一", "版本二"),
            currentAlternativeIndex = 1
        )

        val firstRead = MessageAlternativeVersionPolicy.versions(message)
        val secondRead = MessageAlternativeVersionPolicy.versions(message)

        assertEquals(firstRead.map { it.id }, secondRead.map { it.id })
        assertNotEquals(firstRead[0].id, firstRead[1].id)
        assertEquals(firstRead[1].id, MessageAlternativeVersionPolicy.activeVersionId(message))
    }

    @Test
    fun `append keeps ids paired when oldest version is trimmed`() {
        val original = message(
            alternatives = listOf("一", "二", "三"),
            alternativeVersionIds = listOf("v1", "v2", "v3"),
            currentAlternativeIndex = 2,
            currentAlternativeVersionId = "v3"
        )

        val updated = MessageAlternativeVersionPolicy.append(
            message = original,
            content = "四",
            newVersionId = "v4",
            maxVersions = 3,
            updatedAt = 20
        )

        assertEquals(listOf("二", "三", "四"), updated.alternatives)
        assertEquals(listOf("v2", "v3", "v4"), updated.alternativeVersionIds)
        assertEquals("v4", updated.currentAlternativeVersionId)
        assertEquals(2, updated.currentAlternativeIndex)
    }

    @Test
    fun `select and collapse preserve active version identity`() {
        val original = message(
            alternatives = listOf("一", "二"),
            alternativeVersionIds = listOf("v1", "v2"),
            currentAlternativeIndex = 0,
            currentAlternativeVersionId = "v1"
        )

        val selected = MessageAlternativeVersionPolicy.select(original, 1, updatedAt = 20)
        val collapsed = MessageAlternativeVersionPolicy.collapseToEditedContent(
            selected,
            "编辑后的二",
            updatedAt = 30
        )

        assertEquals("二", selected.content)
        assertEquals("v2", selected.currentAlternativeVersionId)
        assertEquals(emptyList<String>(), collapsed.alternatives)
        assertEquals(emptyList<String>(), collapsed.alternativeVersionIds)
        assertEquals("v2", MessageAlternativeVersionPolicy.activeVersionId(collapsed))
    }

    private fun message(
        alternatives: List<String>,
        alternativeVersionIds: List<String> = emptyList(),
        currentAlternativeIndex: Int,
        currentAlternativeVersionId: String? = null
    ): ChatMessage = ChatMessage(
        id = "message-1",
        sessionId = "session-1",
        role = MessageRole.ASSISTANT,
        content = alternatives[currentAlternativeIndex],
        alternatives = alternatives,
        alternativeVersionIds = alternativeVersionIds,
        currentAlternativeIndex = currentAlternativeIndex,
        currentAlternativeVersionId = currentAlternativeVersionId,
        createdAt = 1,
        updatedAt = 1
    )
}
