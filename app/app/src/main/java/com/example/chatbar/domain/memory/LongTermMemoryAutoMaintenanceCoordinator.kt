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
import kotlinx.coroutines.CompletableDeferred
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

enum class MemoryManualMaintenanceKind { FULL_REGENERATION, HEAD_REGENERATION }

/** Application-owned runner. View destruction never cancels paid maintenance calls. */
class LongTermMemoryAutoMaintenanceCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val modelResolver: EffectiveModelResolver,
    private val memoryService: LongTermMemoryService
) {
    private data class MaintenancePassResult(
        val hasMoreArchiveBatches: Boolean = false,
        val shouldRetry: Boolean = false
    )

    companion object {
        private const val MAX_ARCHIVE_EPISODES_PER_PASS = 1
        private const val NEXT_ARCHIVE_PASS_DELAY_MILLIS = 100L
        private val RETRY_DELAYS_MILLIS = listOf(15_000L, 60_000L, 300_000L)
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val runnerMutex = Mutex()
    private val scheduled = ConcurrentHashMap.newKeySet<String>()
    private val scheduledBackfills = ConcurrentHashMap.newKeySet<String>()
    private val scheduledManualMaintenance =
        ConcurrentHashMap<String, MemoryManualMaintenanceKind>()
    private val maintenanceCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val backfillCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val manualMaintenanceCompletions =
        ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val _backfillProgress =
        MutableStateFlow<Map<String, MemoryBackfillProgress>>(emptyMap())
    val backfillProgress: StateFlow<Map<String, MemoryBackfillProgress>> =
        _backfillProgress.asStateFlow()
    private val _manualMaintenance =
        MutableStateFlow<Map<String, MemoryManualMaintenanceKind>>(emptyMap())
    val manualMaintenance: StateFlow<Map<String, MemoryManualMaintenanceKind>> =
        _manualMaintenance.asStateFlow()
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
        if (scheduledManualMaintenance.containsKey(sessionId)) return
        if (!scheduled.add(sessionId)) return
        val completion = CompletableDeferred<Unit>()
        maintenanceCompletions[sessionId] = completion
        scope.launch {
            try {
                runMaintenanceLoop(sessionId, manual)
            } finally {
                scheduled.remove(sessionId)
                maintenanceCompletions.remove(sessionId, completion)
                completion.complete(Unit)
            }
        }
    }

    private suspend fun runMaintenanceLoop(sessionId: String, manual: Boolean) {
        var retryAttempt = 0
        while (true) {
            if (!manual && currentSessionId != sessionId) break
            if (scheduledManualMaintenance.containsKey(sessionId)) break
            val result = runnerMutex.withLock {
                if (!manual && currentSessionId != sessionId) {
                    MaintenancePassResult()
                } else {
                    maintainPass(sessionId)
                }
            }
            when {
                result.hasMoreArchiveBatches -> {
                    retryAttempt = 0
                    delay(NEXT_ARCHIVE_PASS_DELAY_MILLIS)
                }
                result.shouldRetry && retryAttempt < RETRY_DELAYS_MILLIS.size -> {
                    delay(RETRY_DELAYS_MILLIS[retryAttempt])
                    retryAttempt++
                }
                else -> break
            }
        }
    }

    /** Manual backfill is application-owned so leaving the chat cannot cancel a paid model call. */
    fun enqueueBackfill(sessionId: String) {
        if (scheduledManualMaintenance.containsKey(sessionId)) return
        if (!scheduledBackfills.add(sessionId)) return
        val completion = CompletableDeferred<Unit>()
        backfillCompletions[sessionId] = completion
        _backfillProgress.update { progress ->
            progress + (sessionId to MemoryBackfillProgress(
                phase = MemoryBackfillPhase.WAITING_FOR_ARCHIVE,
                totalSourceTurns = 0,
                completedSourceTurns = 0,
                completedEpisodes = 0
            ))
        }
        scope.launch {
            try {
                runnerMutex.withLock {
                    _backfillProgress.update { progress ->
                        progress + (sessionId to MemoryBackfillProgress(
                            phase = MemoryBackfillPhase.PREPARING,
                            totalSourceTurns = 0,
                            completedSourceTurns = 0,
                            completedEpisodes = 0
                        ))
                    }
                    runBackfill(sessionId)
                }
            } finally {
                _backfillProgress.update { it - sessionId }
                scheduledBackfills.remove(sessionId)
                backfillCompletions.remove(sessionId, completion)
                completion.complete(Unit)
            }
        }
    }

    fun enqueueCompressionDecisionContinuation(
        sessionId: String,
        continuation: MemoryCompressionDecisionContinuation
    ) {
        scope.launch {
            when (continuation) {
                MemoryCompressionDecisionContinuation.ARCHIVE -> {
                    manualMaintenanceCompletions[sessionId]?.await()
                    maintenanceCompletions[sessionId]?.await()
                    runMaintenanceLoop(sessionId, manual = true)
                }
                MemoryCompressionDecisionContinuation.BACKFILL -> {
                    manualMaintenanceCompletions[sessionId]?.await()
                    backfillCompletions[sessionId]?.await()
                    enqueueBackfill(sessionId)
                }
            }
        }
    }

    fun enqueueFullRegeneration(sessionId: String): Boolean =
        enqueueManualMaintenance(sessionId, MemoryManualMaintenanceKind.FULL_REGENERATION)

    fun enqueueHeadRegeneration(sessionId: String): Boolean =
        enqueueManualMaintenance(sessionId, MemoryManualMaintenanceKind.HEAD_REGENERATION)

    private fun enqueueManualMaintenance(
        sessionId: String,
        kind: MemoryManualMaintenanceKind
    ): Boolean {
        if (scheduledBackfills.contains(sessionId)) return false
        if (scheduledManualMaintenance.putIfAbsent(sessionId, kind) != null) return false
        val completion = CompletableDeferred<Unit>()
        manualMaintenanceCompletions[sessionId] = completion
        _manualMaintenance.update { it + (sessionId to kind) }
        scope.launch {
            try {
                when (kind) {
                    MemoryManualMaintenanceKind.FULL_REGENERATION ->
                        runFullRegeneration(sessionId)
                    MemoryManualMaintenanceKind.HEAD_REGENERATION ->
                        runnerMutex.withLock { runHeadRegeneration(sessionId) }
                }
            } finally {
                scheduledManualMaintenance.remove(sessionId, kind)
                _manualMaintenance.update { it - sessionId }
                manualMaintenanceCompletions.remove(sessionId, completion)
                completion.complete(Unit)
            }
        }
        return true
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
                memoryService.markFullRegenerationCompleteAfterBackfill(sessionId)
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

    private suspend fun runFullRegeneration(sessionId: String) {
        _backfillProgress.update { progress ->
            progress + (sessionId to MemoryBackfillProgress(
                phase = MemoryBackfillPhase.WAITING_FOR_ARCHIVE,
                totalSourceTurns = 0,
                completedSourceTurns = 0,
                completedEpisodes = 0
            ))
        }
        var resetCompleted = false
        try {
            runnerMutex.withLock {
                _backfillProgress.update { progress ->
                    progress + (sessionId to MemoryBackfillProgress(
                        phase = MemoryBackfillPhase.PREPARING,
                        totalSourceTurns = 0,
                        completedSourceTurns = 0,
                        completedEpisodes = 0
                    ))
                }
                val session = chatRepository.getSession(sessionId) ?: return@withLock
                if (!session.longTermMemoryEnabled) return@withLock
                val settings = settingsRepository.getAppSettings()
                val model = modelResolver.resolveChatModel(session.modelId, settings)
                if (model == null || !model.hasConfiguredAuthentication(settings)) {
                    memoryService.setMaintenancePreflightError(
                        sessionId,
                        "对话模型未配置或缺少鉴权"
                    )
                    return@withLock
                }
                val requireValidated = !isAllowedLocalHttp(
                    model.baseUrl,
                    settings.allowCleartextModelApi
                )
                AiBackgroundWorkManager.run(
                    sessionId,
                    requireValidatedInternet = requireValidated
                ) {
                    memoryService.resetForFullRegeneration(sessionId)
                    resetCompleted = true
                    memoryService.startBackfill(sessionId, model) { progress ->
                        _backfillProgress.update { it + (sessionId to progress) }
                    }
                    memoryService.markFullRegenerationCompleteAfterBackfill(sessionId)
                }
            }
        } catch (error: BackgroundGenerationProtectionException) {
            if (resetCompleted) {
                memoryService.setBackfillPreflightError(sessionId, error.message ?: "等待网络")
            } else {
                memoryService.setWaitingForNetwork(sessionId, error.message ?: "等待网络")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error::class.simpleName.orEmpty()
            if (resetCompleted) {
                memoryService.setBackfillPreflightError(sessionId, message)
            } else {
                memoryService.setMaintenancePreflightError(sessionId, message)
            }
        } finally {
            _backfillProgress.update { it - sessionId }
        }
    }

    private suspend fun runHeadRegeneration(sessionId: String) {
        val session = chatRepository.getSession(sessionId) ?: return
        if (!session.longTermMemoryEnabled) return
        val settings = settingsRepository.getAppSettings()
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            memoryService.setHeadPreflightError(sessionId, "对话模型未配置或缺少鉴权")
            return
        }
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.regenerateHeadFromArchive(sessionId, model)
            }
        } catch (error: BackgroundGenerationProtectionException) {
            memoryService.setHeadWaitingForNetwork(sessionId, error.message ?: "等待网络")
        } catch (error: Throwable) {
            memoryService.setHeadPreflightError(
                sessionId,
                error.message ?: error::class.simpleName.orEmpty()
            )
        }
    }

    private suspend fun maintainPass(sessionId: String): MaintenancePassResult {
        val currentState = memoryService.currentState(sessionId)
        if (currentState?.fullRegenerationPending == true) {
            if (currentState.pendingDecision == null) enqueueBackfill(sessionId)
            return MaintenancePassResult()
        }
        val session = chatRepository.getSession(sessionId) ?: return MaintenancePassResult()
        if (!session.longTermMemoryEnabled) return MaintenancePassResult()
        val settings = settingsRepository.getAppSettings()
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            memoryService.setMaintenancePreflightError(sessionId, "对话模型未配置或缺少鉴权")
            return MaintenancePassResult()
        }
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        var hasMoreArchiveBatches = false
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.recoverOrphanedMaintenance(sessionId)
                hasMoreArchiveBatches = memoryService.updateArchiveAfterReply(
                    sessionId = sessionId,
                    modelConfig = model,
                    contextWindowSize = settings.defaultContextWindowSize.coerceAtLeast(1),
                    maxEpisodeBatches = MAX_ARCHIVE_EPISODES_PER_PASS
                ).hasMoreReadyBatches
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
        val latest = chatRepository.getSession(sessionId) ?: return MaintenancePassResult()
        val shouldRetry =
            latest.memoryArchiveStatus in setOf(MemoryUpdateStatus.ERROR, MemoryUpdateStatus.WAITING_FOR_NETWORK) ||
                latest.memoryHeadStatus in setOf(MemoryUpdateStatus.ERROR, MemoryUpdateStatus.WAITING_FOR_NETWORK)
        return MaintenancePassResult(
            hasMoreArchiveBatches = hasMoreArchiveBatches && !shouldRetry,
            shouldRetry = shouldRetry
        )
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
