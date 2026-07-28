package com.example.chatbar.domain.voice

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDownloadFileNamePolicyTest {
    @Test
    fun `file name contains sanitized chat character and download time`() {
        val fileName = buildVoiceDownloadFileName(
            chatName = " 我的/聊天 ",
            characterName = "角色:甲",
            downloadedAtMillis = 0L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals("我的_聊天_角色_甲_19700101_000000_000.mp3", fileName)
    }

    @Test
    fun `file name uses readable fallbacks and removes unsafe characters`() {
        val fileName = buildVoiceDownloadFileName(
            chatName = " . ",
            characterName = "\u0000",
            downloadedAtMillis = 1_234L,
            zoneId = ZoneOffset.UTC
        )

        assertEquals("聊天_角色_19700101_000001_234.mp3", fileName)
        assertFalse(fileName.contains('\u0000'))
        assertTrue(fileName.endsWith(".mp3"))
    }
}
