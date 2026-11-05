package com.example.chatbar.domain.chat

/** Transport evidence is separate from Done, which also preserves legacy partial replies. */
data class ChatReplyCompletion(
    val finishReason: String? = null,
    val refused: Boolean = false,
    val transportFailed: Boolean = false,
    val toolReplyCompleted: Boolean = false
)

object AutomaticChatImagePolicy {
    fun skipReason(completion: ChatReplyCompletion?, text: String): String? = when {
        completion == null -> "未取得回复完成状态"
        completion.transportFailed -> "回复连接发生错误"
        completion.refused -> "模型拒绝生成"
        completion.finishReason == "length" -> "回复达到输出上限而截断"
        completion.finishReason != "stop" &&
            !(completion.toolReplyCompleted && completion.finishReason in setOf("tool_calls", "function_call")) ->
            "未确认回复正常完整结束"
        text.isBlank() -> "回复正文为空"
        else -> null
    }
}
