package com.example.chatbar.domain.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.service.BackgroundGenerationProtectionException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MemoryMaintenanceTrigger { SESSION_LOADED, REPLY_PERSISTED, NETWORK_RESTORED, RETRY, MANUAL }

/** Application-owned runner. View destruction never cancels paid maintenance calls. */
class LongTermMemoryAutoMaintenanceCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val modelResolver: EffectiveModelResolver,
    private val memoryService: LongTermMemoryService
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val runnerMutex = Mutex()
    private val scheduled = ConcurrentHashMap.newKeySet<String>()
    private val scheduledBackfills = ConcurrentHashMap.newKeySet<String>()
    private val _backfillProgress =
        MutableStateFlow<Map<String, MemoryBackfillProgress>>(emptyMap())
    val backfillProgress: StateFlow<Map<String, MemoryBackfillProgress>> =
        _backfillProgress.asStateFlow()
    @Volatile private var currentSessionId: String? = null

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentSessionId?.let { enqueue(it, MemoryMaintenanceTrigger.NETWORK_RESTORED) }
                }
            })
        }
    }

    fun activateSession(sessionId: String) {
        currentSessionId = sessionId
        enqueue(sessionId, MemoryMaintenanceTrigger.SESSION_LOADED)
    }

    fun enqueue(
        sessionId: String,
        trigger: MemoryMaintenanceTrigger,
        manual: Boolean = trigger == MemoryMaintenanceTrigger.MANUAL
    ) {
        if (!manual && currentSessionId != sessionId) return
        if (!scheduled.add(sessionId)) return
        scope.launch {
            try {
                runnerMutex.withLock {
                    if (!manual && currentSessionId != sessionId) return@withLock
                    maintain(sessionId, manual)
                }
            } finally {
                scheduled.remove(sessionId)
            }
        }
    }

    /** Manual backfill is application-owned so leaving the chat cannot cancel a paid model call. */
    fun enqueueBackfill(sessionId: String) {
        if (!scheduledBackfills.add(sessionId)) return
        _backfillProgress.update { progress ->
            progress + (sessionId to MemoryBackfillProgress(
                phase = MemoryBackfillPhase.PREPARING,
                totalSourceTurns = 0,
                completedSourceTurns = 0,
                completedEpisodes = 0
            ))
        }
        scope.launch {
            try {
                runnerMutex.withLock { runBackfill(sessionId) }
            } finally {
                _backfillProgress.update { it - sessionId }
                scheduledBackfills.remove(sessionId)
            }
        }
    }

    private suspend fun runBackfill(sessionId: String) {
        val session = chatRepository.getSession(sessionId)
        if (session == null) {
            return
        }
        if (!session.longTermMemoryEnabled) {
            return
        }
        val settings = settingsRepository.getAppSettings()
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            memoryService.setBackfillPreflightError(sessionId, "对话模型未配置或缺少鉴权")
            return
        }
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.startBackfill(sessionId, model) { progress ->
                    _backfillProgress.update { it + (sessionId to progress) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            memoryService.setBackfillPreflightError(
                sessionId,
                error.message ?: error::class.simpleName.orEmpty()
            )
        }
    }

    private suspend fun maintain(sessionId: String, manual: Boolean) {
        val delays = listOf(0L, 15_000L, 60_000L, 300_000L)
        for (attempt in delays.indices) {
            if (attempt > 0) delay(delays[attempt])
            if (!manual && currentSessionId != sessionId) return
            val session = chatRepository.getSession(sessionId) ?: return
            if (!session.longTermMemoryEnabled) return
            val settings = settingsRepository.getAppSettings()
            val model = modelResolver.resolveChatModel(session.modelId, settings)
            if (model == null || !model.hasConfiguredAuthentication(settings)) {
                memoryService.setMaintenancePreflightError(sessionId, "对话模型未配置或缺少鉴权")
                return
            }
            val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
            try {
                AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                    memoryService.recoverOrphanedMaintenance(sessionId)
                    memoryService.updateArchiveAfterReply(
                        sessionId,
                        model,
                        settings.defaultContextWindowSize.coerceAtLeast(1)
                    )
                    memoryService.maintainHeadAutomatically(sessionId, model)
                }
            } catch (error: BackgroundGenerationProtectionException) {
                memoryService.setWaitingForNetwork(sessionId, error.message ?: "等待网络")
            } catch (error: Throwable) {
                memoryService.setMaintenancePreflightError(
                    sessionId,
                    error.message ?: error::class.simpleName.orEmpty()
                )
            }
            val latest = chatRepository.getSession(sessionId) ?: return
            if (latest.memoryArchiveStatus !in setOf(MemoryUpdateStatus.ERROR, MemoryUpdateStatus.WAITING_FOR_NETWORK) &&
                latest.memoryHeadStatus !in setOf(MemoryUpdateStatus.ERROR, MemoryUpdateStatus.WAITING_FOR_NETWORK)
            ) return
        }
    }

    private fun isAllowedLocalHttp(baseUrl: String, allowCleartext: Boolean): Boolean {
        if (!allowCleartext) return false
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", true)) return false
        val host = uri.host.orEmpty().lowercase()
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local")
    }
}
