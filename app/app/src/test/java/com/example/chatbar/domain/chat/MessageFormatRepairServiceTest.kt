package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFormatRepairServiceTest {
    @Test
    fun `output limit follows model and content bounds`() {
        assertEquals(1_024, MessageFormatRepairService.outputTokenLimit("短文", null))
        assertEquals(8_192, MessageFormatRepairService.outputTokenLimit("字".repeat(5_000), null))
        assertEquals(2_000, MessageFormatRepairService.outputTokenLimit("字".repeat(5_000), 2_000))
        assertEquals(1_024, MessageFormatRepairService.outputTokenLimit("😀".repeat(10), 4_096))
    }
}
