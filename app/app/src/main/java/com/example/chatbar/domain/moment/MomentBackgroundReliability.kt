package com.example.chatbar.domain.moment

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

enum class MomentReliabilityLevel {
    OK,
    WARNING,
    BLOCKED
}

data class MomentReliabilityItem(
    val title: String,
    val detail: String,
    val level: MomentReliabilityLevel
)

data class MomentReliabilityState(
    val items: List<MomentReliabilityItem> = emptyList()
) {
    val hasWarning: Boolean get() = items.any { it.level != MomentReliabilityLevel.OK }
}

object MomentBackgroundReliability {
    fun check(context: Context, autoStartConfirmed: Boolean): MomentReliabilityState {
        val appContext = context.applicationContext
        val notificationsAllowed = notificationsAllowed(appContext)
        val batteryAllowed = batteryOptimizationAllowed(appContext)
        val backgroundRestricted = isBackgroundRestricted(appContext)
        val autoStartKnown = autoStartConfirmed || hasAutoStartIntent(appContext)
        return MomentReliabilityState(
            items = listOf(
                MomentReliabilityItem(
                    title = "通知权限",
                    detail = if (notificationsAllowed) "已允许前台任务通知" else "未允许通知，后台生成更容易被系统中断",
                    level = if (notificationsAllowed) MomentReliabilityLevel.OK else MomentReliabilityLevel.BLOCKED
                ),
                MomentReliabilityItem(
                    title = "电池优化",
                    detail = if (batteryAllowed) "已放行或系统无需设置" else "建议允许忽略电池优化",
                    level = if (batteryAllowed) MomentReliabilityLevel.OK else MomentReliabilityLevel.WARNING
                ),
                MomentReliabilityItem(
                    title = "后台限制",
                    detail = if (backgroundRestricted) "系统正在限制后台运行" else "未检测到系统后台限制",
                    level = if (backgroundRestricted) MomentReliabilityLevel.BLOCKED else MomentReliabilityLevel.OK
                ),
                MomentReliabilityItem(
                    title = "自启动",
                    detail = when {
                        autoStartConfirmed -> "用户已确认处理"
                        autoStartKnown -> "检测到厂商自启动入口，建议开启"
                        else -> "未识别厂商自启动入口，可在系统应用设置中手动允许"
                    },
                    level = if (autoStartConfirmed) MomentReliabilityLevel.OK else MomentReliabilityLevel.WARNING
                )
            )
        )
    }

    fun openAutoStartSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        autoStartIntents(appContext).forEach { intent ->
            if (launch(appContext, intent)) return true
        }
        return launch(appContext, appDetailsIntent(appContext))
    }

    fun openBatterySettings(context: Context): Boolean {
        val appContext = context.applicationContext
        val packageName = appContext.packageName
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(appContext)
        )
        return intents.any { launch(appContext, it) }
    }

    fun openNotificationSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            }
        } else {
            appDetailsIntent(appContext)
        }
        return launch(appContext, intent)
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun batteryOptimizationAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        return manager.isBackgroundRestricted
    }

    private fun hasAutoStartIntent(context: Context): Boolean =
        autoStartIntents(context).any { intent ->
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }

    private fun autoStartIntents(context: Context): List<Intent> {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = mutableListOf<Intent>()
        fun component(packageName: String, className: String) {
            candidates += Intent().setComponent(ComponentName(packageName, className))
        }
        when {
            "xiaomi" in manufacturer || "redmi" in manufacturer -> component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            "oppo" in manufacturer || "realme" in manufacturer -> {
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            }
            "vivo" in manufacturer || "iqoo" in manufacturer -> component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            "huawei" in manufacturer || "honor" in manufacturer -> component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            "oneplus" in manufacturer -> component(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            "samsung" in manufacturer -> candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        return candidates.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun launch(context: Context, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved == null) return false
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
