package com.example.chatbar.domain.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import com.example.chatbar.domain.service.BackgroundGenerationProtectionException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MemoryMaintenanceTrigger {
    SESSION_LOADED,
    USER_MESSAGE_PERSISTED,
    REPLY_PERSISTED,
    NETWORK_RESTORED,
    RETRY,
    MANUAL
}

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
    private val maintenanceMailbox = MemoryMaintenanceMailbox()
    private val scheduledBackfills = ConcurrentHashMap.newKeySet<String>()
    private val scheduledManualMaintenance =
        ConcurrentHashMap<String, MemoryManualMaintenanceKind>()
    private val maintenanceCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val backfillCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val manualMaintenanceCompletions =
        ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val sessionJobs = SessionScopedJobRegistry(scope)
    private val _backfillProgress =
        MutableStateFlow<Map<String, MemoryBackfillProgress>>(emptyMap())
    val backfillProgress: StateFlow<Map<String, MemoryBackfillProgress>> =
        _backfillProgress.asStateFlow()
    private val _manualMaintenance =
        MutableStateFlow<Map<String, MemoryManualMaintenanceKind>>(emptyMap())
    val manualMaintenance: StateFlow<Map<String, MemoryManualMaintenanceKind>> =
        _manualMaintenance.asStateFlow()
    private val scheduledSourceRepairs = ConcurrentHashMap.newKeySet<String>()
    private val _sourceRepairProgress =
        MutableStateFlow<Map<String, MemorySourceRepairProgress>>(emptyMap())
    val sourceRepairProgress: StateFlow<Map<String, MemorySourceRepairProgress>> =
        _sourceRepairProgress.asStateFlow()
    private val currentSessionId = AtomicReference<String?>(null)

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentSessionId.get()?.let { enqueue(it, MemoryMaintenanceTrigger.NETWORK_RESTORED) }
                }
            })
        }
    }

    fun activateSession(sessionId: String) {
        currentSessionId.set(sessionId)
        enqueue(sessionId, MemoryMaintenanceTrigger.SESSION_LOADED)
    }

    /** Explicit session deletion cancels application-owned memory work before data removal. */
    suspend fun cancelAndJoinForSession(sessionId: String) {
        currentSessionId.compareAndSet(sessionId, null)
        sessionJobs.cancelAndJoin(sessionId)
        maintenanceMailbox.cancel(sessionId)
        _backfillProgress.update { it - sessionId }
        _manualMaintenance.update { it - sessionId }
        _sourceRepairProgress.update { it - sessionId }
    }

    fun enqueue(
        sessionId: String,
        trigger: MemoryMaintenanceTrigger,
        manual: Boolean = trigger == MemoryMaintenanceTrigger.MANUAL
    ) {
        if (!manual && currentSessionId.get() != sessionId) return
        if (!maintenanceMailbox.request(sessionId)) return
        val completion = CompletableDeferred<Unit>()
        maintenanceCompletions[sessionId] = completion
        val cleanup = {
            maintenanceCompletions.remove(sessionId, completion)
            completion.complete(Unit)
            Unit
        }
        val job = sessionJobs.launch(sessionId) {
            runMaintenanceLoop(sessionId, manual)
        }
        if (job == null) {
            maintenanceMailbox.cancel(sessionId)
            cleanup()
        } else {
            job.invokeOnCompletion { cleanup() }
        }
    }

    private suspend fun runMaintenanceLoop(sessionId: String, manual: Boolean) {
        var retryAttempt = 0
        while (true) {
            if (!manual && currentSessionId.get() != sessionId) {
                maintenanceMailbox.cancel(sessionId)
                break
            }
            val requestedVersion = maintenanceMailbox.versionToProcess(sessionId)
            val result = runnerMutex.withLock {
                if (!manual && currentSessionId.get() != sessionId) {
                    MaintenancePassResult()
                } else {
                    maintainPass(sessionId)
                }
            }
            when {
                result.hasMoreArchiveBatches -> {
                    retryAttempt = 0
                    maintenanceMailbox.request(sessionId)
                    delay(NEXT_ARCHIVE_PASS_DELAY_MILLIS)
                }
                result.shouldRetry && retryAttempt < RETRY_DELAYS_MILLIS.size -> {
                    delay(RETRY_DELAYS_MILLIS[retryAttempt])
                    retryAttempt++
                    maintenanceMailbox.request(sessionId)
                }
                else -> retryAttempt = 0
            }
            if (!maintenanceMailbox.completePass(sessionId, requestedVersion)) break
        }
    }

    /** Manual backfill is application-owned so leaving the chat cannot cancel a paid model call. */
    fun enqueueBackfill(sessionId: String) {
        if (scheduledManualMaintenance.containsKey(sessionId)) return
        if (scheduledSourceRepairs.contains(sessionId)) return
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
        val cleanup = {
            _backfillProgress.update { it - sessionId }
            scheduledBackfills.remove(sessionId)
            backfillCompletions.remove(sessionId, completion)
            completion.complete(Unit)
            Unit
        }
        val job = sessionJobs.launch(sessionId) {
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
        }
        if (job == null) {
            cleanup()
        } else {
            job.invokeOnCompletion { cleanup() }
        }
    }

    fun enqueueCompressionDecisionContinuation(
        sessionId: String,
        continuation: MemoryCompressionDecisionContinuation
    ) {
        sessionJobs.launch(sessionId) {
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

    fun enqueueSourceRepair(sessionId: String): Boolean {
        if (scheduledBackfills.contains(sessionId)) return false
        if (scheduledManualMaintenance.containsKey(sessionId)) return false
        if (!scheduledSourceRepairs.add(sessionId)) return false
        _sourceRepairProgress.update {
            it + (sessionId to MemorySourceRepairProgress(
                phase = MemorySourceRepairPhase.WAITING_FOR_ARCHIVE,
                totalRoots = 0,
                completedRoots = 0
            ))
        }
        val job = sessionJobs.launch(sessionId) {
            runnerMutex.withLock { runSourceRepair(sessionId) }
        }
        if (job == null) {
            scheduledSourceRepairs.remove(sessionId)
            _sourceRepairProgress.update { it - sessionId }
            return false
        }
        job.invokeOnCompletion {
            scheduledSourceRepairs.remove(sessionId)
            _sourceRepairProgress.update { it - sessionId }
        }
        return true
    }

    private fun enqueueManualMaintenance(
        sessionId: String,
        kind: MemoryManualMaintenanceKind
    ): Boolean {
        if (scheduledBackfills.contains(sessionId)) return false
        if (scheduledSourceRepairs.contains(sessionId)) return false
        if (scheduledManualMaintenance.putIfAbsent(sessionId, kind) != null) return false
        val completion = CompletableDeferred<Unit>()
        manualMaintenanceCompletions[sessionId] = completion
        _manualMaintenance.update { it + (sessionId to kind) }
        val cleanup = {
            scheduledManualMaintenance.remove(sessionId, kind)
            _manualMaintenance.update { it - sessionId }
            manualMaintenanceCompletions.remove(sessionId, completion)
            completion.complete(Unit)
            Unit
        }
        val job = sessionJobs.launch(sessionId) {
            when (kind) {
                MemoryManualMaintenanceKind.FULL_REGENERATION ->
                    runFullRegeneration(sessionId)
                MemoryManualMaintenanceKind.HEAD_REGENERATION ->
                    runnerMutex.withLock { runHeadRegeneration(sessionId) }
            }
        }
        if (job == null) {
            cleanup()
            return false
        }
        job.invokeOnCompletion { cleanup() }
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
            memoryService.setBackfillPreflightError(sessionId, MEMORY_MODEL_CONFIGURATION_ERROR)
            return
        }
        memoryService.clearResolvedModelConfigurationErrors(sessionId)
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
                        MEMORY_MODEL_CONFIGURATION_ERROR
                    )
                    return@withLock
                }
                memoryService.clearResolvedModelConfigurationErrors(sessionId)
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
            memoryService.setHeadPreflightError(sessionId, MEMORY_MODEL_CONFIGURATION_ERROR)
            return
        }
        memoryService.clearResolvedModelConfigurationErrors(sessionId)
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.regenerateHeadFromArchive(sessionId, model)
            }
        } catch (error: BackgroundGenerationProtectionException) {
            memoryService.setHeadWaitingForNetwork(sessionId, error.message ?: "等待网络")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            memoryService.setHeadPreflightError(
                sessionId,
                error.message ?: error::class.simpleName.orEmpty()
            )
        }
    }

    private suspend fun runSourceRepair(sessionId: String) {
        val session = chatRepository.getSession(sessionId) ?: return
        if (!session.longTermMemoryEnabled) return
        val settings = settingsRepository.getAppSettings()
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            memoryService.setMaintenancePreflightError(
                sessionId,
                MEMORY_MODEL_CONFIGURATION_ERROR
            )
            return
        }
        memoryService.clearResolvedModelConfigurationErrors(sessionId)
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.startSourceRepair(sessionId, model) { progress ->
                    _sourceRepairProgress.update { it + (sessionId to progress) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            memoryService.setMaintenancePreflightError(
                sessionId,
                error.message ?: error::class.simpleName.orEmpty()
            )
        }
    }

    private suspend fun maintainPass(sessionId: String): MaintenancePassResult {
        val currentState = memoryService.currentState(sessionId)
        if (currentState?.fullRegenerationPending == true) {
            if (currentState.pendingDecision == null &&
                currentState.backfill.status == MemoryBackfillStatus.IDLE
            ) {
                enqueueBackfill(sessionId)
            }
            return MaintenancePassResult()
        }
        val session = chatRepository.getSession(sessionId) ?: return MaintenancePassResult()
        if (!session.longTermMemoryEnabled) return MaintenancePassResult()
        val settings = settingsRepository.getAppSettings()
        val model = modelResolver.resolveChatModel(session.modelId, settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            memoryService.setMaintenancePreflightError(sessionId, MEMORY_MODEL_CONFIGURATION_ERROR)
            return MaintenancePassResult()
        }
        memoryService.clearResolvedModelConfigurationErrors(sessionId)
        val requireValidated = !isAllowedLocalHttp(model.baseUrl, settings.allowCleartextModelApi)
        var hasMoreArchiveBatches = false
        try {
            AiBackgroundWorkManager.run(sessionId, requireValidatedInternet = requireValidated) {
                memoryService.recoverOrphanedMaintenance(sessionId)
                hasMoreArchiveBatches = memoryService.updateArchiveAfterReply(
                    sessionId = sessionId,
                    modelConfig = model,
                    contextWindowSize = settings.defaultContextWindowSize.coerceAtLeast(0),
                    maxEpisodeBatches = MAX_ARCHIVE_EPISODES_PER_PASS
                ).hasMoreReadyBatches
                memoryService.maintainHeadAutomatically(sessionId, model)
            }
        } catch (error: BackgroundGenerationProtectionException) {
            memoryService.setWaitingForNetwork(sessionId, error.message ?: "等待网络")
        } catch (error: CancellationException) {
            throw error
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

internal class SessionScopedJobRegistry(
    private val scope: CoroutineScope
) {
    private val lock = Any()
    private val deletingSessionIds = mutableSetOf<String>()
    private val jobsBySessionId = mutableMapOf<String, MutableSet<Job>>()

    fun launch(
        sessionId: String,
        block: suspend CoroutineScope.() -> Unit
    ): Job? {
        val job = synchronized(lock) {
            if (sessionId in deletingSessionIds) return null
            scope.launch(start = CoroutineStart.LAZY, block = block).also { job ->
                jobsBySessionId.getOrPut(sessionId) { mutableSetOf() }.add(job)
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                jobsBySessionId[sessionId]?.let { jobs ->
                    jobs.remove(job)
                    if (jobs.isEmpty()) jobsBySessionId.remove(sessionId)
                }
            }
        }
        job.start()
        return job
    }

    suspend fun cancelAndJoin(sessionId: String) {
        val jobs = synchronized(lock) {
            deletingSessionIds.add(sessionId)
            jobsBySessionId[sessionId]?.toList().orEmpty()
        }
        jobs.forEach(Job::cancel)
        jobs.forEach { it.join() }
    }
}
