package com.example.chatbar.domain.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.chatbar.MainActivity
import com.example.chatbar.R

object StreamingNotificationManager {
    const val CHANNEL_ID = "chatbar_streaming"
    const val CHANNEL_ID_COMPLETE = "chatbar_complete"
    const val NOTIFICATION_ID = 1001
    const val NOTIFICATION_ID_COMPLETE = 1002
    const val ACTION_STOP = "com.example.chatbar.STOP_STREAMING"
    private const val MIN_UPDATE_INTERVAL_MS = 500L

    private var notificationManager: NotificationManager? = null
    private var lastUpdateMs = 0L

    fun init(context: Context) {
        if (notificationManager != null) return
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "AI 生成",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AI 内容生成进行中"
            setShowBadge(false)
        }
        notificationManager!!.createNotificationChannel(channel)
        val completeChannel = android.app.NotificationChannel(
            CHANNEL_ID_COMPLETE,
            "生成完成",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI 内容生成完毕通知"
            setShowBadge(true)
            setSound(null, null)
        }
        notificationManager!!.createNotificationChannel(completeChannel)
    }

    fun show(context: Context, sessionId: String) {
        val notification = buildNotification(context, "", sessionId)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    fun update(context: Context, text: String, sessionId: String) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateMs < MIN_UPDATE_INTERVAL_MS) return
        lastUpdateMs = now
        val preview = text.takeLast(50)
        val notification = buildNotification(context, preview, sessionId)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    fun buildNotification(context: Context, text: String, sessionId: String): Notification {
        val stopIntent = Intent(context, StreamingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = text.ifEmpty { "正在等待响应..." }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle("AI 正在生成...")
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "停止生成", stopPendingIntent)
            .build()
    }

    fun cancel(context: Context) {
        lastUpdateMs = 0L
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    fun showComplete(context: Context, text: String) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val preview = text.take(80)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle("生成完成")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(240)))
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()
        notificationManager?.notify(NOTIFICATION_ID_COMPLETE, notification)
    }
}
