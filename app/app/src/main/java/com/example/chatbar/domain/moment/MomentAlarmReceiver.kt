package com.example.chatbar.domain.moment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.chatbar.ChatBarApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MomentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action == MomentAlarmScheduler.ACTION_MOMENT_TICK ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pending = goAsync()
            ChatBarApp.instance.applicationScope.launch {
                try {
                    withTimeout(7000) {
                        ChatBarApp.instance.backupStartupReady.first { it }
                        ChatBarApp.instance.momentScheduler.kick("alarm:$action")
                    }
                } finally { pending.finish() }
            }
        }
    }
}
