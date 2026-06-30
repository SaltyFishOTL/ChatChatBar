package com.example.chatbar.domain.service

import android.content.Intent
import com.example.chatbar.ChatBarApp
import java.util.concurrent.atomic.AtomicInteger

object AiBackgroundWorkManager {
    private val activeCount = AtomicInteger(0)

    fun start(sessionId: String = "") {
        val ctx = ChatBarApp.instance
        if (activeCount.getAndIncrement() == 0) {
            StreamingNotificationManager.show(ctx, sessionId)
            ctx.startForegroundService(Intent(ctx, StreamingForegroundService::class.java).apply {
                putExtra("sessionId", sessionId)
            })
        } else {
            StreamingNotificationManager.show(ctx, sessionId)
        }
    }

    fun finish() {
        val remaining = activeCount.decrementAndGet()
        if (remaining > 0) return
        activeCount.set(0)
        try {
            ChatBarApp.instance.stopService(Intent(ChatBarApp.instance, StreamingForegroundService::class.java))
        } catch (_: Exception) {}
        try {
            StreamingNotificationManager.cancel(ChatBarApp.instance)
        } catch (_: Exception) {}
    }

    suspend fun <T> run(sessionId: String = "", block: suspend () -> T): T {
        start(sessionId)
        return try {
            block()
        } finally {
            finish()
        }
    }
}
