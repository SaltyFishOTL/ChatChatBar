package com.example.chatbar.domain.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.chatbar.ChatBarApp

class StreamingForegroundService : Service() {

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

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
