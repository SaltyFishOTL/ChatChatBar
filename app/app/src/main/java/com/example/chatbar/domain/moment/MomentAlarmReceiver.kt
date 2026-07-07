package com.example.chatbar.domain.moment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.chatbar.ChatBarApp

class MomentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action == MomentAlarmScheduler.ACTION_MOMENT_TICK ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { ChatBarApp.instance.momentScheduler.kick("alarm:$action") }
        }
    }
}
