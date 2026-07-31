package com.example.chatbar.domain.voice.qq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.chatbar.MainActivity
import com.example.chatbar.R

object QqVoiceTransferNotificationManager {
    const val CHANNEL_ID = "qq_voice_transfer"
    const val NOTIFICATION_ID = 1401

    fun init(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QQ 语音发送",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "控制实验性 QQ 原生语音自动发送"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun preparing(context: Context): Notification = ongoingBuilder(
        context = context,
        title = "正在准备 QQ 语音",
        text = "正在读取本地语音并准备播放器"
    ).addAction(cancelAction(context)).build()

    fun ready(context: Context, clipped: Boolean): Notification {
        val limitNote = if (clipped) "；只发送前 50 秒" else ""
        return ongoingBuilder(
            context = context,
            title = "QQ 语音已就绪",
            text = "进入目标聊天并切到“按住说话”，再点开始$limitNote"
        )
            .addAction(startAction(context))
            .addAction(cancelAction(context))
            .build()
    }

    fun waiting(context: Context): Notification = ongoingBuilder(
        context = context,
        title = "正在查找 QQ 录音键",
        text = "请保持目标 QQ 聊天的“按住说话”界面"
    ).addAction(cancelAction(context)).build()

    fun countdown(context: Context, secondsRemaining: Int): Notification = ongoingBuilder(
        context = context,
        title = "$secondsRemaining 秒后开始发送",
        text = "请保持 QQ 在前台，不要触碰录音区域"
    ).addAction(cancelAction(context)).build()

    fun recording(context: Context, clipDurationMs: Long): Notification = ongoingBuilder(
        context = context,
        title = "正在发送 QQ 语音",
        text = "自动按住录音键并外放 ${formatSeconds(clipDurationMs)} 秒，请勿切换页面"
    ).addAction(cancelAction(context)).build()

    fun terminal(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent(context))
            .build()

    fun notify(context: Context, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ongoingBuilder(context: Context, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent(context))

    private fun startAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, QqVoiceTransferService::class.java).apply {
            action = QqVoiceTransferService.ACTION_BEGIN
        }
        val pendingIntent = PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(0, "开始", pendingIntent)
    }

    private fun cancelAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, QqVoiceTransferService::class.java).apply {
            action = QqVoiceTransferService.ACTION_CANCEL
        }
        val pendingIntent = PendingIntent.getService(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(0, "取消", pendingIntent)
    }

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatSeconds(durationMs: Long): String =
        ((durationMs + 999L) / 1_000L).coerceAtLeast(1L).toString()
}
