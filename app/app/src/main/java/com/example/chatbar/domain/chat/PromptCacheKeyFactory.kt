package com.example.chatbar.domain.chat

import java.security.MessageDigest

/** 为同一份固定设定生成稳定的缓存路由键。 */
object PromptCacheKeyFactory {
    fun cacheKey(stableSystemPrompt: String): String =
        "chatbar-${sha256(stableSystemPrompt).take(48)}"

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
