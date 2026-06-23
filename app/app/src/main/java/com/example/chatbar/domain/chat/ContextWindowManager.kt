package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage

/**
 * 上下文窗口管理器 — 控制发送给 LLM 的消息范围
 *
 * 职责：
 * - 从全部消息中截取最近 N 条作为上下文
 * - 识别需要归档（向量化）的旧消息
 * - 判断是否需要刷新系统提示词
 */
class ContextWindowManager {

    /**
     * 获取最近的消息（发送给 LLM 的上下文）
     *
     * @param allMessages 全部消息列表（按时间正序）
     * @param windowSize  上下文窗口大小（消息条数）
     * @return 最近的 [windowSize] 条消息
     */
    fun getRecentMessages(
        allMessages: List<ChatMessage>,
        windowSize: Int
    ): List<ChatMessage> {
        if (allMessages.size <= windowSize) return allMessages
        return allMessages.takeLast(windowSize)
    }

    /**
     * 获取需要归档的消息（已滑出上下文窗口的旧消息）
     *
     * 这些消息可以被向量化存入 RAG 作为长期记忆。
     *
     * @param allMessages 全部消息列表（按时间正序）
     * @param windowSize  上下文窗口大小
     * @return 滑出窗口的旧消息列表
     */
    fun getMessagesToArchive(
        allMessages: List<ChatMessage>,
        windowSize: Int
    ): List<ChatMessage> {
        if (allMessages.size <= windowSize) return emptyList()
        return allMessages.dropLast(windowSize)
    }

    /**
     * 判断是否需要刷新系统提示词
     *
     * 当消息总数超过窗口大小时，说明有旧消息被裁剪，
     * 此时应该用 RAG 重新组装系统提示词以包含相关记忆。
     *
     * @param messageCount 当前消息总数
     * @param windowSize   上下文窗口大小
     * @return 是否需要刷新
     */
    fun shouldRefreshSystemPrompt(
        messageCount: Int,
        windowSize: Int
    ): Boolean {
        return messageCount > windowSize
    }
}
