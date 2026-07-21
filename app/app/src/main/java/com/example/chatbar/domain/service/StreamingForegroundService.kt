package com.example.chatbar.domain.service

import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.chatbar.ChatBarApp

class StreamingForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var activeGeneration = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra("sessionId") ?: ""
        val generation = AiBackgroundWorkManager.workGenerationFrom(intent)
        try {
            val notification = StreamingNotificationManager.buildNotification(this, "", sessionId)
            startForeground(StreamingNotificationManager.NOTIFICATION_ID, notification)
            if (intent?.action == StreamingNotificationManager.ACTION_STOP) {
                ChatBarApp.instance.streamingStopRequested.value = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                StreamingNotificationManager.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }

            activeGeneration = generation
            acquireWakeLock()
            acquireWifiLock()
            AiBackgroundWorkManager.foregroundServiceReady(generation)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start foreground generation protection", error)
            AiBackgroundWorkManager.foregroundServiceStartFailed(generation, error)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val generation = activeGeneration
        releaseWifiLock()
        releaseWakeLock()
        if (generation >= 0L) {
            AiBackgroundWorkManager.foregroundServiceStopped(generation)
        }
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

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "${packageName}:ai-generation"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private companion object {
        const val TAG = "StreamingForeground"
    }
}
