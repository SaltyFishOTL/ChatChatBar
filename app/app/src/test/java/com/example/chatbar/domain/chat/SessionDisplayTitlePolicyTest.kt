package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDisplayTitlePolicyTest {
    @Test
    fun `blank override falls back to source title`() {
        val session = session(displayTitleOverride = "   ")

        assertEquals("默认名称", SessionDisplayTitlePolicy.resolve(session))
        assertNull(SessionDisplayTitlePolicy.normalize(session.displayTitleOverride))
    }

    @Test
    fun `override is trimmed and limited`() {
        val input = "  ${"名".repeat(SessionDisplayTitlePolicy.MAX_LENGTH + 5)}  "

        val normalized = requireNotNull(SessionDisplayTitlePolicy.normalize(input))

        assertEquals(SessionDisplayTitlePolicy.MAX_LENGTH, normalized.length)
        assertEquals(normalized, SessionDisplayTitlePolicy.resolve(session(normalized)))
    }

    @Test
    fun `override remains single line after pasted line breaks`() {
        assertEquals("第一章 第二章", SessionDisplayTitlePolicy.normalize(" 第一章\r\n第二章 "))
    }

    private fun session(displayTitleOverride: String?) = ChatSession(
        id = "session",
        characterCardId = "card",
        title = "默认名称",
        displayTitleOverride = displayTitleOverride,
        createdAt = 1,
        updatedAt = 1
    )
}
