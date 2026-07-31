package com.example.chatbar.domain.voice.qq

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

class QqVoiceTransferPreflight(
    context: Context,
    private val gestureGateway: QqVoiceGestureGateway
) {
    private val appContext = context.applicationContext

    fun evaluate(request: QqVoiceTransferRequest): QqVoiceTransferFailure? =
        QqVoiceTransferPolicy.evaluatePreflight(snapshot(request))

    fun snapshot(request: QqVoiceTransferRequest): QqVoicePreflightSnapshot {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val source = File(request.audioPath)
        return QqVoicePreflightSnapshot(
            fileReady = source.isFile && source.length() > 0L,
            durationValid = request.sourceDurationMs > 0L && request.clipDurationMs > 0L,
            qqInstalled = isQqInstalled(),
            accessibilityEnabled = isAccessibilityEnabled() && gestureGateway.connected,
            notificationsEnabled = areNotificationsEnabled(),
            externalAudioRouteConnected = hasExternalAudioRoute(audioManager),
            mediaVolumeAudible = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        )
    }

    fun isAccessibilityEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val serviceInfo = info.resolveInfo.serviceInfo
            serviceInfo.packageName == appContext.packageName &&
                serviceInfo.name == QqVoiceAccessibilityService::class.java.name
        }
    }

    fun areNotificationsEnabled(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    fun isQqInstalled(): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getApplicationInfo(
                QqVoiceTransferPolicy.QQ_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getApplicationInfo(QqVoiceTransferPolicy.QQ_PACKAGE, 0)
        }
        info.enabled && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }.getOrDefault(false)

    private fun hasExternalAudioRoute(audioManager: AudioManager): Boolean {
        val blockedTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in blockedTypes
        }
    }
}
