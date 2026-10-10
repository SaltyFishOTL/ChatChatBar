package com.example.chatbar.domain.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.chatbar.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Local-only protection: no internet preflight, no AI work/stop signal. */
class LocalDataTransferForegroundService : Service() {
    private var generation = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = intent?.getLongExtra("generation", -1L) ?: -1L
        if (intent?.action == CANCEL) {
            val owner = synchronized(lock) { active?.takeIf { it.generation == incoming } }
            if (owner != null) {
                owner.stopRequested = true
                owner.cancel("迁移已取消")
                stopSelf()
            } else if (synchronized(lock) { active == null }) stopSelf()
            return START_NOT_STICKY
        }
        generation = synchronized(lock) { active?.generation ?: incoming }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "数据迁移", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val cancel = PendingIntent.getService(this, 1,
                Intent(this, LocalDataTransferForegroundService::class.java)
                    .setAction(CANCEL).putExtra("generation", generation),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("ChatBar 数据迁移")
                .setContentText("正在处理本地存档，可返回 APP 查看进度")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "取消", cancel).build()).build()
            startForeground(NOTIFICATION, notification)
            synchronized(lock) {
                pending[incoming]?.ready?.complete(Unit)
                active?.ready?.complete(Unit)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                pending[incoming]?.ready?.completeExceptionally(error)
                active?.ready?.completeExceptionally(error)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
        synchronized(lock) { active }?.cancel?.invoke("系统终止了数据迁移，请重新尝试")
    }

    override fun onDestroy() {
        synchronized(lock) { active?.takeIf { it.generation == generation && !it.stopRequested } }
            ?.cancel?.invoke("数据迁移前台服务已停止，请重试")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "chatbar_data_transfer"
        private const val NOTIFICATION = 2304
        private const val CANCEL = "com.example.chatbar.CANCEL_DATA_TRANSFER"
        private val lock = Any()
        private val protection = Mutex()
        private val main = Handler(Looper.getMainLooper())
        private var nextGeneration = 0L
        private var active: Lease? = null
        private val pending = mutableMapOf<Long, Lease>()

        private data class Lease(
            val generation: Long, val ready: CompletableDeferred<Unit>, val cancel: (String) -> Unit,
            var stopRequested: Boolean = false
        )

        suspend fun <T> runProtected(context: Context, cancel: (String) -> Unit, block: suspend () -> T): T =
            protection.withLock {
                val lease = synchronized(lock) {
                    Lease(++nextGeneration, CompletableDeferred(), cancel).also {
                        active = it
                        pending[it.generation] = it
                    }
                }
                var requested = false
                try {
                    context.startForegroundService(Intent(context, LocalDataTransferForegroundService::class.java)
                        .putExtra("generation", lease.generation))
                    requested = true
                    withTimeout(8000) { lease.ready.await() }
                    block()
                } finally {
                    lease.stopRequested = true
                    if (requested) {
                        // Stop only after promotion, and never stop another transfer's generation.
                        lease.ready.invokeOnCompletion {
                            main.post {
                                val current = synchronized(lock) {
                                    pending.remove(lease.generation)
                                    if (active === lease) { active = null; true } else false
                                }
                                if (current) context.stopService(Intent(context, LocalDataTransferForegroundService::class.java))
                            }
                        }
                    } else synchronized(lock) {
                        pending.remove(lease.generation)
                        if (active === lease) active = null
                    }
                }
            }
    }
}
