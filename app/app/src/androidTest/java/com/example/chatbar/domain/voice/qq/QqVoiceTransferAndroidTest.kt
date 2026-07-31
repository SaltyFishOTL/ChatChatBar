package com.example.chatbar.domain.voice.qq

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QqVoiceTransferAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mediaPlaybackAndAccessibilityServicesHaveRestrictedManifestConfiguration() {
        val mediaService = serviceInfo(QqVoiceTransferService::class.java)
        val accessibilityService = serviceInfo(QqVoiceAccessibilityService::class.java)

        assertTrue(
            mediaService.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
        )
        assertFalse(mediaService.exported)
        assertTrue(accessibilityService.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibilityService.permission)
        assertNotNull(accessibilityService.metaData)
    }

    @Test
    fun readyNotificationExposesStartAndCancelActions() {
        QqVoiceTransferNotificationManager.init(context)

        val notification = QqVoiceTransferNotificationManager.ready(context, clipped = true)
        val actionTitles = notification.actions.map { it.title.toString() }

        assertEquals(listOf("开始", "取消"), actionTitles)
        assertTrue(notification.actions.all { it.actionIntent != null })
    }

    @Suppress("DEPRECATION")
    private fun serviceInfo(serviceClass: Class<*>): ServiceInfo {
        val component = ComponentName(context, serviceClass)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        }
    }
}
