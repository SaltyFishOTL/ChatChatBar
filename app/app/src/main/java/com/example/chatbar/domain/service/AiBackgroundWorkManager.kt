package com.example.chatbar.domain.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.chatbar.ChatBarApp
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

class BackgroundGenerationProtectionException(
    message: String
) : IllegalStateException(message)

class BackgroundGenerationProtectionCancellationException(
    val reason: String
) : kotlinx.coroutines.CancellationException("后台生成保护失效：$reason")

internal fun releaseForegroundServiceWhenReady(
    ready: CompletableDeferred<Unit>,
    stopStartedService: () -> Unit,
    clearFailedStartNotification: () -> Unit
) {
    ready.invokeOnCompletion { error ->
        if (error == null) {
            stopStartedService()
        } else {
            clearFailedStartNotification()
        }
    }
}

object AiBackgroundWorkManager {
    private const val FOREGROUND_READY_TIMEOUT_MS = 8_000L
    private const val NETWORK_LOSS_GRACE_MS = 5_000L
    private const val EXTRA_WORK_GENERATION = "workGeneration"
    private const val TAG = "AiBackgroundWork"

    private data class ForegroundLease(
        val generation: Long,
        val ready: CompletableDeferred<Unit>,
        val protection: ProtectionSignal,
        val networkGuard: NetworkGuard
    )

    private class ProtectionSignal {
        private val lossReason = AtomicReference<String?>(null)
        private val loss = CompletableDeferred<String>()

        fun fail(reason: String) {
            if (lossReason.compareAndSet(null, reason)) {
                loss.complete(reason)
            }
        }

        fun throwIfFailed() {
            lossReason.get()?.let { reason ->
                throw BackgroundGenerationProtectionException(reason)
            }
        }

        suspend fun awaitFailure(): String = loss.await()

        fun observe(onFailure: (String) -> Unit): DisposableHandle =
            loss.invokeOnCompletion {
                lossReason.get()?.let(onFailure)
            }
    }

    private class NetworkGuard(
        context: Context,
        private val protection: ProtectionSignal,
        private val requireValidatedInternet: Boolean
    ) {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        private val lossHandler = Handler(Looper.getMainLooper())
        @Volatile private var pendingLossReason: String? = null
        private val confirmLoss = Runnable {
            val reason = pendingLossReason ?: return@Runnable
            if (!hasRequiredNetwork()) {
                protection.fail("$reason（持续 ${NETWORK_LOSS_GRACE_MS / 1000} 秒）")
            }
        }
        private var callbackRegistered = false
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.isRequiredNetwork(requireValidatedInternet)) {
                    scheduleLossConfirmation("网络失去互联网验证")
                } else {
                    cancelLossConfirmation()
                }
            }

            override fun onAvailable(network: Network) {
                cancelLossConfirmation()
            }

            override fun onLost(network: Network) {
                scheduleLossConfirmation("设备网络已断开")
            }

            override fun onUnavailable() {
                scheduleLossConfirmation("设备没有可用网络")
            }
        }

        init {
            if (!hasRequiredNetwork()) {
                protection.fail("未检测到可用网络，未发送模型请求")
            } else {
                try {
                    connectivityManager?.registerDefaultNetworkCallback(callback)
                    callbackRegistered = connectivityManager != null
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to monitor active network", error)
                    protection.fail("无法监测网络状态，未发送模型请求")
                }
            }
        }

        fun requireValidatedInternet() {
            if (!hasValidatedInternet()) {
                protection.fail("网络不可用，未发送模型请求")
            }
            protection.throwIfFailed()
        }

        fun close() {
            cancelLossConfirmation()
            if (!callbackRegistered) return
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            callbackRegistered = false
        }

        private fun scheduleLossConfirmation(reason: String) {
            pendingLossReason = reason
            lossHandler.removeCallbacks(confirmLoss)
            lossHandler.postDelayed(confirmLoss, NETWORK_LOSS_GRACE_MS)
        }

        private fun cancelLossConfirmation() {
            pendingLossReason = null
            lossHandler.removeCallbacks(confirmLoss)
        }

        private fun hasValidatedInternet(): Boolean {
            val capabilities = connectivityManager
                ?.activeNetwork
                ?.let(connectivityManager::getNetworkCapabilities)
                ?: return false
            return capabilities.isValidatedInternet()
        }

        private fun hasRequiredNetwork(): Boolean {
            val capabilities = connectivityManager
                ?.activeNetwork
                ?.let(connectivityManager::getNetworkCapabilities)
                ?: return false
            return capabilities.isRequiredNetwork(requireValidatedInternet)
        }
    }

    private val lock = Any()
    private var activeCount = 0
    private var nextGeneration = 0L
    private var currentLease: ForegroundLease? = null
    private val releasingLeases = mutableMapOf<Long, ForegroundLease>()

    fun start(sessionId: String = "") {
        acquireForegroundLease(sessionId)
    }

    private fun acquireForegroundLease(
        sessionId: String,
        requireValidatedInternet: Boolean = true
    ): ForegroundLease = synchronized(lock) {
        val context = ChatBarApp.instance
        val lease = if (activeCount == 0) {
            val protection = ProtectionSignal()
            ForegroundLease(
                generation = ++nextGeneration,
                ready = CompletableDeferred(),
                protection = protection,
                networkGuard = NetworkGuard(context, protection, requireValidatedInternet)
            ).also { currentLease = it }
        } else {
            checkNotNull(currentLease)
        }
        activeCount += 1

        if (activeCount == 1) {
            try {
                context.startForegroundService(Intent(context, StreamingForegroundService::class.java).apply {
                    putExtra("sessionId", sessionId)
                    putExtra(EXTRA_WORK_GENERATION, lease.generation)
                })
            } catch (error: Exception) {
                lease.ready.completeExceptionally(
                    IllegalStateException("无法启动后台生成前台服务", error)
                )
            }
        } else {
            StreamingNotificationManager.show(context, sessionId)
        }
        lease
    }

    internal fun foregroundServiceReady(generation: Long) {
        synchronized(lock) {
            leaseForGeneration(generation)?.ready?.complete(Unit)
        }
    }

    internal fun foregroundServiceStartFailed(generation: Long, error: Throwable) {
        synchronized(lock) {
            leaseForGeneration(generation)?.ready?.completeExceptionally(
                IllegalStateException("后台生成前台服务启动失败", error)
            )
        }
    }

    internal fun foregroundServiceStopped(generation: Long) {
        synchronized(lock) {
            currentLease
                ?.takeIf { it.generation == generation }
                ?.protection
                ?.fail("后台前台服务已停止，已中止生成")
        }
    }

    internal fun observeProtectionLoss(onLoss: (String) -> Unit): DisposableHandle? {
        val protection = synchronized(lock) { currentLease?.protection } ?: return null
        return protection.observe(onLoss)
    }

    private suspend fun awaitForegroundProtection(lease: ForegroundLease) {
        lease.networkGuard.requireValidatedInternet()
        try {
            withTimeout(FOREGROUND_READY_TIMEOUT_MS) {
                lease.ready.await()
            }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "Foreground service did not become ready before generation", error)
            throw BackgroundGenerationProtectionException("后台生成保护启动超时，未发送模型请求")
        }
        lease.networkGuard.requireValidatedInternet()
    }

    private suspend fun awaitForegroundProtection(
        lease: ForegroundLease,
        requireValidatedInternet: Boolean
    ) {
        if (requireValidatedInternet) lease.networkGuard.requireValidatedInternet()
        try {
            withTimeout(FOREGROUND_READY_TIMEOUT_MS) { lease.ready.await() }
        } catch (error: TimeoutCancellationException) {
            throw BackgroundGenerationProtectionException("后台生成保护启动超时，未发送模型请求")
        }
        if (requireValidatedInternet) lease.networkGuard.requireValidatedInternet()
    }

    suspend fun awaitForegroundProtection() {
        val lease = synchronized(lock) {
            checkNotNull(currentLease) { "没有正在启动的后台生成前台服务" }
        }
        awaitForegroundProtection(lease)
    }

    fun finish() {
        synchronized(lock) {
            if (activeCount <= 0) {
                Log.w(TAG, "Ignoring unmatched background-work finish")
                return
            }
            activeCount -= 1
            if (activeCount > 0) return

            val finishedLease = currentLease
            finishedLease?.networkGuard?.close()
            currentLease = null
            if (finishedLease == null) {
                cancelForegroundNotificationIfIdle()
                return
            }
            releasingLeases[finishedLease.generation] = finishedLease
            releaseForegroundServiceWhenReady(
                ready = finishedLease.ready,
                stopStartedService = {
                    removeReleasingLease(finishedLease.generation)
                    stopForegroundServiceIfIdle()
                },
                clearFailedStartNotification = {
                    removeReleasingLease(finishedLease.generation)
                    cancelForegroundNotificationIfIdle()
                }
            )
        }
    }

    private fun leaseForGeneration(generation: Long): ForegroundLease? =
        currentLease?.takeIf { it.generation == generation }
            ?: releasingLeases[generation]

    private fun removeReleasingLease(generation: Long) {
        synchronized(lock) {
            releasingLeases.remove(generation)
        }
    }

    private fun stopForegroundServiceIfIdle() {
        synchronized(lock) {
            if (activeCount > 0) return
            try {
                ChatBarApp.instance.stopService(
                    Intent(ChatBarApp.instance, StreamingForegroundService::class.java)
                )
            } catch (_: Exception) {
            }
            cancelForegroundNotificationIfIdle()
        }
    }

    private fun cancelForegroundNotificationIfIdle() {
        synchronized(lock) {
            if (activeCount > 0) return
            try {
                StreamingNotificationManager.cancel(ChatBarApp.instance)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun <T> run(
        sessionId: String = "",
        requireValidatedInternet: Boolean = true,
        block: suspend () -> T
    ): T {
        val lease = acquireForegroundLease(sessionId, requireValidatedInternet)
        return try {
            awaitForegroundProtection(lease, requireValidatedInternet)
            runWhileProtected(lease, block)
        } finally {
            finish()
        }
    }

    private suspend fun <T> runWhileProtected(
        lease: ForegroundLease,
        block: suspend () -> T
    ): T = coroutineScope {
        lease.protection.throwIfFailed()
        val blockJob = async { block() }
        val lossJob = async { lease.protection.awaitFailure() }
        try {
            select {
                blockJob.onAwait { it }
                lossJob.onAwait { reason ->
                    throw BackgroundGenerationProtectionException(reason)
                }
            }
        } finally {
            lossJob.cancel()
            blockJob.cancel()
        }
    }

    internal fun workGenerationFrom(intent: Intent?): Long =
        intent?.getLongExtra(EXTRA_WORK_GENERATION, -1L) ?: -1L

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))

    private fun NetworkCapabilities.isRequiredNetwork(requireValidated: Boolean): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!requireValidated || hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
}
