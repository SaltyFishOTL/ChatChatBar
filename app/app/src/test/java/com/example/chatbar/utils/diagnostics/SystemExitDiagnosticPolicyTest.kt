package com.example.chatbar.utils.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.system.OsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemExitDiagnosticPolicyTest {
    @Test
    fun ignoresBackgroundSigkillReportedByDevice() {
        assertFalse(
            SystemExitDiagnosticPolicy.isDiagnosticAbnormalExit(
                reason = ApplicationExitInfo.REASON_SIGNALED,
                status = OsConstants.SIGKILL,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
            )
        )
    }

    @Test
    fun ignoresBackgroundLowMemoryKill() {
        assertFalse(
            SystemExitDiagnosticPolicy.isDiagnosticAbnormalExit(
                reason = ApplicationExitInfo.REASON_LOW_MEMORY,
                status = 0,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            )
        )
    }

    @Test
    fun keepsVisibleSigkillDiagnostic() {
        assertTrue(
            SystemExitDiagnosticPolicy.isDiagnosticAbnormalExit(
                reason = ApplicationExitInfo.REASON_SIGNALED,
                status = OsConstants.SIGKILL,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            )
        )
    }

    @Test
    fun keepsForegroundServiceSigkillDiagnostic() {
        assertTrue(
            SystemExitDiagnosticPolicy.isDiagnosticAbnormalExit(
                reason = ApplicationExitInfo.REASON_SIGNALED,
                status = OsConstants.SIGKILL,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            )
        )
    }

    @Test
    fun keepsBackgroundJavaCrashDiagnostic() {
        assertTrue(
            SystemExitDiagnosticPolicy.isDiagnosticAbnormalExit(
                reason = ApplicationExitInfo.REASON_CRASH,
                status = 1,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            )
        )
    }
}
