package com.example.chatbar.domain.moment

import android.content.Context
import android.util.Log
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MomentRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MomentScheduler(
    context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val momentRepository: MomentRepository,
    private val modelResolver: EffectiveModelResolver,
    private val generationService: MomentGenerationService
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    fun kick(reason: String = "manual") {
        scope.launch {
            runCatching { runOnce(reason) }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        Log.w(TAG, "Moment scheduler failed: $reason", error)
                    }
                }
        }
    }

    suspend fun runOnce(reason: String = "manual", now: Long = System.currentTimeMillis()) = mutex.withLock {
        settingsRepository.initialize()
        characterRepository.initialize()
        chatRepository.initialize()
        momentRepository.initialize()
        MomentAlarmScheduler.cancel(appContext)
        val settings = settingsRepository.getAppSettings()
        if (!settings.momentsEnabled) {
            return@withLock
        }

        ensureSchedules(now, settings)
        processDueTasks(now)
        ensureSchedules(System.currentTimeMillis(), settings)
        Log.d(TAG, "Moment scheduler complete: $reason")
    }

    suspend fun ensureFutureSchedules(reason: String = "preview", now: Long = System.currentTimeMillis()) = mutex.withLock {
        settingsRepository.initialize()
        characterRepository.initialize()
        chatRepository.initialize()
        momentRepository.initialize()
        MomentAlarmScheduler.cancel(appContext)
        val settings = settingsRepository.getAppSettings()
        if (!settings.momentsEnabled) return@withLock

        ensureSchedules(now, settings)
        Log.d(TAG, "Moment future schedules ensured: $reason")
    }

    private suspend fun ensureSchedules(now: Long, settings: AppSettings) {
        val horizon = now + MomentPolicy.SCHEDULE_HORIZON_MS
        val cards = characterRepository.getAll()
            .filter { it.momentsEnabled }
            .associateBy { it.id }
        if (cards.isEmpty()) return

        val sessionsByCard = activeSessionsByCard(now)
        val allPosts = momentRepository.getAllPosts()
        val allTasks = momentRepository.getAllTasks()
        val scheduledTimesByCard = cards.keys.associateWith { cardId ->
            MomentPolicy.scheduledTimesForLimit(
                posts = allPosts.filter { it.characterCardId == cardId },
                tasks = allTasks.filter { it.characterCardId == cardId }
            ).toMutableList()
        }.toMutableMap()
        val globalScheduledTimes = MomentPolicy.scheduledTimesForLimit(allPosts, allTasks).toMutableList()

        cards.values.forEach { card ->
            val session = sessionsByCard[card.id] ?: return@forEach
            val cardTimes = scheduledTimesByCard.getValue(card.id)
            var cursor = (cardTimes.maxOrNull() ?: now).coerceAtLeast(now)
            var guard = 0
            while (cursor < horizon && guard++ < 128) {
                val scheduledAt = cursor + MomentPolicy.nextDelayMs(
                    seed = card.id,
                    cursorMs = cursor,
                    minDelayHours = settings.momentsMinDelayHours,
                    maxDelayHours = settings.momentsMaxDelayHours
                )
                cursor = scheduledAt
                if (scheduledAt > horizon) break
                val cardDayCount = MomentPolicy.countForDay(cardTimes, scheduledAt)
                val globalDayCount = MomentPolicy.countForDay(globalScheduledTimes, scheduledAt)
                if (
                    cardDayCount >= MomentPolicy.MAX_CARD_POSTS_PER_DAY ||
                    globalDayCount >= MomentPolicy.MAX_GLOBAL_POSTS_PER_DAY
                ) {
                    continue
                }
                val task = MomentTask.create(
                    characterCardId = card.id,
                    sessionId = session.id,
                    scheduledAt = scheduledAt,
                    now = now
                )
                momentRepository.saveTask(task)
                cardTimes += scheduledAt
                globalScheduledTimes += scheduledAt
            }
        }
    }

    private suspend fun processDueTasks(now: Long) {
        val tasks = momentRepository.pendingTasksDue(now)
        if (tasks.isEmpty()) return
        for (task in tasks) {
            processTask(task, now)
        }
    }

    private suspend fun processTask(task: MomentTask, now: Long) {
        var generationCheckpoint = MomentGenerationCheckpoint()
        val settings = settingsRepository.getAppSettings()
        if (!settings.momentsEnabled) return
        val card = characterRepository.getById(task.characterCardId)
        if (card == null || !card.momentsEnabled) {
            momentRepository.updateTask(task.copy(status = MomentTaskStatus.SKIPPED, failureReason = "角色未开启朋友圈或已删除"))
            return
        }
        val session = chatRepository.getSession(task.sessionId) ?: latestActiveSession(card, now)
        if (session == null) {
            momentRepository.updateTask(task.copy(status = MomentTaskStatus.SKIPPED, failureReason = "会话已删除或 48 小时内无交流"))
            return
        }
        val model = resolveModel(settings)
        if (model == null || !model.hasConfiguredAuthentication(settings)) {
            saveFailurePlaceholder(task, card, session, "未配置可用默认对话模型/API Key")
            return
        }
        val imageModel = resolveImageModel(settings)

        val running = task.copy(status = MomentTaskStatus.RUNNING, sessionId = session.id, failureReason = null)
        momentRepository.updateTask(running)
        runCatching {
            val messages = chatRepository.getMessages(session.id)
            val latestPost = momentRepository.latestPostForCard(card.id)
            AiBackgroundWorkManager.run("moments_${card.id}") {
                generationService.generate(
                    card = card,
                    session = session,
                    messages = messages,
                    latestPost = latestPost,
                    model = model,
                    imageModel = imageModel,
                    scheduledAt = task.scheduledAt,
                    finalPromptRequirement = settings.imagePromptToolPreference,
                    allowCleartextModelApi = settings.allowCleartextModelApi,
                    onCheckpoint = { generationCheckpoint = it }
                )
            }
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is MomentGenerationResult.Posted -> {
                        momentRepository.savePost(result.post)
                        momentRepository.updateTask(
                            running.copy(status = MomentTaskStatus.COMPLETED, postId = result.post.id, failureReason = null)
                        )
                    }

                    is MomentGenerationResult.Skipped -> {
                        momentRepository.updateTask(
                            running.copy(status = MomentTaskStatus.SKIPPED, failureReason = result.reason)
                        )
                    }
                }
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                saveFailurePlaceholder(
                    running,
                    card,
                    session,
                    error.message ?: error.javaClass.simpleName,
                    generationService.encodeCheckpoint(generationCheckpoint)
                )
            }
        )
    }

    private suspend fun saveFailurePlaceholder(
        task: MomentTask,
        card: CharacterCard,
        session: ChatSession,
        reason: String,
        generationCheckpoint: String = ""
    ) {
        val sender = card.characters.firstOrNull()
        val placeholder = MomentPost.createPlaceholder(
            characterCardId = card.id,
            sessionId = session.id,
            senderCharacterId = sender?.id,
            senderName = sender?.name?.takeIf(String::isNotBlank) ?: card.name.ifBlank { "朋友圈" },
            senderAvatar = sender?.appearanceImage?.takeIf(String::isNotBlank)
                ?: card.avatar?.takeIf(String::isNotBlank),
            failureReason = reason,
            generationCheckpoint = generationCheckpoint,
            scheduledAt = task.scheduledAt
        )
        momentRepository.savePost(placeholder)
        momentRepository.updateTask(
            task.copy(
                status = MomentTaskStatus.FAILED,
                postId = placeholder.id,
                failureReason = reason
            )
        )
    }

    private suspend fun activeSessionsByCard(now: Long): Map<String, ChatSession> =
        chatRepository.getAllSessions()
            .asSequence()
            .filter { MomentPolicy.isRecentlyActive(it.lastMessageTime, now) }
            .groupBy { it.characterCardId }
            .mapValues { (_, sessions) -> sessions.maxBy { it.lastMessageTime ?: it.createdAt } }

    private suspend fun latestActiveSession(card: CharacterCard, now: Long): ChatSession? =
        chatRepository.getAllSessions()
            .filter { it.characterCardId == card.id && MomentPolicy.isRecentlyActive(it.lastMessageTime, now) }
            .maxByOrNull { it.lastMessageTime ?: it.createdAt }

    private suspend fun resolveModel(settings: com.example.chatbar.data.local.entity.AppSettings): ModelConfig? =
        modelResolver.defaultChatModel(settings)

    private suspend fun resolveImageModel(settings: com.example.chatbar.data.local.entity.AppSettings): ModelConfig? =
        modelResolver.defaultImageModel(settings)

    private companion object {
        const val TAG = "MomentScheduler"
    }
}
