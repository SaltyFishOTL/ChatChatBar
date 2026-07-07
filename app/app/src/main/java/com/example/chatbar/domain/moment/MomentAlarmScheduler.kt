package com.example.chatbar.domain.moment

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object MomentAlarmScheduler {
    const val ACTION_MOMENT_TICK = "com.example.chatbar.action.MOMENT_TICK"

    fun scheduleNext(context: Context, scheduledAt: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntent(context)
        if (scheduledAt == null) {
            alarmManager.cancel(pendingIntent)
            return
        }
        val at = scheduledAt.coerceAtLeast(System.currentTimeMillis() + MIN_ALARM_DELAY_MS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MomentAlarmReceiver::class.java).apply {
            action = ACTION_MOMENT_TICK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, 7104, intent, flags)
    }

    private const val MIN_ALARM_DELAY_MS = 10_000L
}
