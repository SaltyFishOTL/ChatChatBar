package com.example.chatbar.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogEntryMemoryTest {
    @Test
    fun `request memory indicators read actual serialized body`() {
        val complete = DebugLogEntry(
            sessionId = "session",
            requestBodyJson = """{"messages":[{"content":"【ARCHIVE｜历史档案】\\n旧事"},{"content":"【HEAD｜当前状态】\\n现在"}]}"""
        )
        val headOnly = DebugLogEntry(
            sessionId = "session",
            requestBodyJson = """{"messages":[{"content":"【HEAD｜当前状态】\\n现在"}]}"""
        )

        assertTrue(complete.requestContainsArchive)
        assertTrue(complete.requestContainsHead)
        assertFalse(headOnly.requestContainsArchive)
        assertTrue(headOnly.requestContainsHead)
    }
}
