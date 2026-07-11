package com.example.chatbar.domain.moment

import com.example.chatbar.domain.image.NovelAiPromptPlan
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentGenerationServiceTest {
    @Test
    fun generationCheckpoint_roundTripsCompletedStages() {
        val checkpoint = MomentGenerationCheckpoint(
            decision = MomentPostDecision(shouldPost = true, reason = "有新进展"),
            draft = MomentDraft(
                senderName = "测试角色",
                text = "测试动态",
                imageBrief = "窗边随手拍"
            ),
            imagePrompt = NovelAiPromptPlan(
                baseCaption = "1girl, window",
                characterCaptions = emptyList()
            )
        )

        val encoded = Json.encodeToString(MomentGenerationCheckpoint.serializer(), checkpoint)
        val decoded = Json.decodeFromString(MomentGenerationCheckpoint.serializer(), encoded)

        assertEquals(checkpoint, decoded)
    }

    @Test
    fun transientImageFailures_areRetryable() {
        assertTrue("NovelAI 生图请求失败 (连接/读取超时)".isTransientMomentImageFailure())
        assertTrue("NovelAI 生图请求失败 (服务器连接意外断开)".isTransientMomentImageFailure())
        assertTrue("NovelAI 未返回最终图片".isTransientMomentImageFailure())
        assertTrue("NovelAI 生图失败 (NovelAI 网关错误, HTTP 502)".isTransientMomentImageFailure())
    }

    @Test
    fun permanentImageFailures_areNotRetryable() {
        assertFalse("NovelAI 生图失败 (认证失败，请检查 NovelAI Token 是否有效, HTTP 401)".isTransientMomentImageFailure())
        assertFalse("NovelAI 生图失败 (账户余额不足, HTTP 402)".isTransientMomentImageFailure())
        assertFalse("NovelAI 生图失败 (请求参数有误, HTTP 400)".isTransientMomentImageFailure())
    }
}
