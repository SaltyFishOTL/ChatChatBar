package com.example.chatbar.domain.moment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentGenerationServiceTest {
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
