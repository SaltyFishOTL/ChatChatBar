package com.example.chatbar.domain.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.example.chatbar.ChatBarApp

class StreamingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == StreamingNotificationManager.ACTION_STOP) {
            ChatBarApp.instance.streamingStopRequested.value = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            StreamingNotificationManager.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra("sessionId") ?: ""
        val notification = StreamingNotificationManager.buildNotification(this, "", sessionId)
        startForeground(StreamingNotificationManager.NOTIFICATION_ID, notification)
        acquireWakeLock()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:chat-streaming"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
