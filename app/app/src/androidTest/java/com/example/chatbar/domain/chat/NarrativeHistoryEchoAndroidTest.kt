package com.example.chatbar.domain.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NarrativeHistoryEchoAndroidTest {
    @Test
    fun formatLabelsAndCounterexamplesCompileOnAndroidAndKeepTheirMeaning() {
        val story = "她关上门。"
        val reply = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"

        for (label in listOf("以下标签仅供举例，不要在回复中输出：", "The following is a counterexample:")) {
            val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, "$label\n$reply")
            assertEquals(story, cleanup.completedBody(reply))
        }

        for (label in listOf("输出模板：", "Required output template:")) {
            val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, "$label\n$reply")
            assertEquals(reply, cleanup.completedBody(reply))
        }
    }
}
