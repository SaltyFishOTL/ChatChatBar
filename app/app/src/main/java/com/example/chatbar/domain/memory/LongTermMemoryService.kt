package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION
import com.example.chatbar.data.local.entity.MAX_MEMORY_LIMIT_CHARS
import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryCommit
import com.example.chatbar.data.local.entity.MemoryCommitOperation
import com.example.chatbar.data.local.entity.MemoryCompressionEvent
import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.MemoryCompressionTransaction
import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryDecisionTier
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.PendingMemoryDecision
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.MemoryRepository
import com.example.chatbar.data.repository.SettingsRepository
import com.example.chatbar.domain.chat.ContextWindowManager
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MemoryPromptView(
    val archive: String,
    val headAndTimeline: String,
    val fullText: String,
    val archiveThroughT: Long?,
    val latestStableT: Long,
    val effectiveBudgetChars: Int,
    val usedArchiveChars: Int,
    /** 当前长期记忆时间线的派生显示T；sourceTurnId仍是唯一持久身份。 */
    val displayTBySourceTurnId: Map<String, Long> = emptyMap(),
    /** 仅供普通上下文模块临时保留未提交Episode的原文；不进入长期记忆块。 */
    val pendingSourceTurnIds: Set<String> = emptySet(),
    val headPresent: Boolean = false,
    val headInitializationPending: Boolean = false,
    val headBackfillRequired: Boolean = false,
    val warnings: List<MemoryIntegrityWarning> = emptyList()
)

data class MemoryBackfillEstimate(
    val missingSourceTurns: Int,
    val episodeCallsMin: Int,
    val episodeCallsMax: Int,
    val compressionCallsMin: Int = 0,
    val compressionCallsMax: Int = 0,
    val sourceCharacters: Int = 0
)

private data class LoadedMemory(
    val session: ChatSession,
    val messages: List<ChatMessage>,
    val state: MemorySessionState,
    val nodes: MutableMap<String, MemoryNode>
)

private data class MemoryNodeRegenerationRequest(
    val plan: MemoryNodeRegenerationPlan,
    val renderedEvidence: String,
    val evidenceHash: String
)

private sealed interface MaintenanceResult {
    data class Ready(
        val state: MemorySessionState,
        val nodes: MutableMap<String, MemoryNode>
    ) : MaintenanceResult

    data class DecisionRequired(val state: MemorySessionState) : MaintenanceResult
}

private enum class CompressionAttempt {
    SUCCESS,
    NOT_COMPRESSIBLE
}

private enum class HeadUpdateRequest {
    AFTER_REPLY,
    BEFORE_PROMPT,
    BACKFILL
}

class LongTermMemoryService(
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val settingsRepository: SettingsRepository,
    streamingChatService: StreamingChatService,
    private val contextWindowManager: ContextWindowManager
) {
    private val ai = MemoryAiGateway(streamingChatService)
    private val revisions = MemoryRevisionService(memoryRepository)
    private val stateMutexes = ConcurrentHashMap<String, Mutex>()
    private val archiveMutexes = ConcurrentHashMap<String, Mutex>()
    private val headMutexes = ConcurrentHashMap<String, Mutex>()
    /** 区分本进程活跃补录与进程重启后遗留的RUNNING状态。 */
    private val activeBackfillSessionIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun ensureMigrated(sessionId: String): MemoryPromptView = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        compileAndCacheLocked(loaded)
    }

    suspend fun promptView(sessionId: String): MemoryPromptView = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        compileAndCacheLocked(loaded)
    }

    /**
     * Re-reads persisted chat, settings, timeline, gaps, source hashes, and active nodes.
     * Explicit refresh also discovers stable uncovered turns that became archived after
     * context-window changes. It never calls AI or mutates existing summaries.
     */
    suspend fun refreshForCurrentConditions(sessionId: String): MemoryPromptView = stateLock(sessionId) {
        val loaded = loadLocked(sessionId, discoverUntrackedArchived = true)
        compileAndCacheLocked(loaded)
    }

    suspend fun currentState(sessionId: String): MemorySessionState? = stateLock(sessionId) {
        loadLocked(sessionId).state
    }

    suspend fun activeNodes(sessionId: String): List<MemoryNode> = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        MemoryTimelinePolicy.sortNodes(
            (loaded.state.activeNodeIds + loaded.state.legacyReferenceNodeIds)
                .mapNotNull(loaded.nodes::get),
            loaded.state.timeline
        )
    }

    suspend fun reachableNodes(sessionId: String): List<MemoryNode> = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        reachableNodes(loaded.state, loaded.nodes)
    }

    suspend fun history(
        sessionId: String,
        tier: MemoryTier,
        limit: Int = Int.MAX_VALUE
    ): List<MemoryTierRevision> = memoryRepository.history(sessionId, tier, limit)

    suspend fun revisionNodes(revisionId: String): List<MemoryNode> =
        memoryRepository.getNodes(memoryRepository.materializeRevision(revisionId))

    suspend fun updateArchiveAfterReply(
        sessionId: String,
        modelConfig: ModelConfig,
        contextWindowSize: Int? = null
    ) = archiveLock(sessionId) {
        val initial = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            if (!loaded.session.longTermMemoryEnabled ||
                loaded.state.backfill.status == MemoryBackfillStatus.RUNNING ||
                loaded.state.pendingDecision != null
            ) {
                return@stateLock null
            }
            setArchiveStatusLocked(loaded.session, MemoryUpdateStatus.UPDATING, null)
            loaded
        } ?: return@archiveLock

        try {
            var state = initial.state
            var nodes = initial.nodes
            val messages = initial.messages
            val session = initial.session
            val appSettings = settingsRepository.getAppSettings()
            val maxTurns = appSettings.episodeMaxSourceTurns.coerceIn(1, 6)
            val archivedSourceIds = completeArchivedSourceTurnIds(
                messages = messages,
                windowSize = contextWindowSize
                    ?: appSettings.defaultContextWindowSize.coerceAtLeast(1)
            ).filter { sourceId ->
                val watermark = state.recordingStartsAfterSourceOrder
                watermark == null || sourceOrder(sourceId, messages, session) > watermark
            }
            val covered = state.activeNodeIds.mapNotNull(nodes::get).flatMapTo(mutableSetOf()) {
                it.sourceTurnIds
            }
            val gapIds = state.gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
            val newPending = archivedSourceIds.filter { id ->
                id !in covered && id !in gapIds && id !in state.pendingSourceTurnIds
            }
            if (newPending.isNotEmpty()) {
                state = state.copy(
                    pendingSourceTurnIds = sortSourceIds(
                        state.pendingSourceTurnIds + newPending,
                        messages,
                        session
                    ),
                    revision = state.revision + 1,
                    updatedAt = System.currentTimeMillis()
                )
                persistStateIfCurrent(state, expectedPreviousRevision = initial.state.revision)
            }

            while (true) {
                val latest = stateLock(sessionId) { loadLocked(sessionId) }
                state = latest.state
                nodes = latest.nodes
                if (!latest.session.longTermMemoryEnabled || state.pendingDecision != null) break
                val batch = nextEpisodeBatch(state, messages, maxTurns, forcePartial = false)
                if (batch.isEmpty()) break

                val sourceRefs = sourceRefs(batch, messages, latest.session)
                val baseSourceHash = MemoryHashes.sourceRefs(sourceRefs)
                val summaryMaxChars = MemoryEpisodeSummaryPolicy.maxChars(batch.size)
                val response = ai.episode(
                    model = modelConfig,
                    renderedTurns = renderSourceTurns(batch, messages, state.timeline, latest.session),
                    summaryMaxChars = summaryMaxChars
                ) { output -> validateEpisodeResponse(output, batch.size) }
                val currentSourceRefs = sourceRefs(batch, chatRepository.getMessages(sessionId), latest.session)
                check(MemoryHashes.sourceRefs(currentSourceRefs) == baseSourceHash) {
                    "Episode来源已变化，丢弃过期结果"
                }
                val episode = createEpisodeNode(
                    sessionId = sessionId,
                    sourceRefs = sourceRefs,
                    response = response
                )
                val maintenance = maintainUntilFits(
                    sessionId = sessionId,
                    initialState = state,
                    initialNodes = nodes,
                    stagedEpisode = episode,
                    model = modelConfig,
                    limitChars = latest.session.memoryLimitChars
                )
                if (maintenance is MaintenanceResult.DecisionRequired) break
                maintenance as MaintenanceResult.Ready
                state = maintenance.state
                nodes = maintenance.nodes

                val refreshed = stateLock(sessionId) { loadLocked(sessionId) }
                check(refreshed.state.revision == state.revision) { "Episode提交前版本已变化" }
                val finalNodes = refreshed.nodes.toMutableMap().apply { put(episode.id, episode) }
                val finalState = revisions.appendPure(refreshed.state, MemoryTier.EPISODE, episode.id)
                    .copy(
                        pendingSourceTurnIds = refreshed.state.pendingSourceTurnIds.filterNot { it in batch },
                        revision = refreshed.state.revision + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                check(!MemoryBudgetPolicy.isOverLimit(finalState, finalNodes, refreshed.session.memoryLimitChars)) {
                    "Episode提交后仍超过自动预算"
                }
                requireValidNode(episode, finalNodes, finalState)
                memoryRepository.saveNodes(listOf(episode))
                memoryRepository.saveState(finalState)
                state = finalState
                nodes = finalNodes
            }

            stateLock(sessionId) {
                val loaded = loadLocked(sessionId)
                val status = if (loaded.state.pendingDecision != null) {
                    MemoryUpdateStatus.LIMIT_DECISION_REQUIRED
                } else {
                    MemoryUpdateStatus.IDLE
                }
                compileAndCacheLocked(loaded, archiveStatus = status, archiveError = null)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stateLock(sessionId) {
                val session = chatRepository.getSession(sessionId) ?: return@stateLock
                setArchiveStatusLocked(
                    session,
                    if (session.longTermMemoryEnabled) MemoryUpdateStatus.ERROR else MemoryUpdateStatus.IDLE,
                    if (session.longTermMemoryEnabled) error.message ?: error::class.simpleName else null
                )
            }
        }
    }

    suspend fun updateHeadAfterReply(sessionId: String, modelConfig: ModelConfig) =
        updateHead(sessionId, modelConfig, HeadUpdateRequest.AFTER_REPLY)

    suspend fun prepareHeadBeforePrompt(sessionId: String, modelConfig: ModelConfig) =
        updateHead(sessionId, modelConfig, HeadUpdateRequest.BEFORE_PROMPT)

    private suspend fun backfillHead(sessionId: String, modelConfig: ModelConfig) =
        updateHead(sessionId, modelConfig, HeadUpdateRequest.BACKFILL)

    private suspend fun updateHead(
        sessionId: String,
        modelConfig: ModelConfig,
        request: HeadUpdateRequest
    ) = headLock(sessionId) {
        val base = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            if (!loaded.session.longTermMemoryEnabled ||
                (loaded.state.backfill.status == MemoryBackfillStatus.RUNNING &&
                    request != HeadUpdateRequest.BACKFILL)
            ) {
                return@stateLock null
            }
            setHeadStatusLocked(loaded.session, MemoryUpdateStatus.UPDATING, null)
            loaded
        } ?: return@headLock

        try {
            val stableIds = stableSourceTurnIds(base.messages).filter { sourceId ->
                val watermark = base.state.recordingStartsAfterSourceOrder
                watermark == null || sourceOrder(sourceId, base.messages, base.session) > watermark
            }
            val headHasContent = base.state.head.render().isNotBlank()
            val hasHistoricalMemory = base.state.activeNodeIds.isNotEmpty() ||
                base.state.legacyReferenceNodeIds.isNotEmpty() ||
                base.state.gaps.isNotEmpty()
            val candidatePlan = when (request) {
                HeadUpdateRequest.AFTER_REPLY -> {
                    if (!headHasContent) null else MemoryHeadUpdatePolicy.update(
                        base.state.head.throughSourceTurnId,
                        stableIds
                    )
                }
                HeadUpdateRequest.BEFORE_PROMPT -> {
                    if (headHasContent) {
                        MemoryHeadUpdatePolicy.update(base.state.head.throughSourceTurnId, stableIds)
                    } else if (!MemoryHeadUpdatePolicy.requiresBackfill(
                            hasHeadContent = false,
                            throughSourceTurnId = null,
                            stableSourceTurnIds = stableIds,
                            hasHistoricalMemory = hasHistoricalMemory
                        )
                    ) {
                        MemoryHeadUpdatePolicy.initialize(stableIds)
                    } else {
                        null
                    }
                }
                HeadUpdateRequest.BACKFILL -> MemoryHeadUpdatePolicy.backfill(stableIds)
            }
            val plan = candidatePlan?.takeUnless { selected ->
                selected.mode == MemoryHeadUpdateMode.UPDATE &&
                    headPathCrossesGap(base, selected.targetSourceTurnId)
            }
            if (plan == null) {
                stateLock(sessionId) {
                    val session = chatRepository.getSession(sessionId) ?: return@stateLock
                    setHeadStatusLocked(session, MemoryUpdateStatus.IDLE, null)
                }
                return@headLock
            }
            val targetId = plan.targetSourceTurnId
            if (MemoryHeadUpdatePolicy.isUpToDate(base.state.head.throughSourceTurnId, stableIds) &&
                plan.mode == MemoryHeadUpdateMode.UPDATE && !base.state.head.stale
            ) {
                stateLock(sessionId) {
                    val session = chatRepository.getSession(sessionId) ?: return@stateLock
                    setHeadStatusLocked(session, MemoryUpdateStatus.IDLE, null)
                }
                return@headLock
            }
            val newIds = plan.inputSourceTurnIds
            val throughT = MemoryTimelinePolicy.displayT(targetId, base.state.timeline)
                ?: error("HEAD目标source turn没有显示T")
            val baseHeadVersion = base.state.head.version
            val baseStateRevision = base.state.revision
            val sourceHash = MemoryHashes.sourceRefs(sourceRefs(newIds, base.messages, base.session))
            val response = ai.head(
                model = modelConfig,
                mode = plan.mode,
                throughT = throughT,
                currentHead = if (plan.mode == MemoryHeadUpdateMode.UPDATE) base.state.head.render() else "",
                archive = if (plan.mode == MemoryHeadUpdateMode.BACKFILL) renderArchive(base) else "",
                sourceTurns = renderSourceTurns(newIds, base.messages, base.state.timeline, base.session)
            ) { output ->
                check(output.throughT == throughT) { "HEAD伪造throughT" }
                check(output.hasContent()) { "HEAD所有字段均为空" }
            }

            stateLock(sessionId) {
                val current = loadLocked(sessionId)
                if (!current.session.longTermMemoryEnabled ||
                    current.state.head.version != baseHeadVersion ||
                    (plan.mode == MemoryHeadUpdateMode.BACKFILL && current.state.revision != baseStateRevision)
                ) {
                    setHeadStatusLocked(current.session, MemoryUpdateStatus.IDLE, null)
                    return@stateLock
                }
                val currentHash = MemoryHashes.sourceRefs(
                    sourceRefs(newIds, current.messages, current.session)
                )
                if (currentHash != sourceHash) {
                    setHeadStatusLocked(current.session, MemoryUpdateStatus.IDLE, null)
                    return@stateLock
                }
                val inputHashes = sourceRefs(newIds, current.messages, current.session).associate {
                    it.sourceTurnId to it.sourceHash
                }
                val archiveHashes = if (plan.mode == MemoryHeadUpdateMode.BACKFILL) {
                    (current.state.activeNodeIds + current.state.legacyReferenceNodeIds)
                        .mapNotNull(current.nodes::get)
                        .flatMap { it.sourceHashes.entries }
                        .associate { it.key to it.value }
                } else {
                    emptyMap()
                }
                val head = MemoryHead(
                    throughSourceTurnId = targetId,
                    location = response.location.trim(),
                    participants = response.participants.trim(),
                    relationships = response.relationships.trim(),
                    goals = response.goals.trim(),
                    unresolved = response.unresolved.trim(),
                    worldState = response.worldState.trim(),
                    sourceHashes = when (plan.mode) {
                        MemoryHeadUpdateMode.UPDATE -> current.state.head.sourceHashes + inputHashes
                        MemoryHeadUpdateMode.INITIALIZE -> inputHashes
                        MemoryHeadUpdateMode.BACKFILL -> archiveHashes + inputHashes
                    },
                    version = baseHeadVersion + 1,
                    stale = plan.mode == MemoryHeadUpdateMode.UPDATE && current.state.head.stale
                )
                val next = current.state.copy(
                    head = head,
                    revision = current.state.revision + 1,
                    updatedAt = System.currentTimeMillis()
                )
                memoryRepository.saveState(next)
                compileAndCacheLocked(
                    current.copy(state = next),
                    headStatus = MemoryUpdateStatus.IDLE,
                    headError = null
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stateLock(sessionId) {
                val session = chatRepository.getSession(sessionId) ?: return@stateLock
                setHeadStatusLocked(
                    session,
                    if (session.longTermMemoryEnabled) MemoryUpdateStatus.ERROR else MemoryUpdateStatus.IDLE,
                    if (session.longTermMemoryEnabled) error.message ?: error::class.simpleName else null
                )
            }
        }
    }

    private suspend fun rebuildHeadFromAnchor(
        sessionId: String,
        sourceTurnId: String,
        modelConfig: ModelConfig
    ) = headLock(sessionId) {
        val base = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            setHeadStatusLocked(loaded.session, MemoryUpdateStatus.UPDATING, null)
            loaded
        }
        try {
            val throughT = MemoryTimelinePolicy.displayT(sourceTurnId, base.state.timeline)
                ?: error("当前锚点没有显示T")
            val refs = sourceRefs(listOf(sourceTurnId), base.messages, base.session)
            val sourceHash = MemoryHashes.sourceRefs(refs)
            val baseVersion = base.state.head.version
            val response = ai.head(
                model = modelConfig,
                mode = MemoryHeadUpdateMode.BACKFILL,
                throughT = throughT,
                currentHead = "",
                archive = renderArchive(base),
                sourceTurns = renderSourceTurns(
                    listOf(sourceTurnId),
                    base.messages,
                    base.state.timeline,
                    base.session
                )
            ) { output -> check(output.throughT == throughT) { "HEAD伪造throughT" } }
            stateLock(sessionId) {
                val current = loadLocked(sessionId)
                check(current.state.head.version == baseVersion) { "HEAD锚点提交前版本已变化" }
                check(
                    MemoryHashes.sourceRefs(
                        sourceRefs(listOf(sourceTurnId), current.messages, current.session)
                    ) == sourceHash
                ) { "HEAD锚点来源已变化" }
                val head = MemoryHead(
                    throughSourceTurnId = sourceTurnId,
                    location = response.location.trim(),
                    participants = response.participants.trim(),
                    relationships = response.relationships.trim(),
                    goals = response.goals.trim(),
                    unresolved = response.unresolved.trim(),
                    worldState = response.worldState.trim(),
                    sourceHashes = refs.associate { it.sourceTurnId to it.sourceHash },
                    version = baseVersion + 1
                )
                val next = current.state.copy(
                    head = head,
                    revision = current.state.revision + 1,
                    updatedAt = System.currentTimeMillis()
                )
                memoryRepository.saveState(next)
                compileAndCacheLocked(
                    current.copy(state = next),
                    headStatus = MemoryUpdateStatus.IDLE,
                    headError = null
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stateLock(sessionId) {
                val session = chatRepository.getSession(sessionId) ?: return@stateLock
                setHeadStatusLocked(
                    session,
                    MemoryUpdateStatus.ERROR,
                    error.message ?: error::class.simpleName
                )
            }
        }
    }

    suspend fun editNode(
        sessionId: String,
        nodeId: String,
        bodyText: String
    ) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        val old = loaded.nodes[nodeId] ?: error("记忆节点不存在")
        val tier = old.tier
        check(tier != MemoryTier.LEGACY_REFERENCE) { "Legacy Reference请通过补录替换" }
        check(nodeId in loaded.state.page(tier).activeNodeIds) { "只能编辑当前分页活跃节点" }
        val text = bodyText.trim()
        check(text.isNotBlank()) { "正文不能为空" }
        check(MemorySummaryPolicy.hasOnlyQualifiedStateWords(text)) {
            "Archive包含无T限定的现在/目前/仍然"
        }
        val currentSourceHashes = old.sourceTurnIds.associateWith { sourceId ->
            sourceHash(sourceId, loaded.messages, loaded.session)
        }
        val edited = old.copy(
            id = MemoryNode.newId(),
            content = text,
            overview = text.take(120),
            sourceHash = if (old.childIds.isEmpty()) {
                MemoryHashes.sourceIds(
                    old.sourceTurnIds,
                    old.sourceTurnIds.map(currentSourceHashes::getValue)
                )
            } else {
                old.sourceHash
            },
            sourceHashes = currentSourceHashes,
            coverageHash = if (old.tier == MemoryTier.EPISODE && old.coverageUnits.isEmpty()) {
                MemoryHashes.episodeCoverage(old.sourceTurnIds, currentSourceHashes, text)
            } else {
                old.coverageHash
            },
            author = MemoryAuthor.USER,
            createdAt = System.currentTimeMillis()
        )
        memoryRepository.saveNodes(listOf(edited))
        val page = loaded.state.page(tier)
        val after = page.activeNodeIds.map { if (it == nodeId) edited.id else it }
        val checkpoint = revisions.checkpoint(
            initialState = loaded.state,
            tier = tier,
            afterNodeIds = after,
            operation = MemoryRevisionOperation.USER_EDIT,
            author = MemoryAuthor.USER,
            affectedSourceTurnIds = edited.sourceTurnIds
        )
        memoryRepository.saveState(checkpoint.state)
        compileAndCacheLocked(loaded.copy(state = checkpoint.state, nodes = loaded.nodes.apply {
            put(edited.id, edited)
        }))
    }

    /**
     * Re-runs the original generation protocol for one active node.
     * Returns a review candidate only; persistence still goes through editNode/checkpoint.
     */
    suspend fun regenerateNodeCandidate(
        sessionId: String,
        nodeId: String,
        model: ModelConfig,
        onStreamingSummary: ((String) -> Unit)? = null
    ): String {
        val request = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            buildNodeRegenerationRequest(loaded, nodeId)
        }
        val responseText = when (request.plan.node.tier) {
            MemoryTier.EPISODE -> {
                val sourceTurnCount = request.plan.node.sourceTurnIds.size
                ai.episode(
                    model = model,
                    renderedTurns = request.renderedEvidence,
                    summaryMaxChars = MemoryEpisodeSummaryPolicy.maxChars(sourceTurnCount),
                    onStreamingSummary = onStreamingSummary
                ) { output -> validateEpisodeResponse(output, sourceTurnCount) }.summary
            }

            MemoryTier.ARC, MemoryTier.ERA -> {
                val children = request.plan.children
                val candidate = MemoryCompressionCandidate(
                    candidates = children,
                    minConsume = children.size,
                    maxConsume = children.size
                )
                ai.compression(
                    model = model,
                    kind = requireNotNull(request.plan.compressionKind),
                    forcedConsumedChildIds = children.map { it.id },
                    renderedChildren = request.renderedEvidence,
                    onStreamingSummary = onStreamingSummary
                ) { output ->
                    check(output.compressible) { "重新生成不得返回不可压缩" }
                    validateCompressionResponse(output, candidate)
                    val coverageChars = output.childCoverage.sumOf { it.text.trim().length } +
                        (output.childCoverage.size - 1).coerceAtLeast(0)
                    check(coverageChars < children.sumOf { it.body.length }) {
                        "重新生成的child覆盖没有形成压缩"
                    }
                    check(output.summary.trim().length < children.sumOf { it.body.length }) {
                        "重新生成的正文没有形成压缩"
                    }
                    check(MemorySummaryPolicy.hasOnlyQualifiedStateWords(output.summary)) {
                        "重新生成正文包含无T限定的现在/目前/仍然"
                    }
                }.summary
            }

            MemoryTier.LEGACY_REFERENCE -> error("Legacy Reference无法从可靠来源重新生成")
        }.trim()

        stateLock(sessionId) {
            val current = loadLocked(sessionId)
            val currentRequest = buildNodeRegenerationRequest(current, nodeId)
            MemoryNodeRegenerationPolicy.requireStillCurrent(
                originalPlan = request.plan,
                originalEvidenceHash = request.evidenceHash,
                currentPlan = currentRequest.plan,
                currentEvidenceHash = currentRequest.evidenceHash
            )
        }
        return responseText
    }

    suspend fun editHead(sessionId: String, head: MemoryHead) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        val throughOrder = loaded.state.head.throughSourceTurnId?.let { sourceId ->
            loaded.state.timeline.firstOrNull { it.sourceTurnId == sourceId }?.sourceOrder
        }
        val currentHashes = loaded.state.timeline.asSequence()
            .filter { throughOrder != null && it.sourceOrder <= throughOrder }
            .associate { it.sourceTurnId to sourceHash(it.sourceTurnId, loaded.messages, loaded.session) }
        val corrected = head.copy(
            throughSourceTurnId = loaded.state.head.throughSourceTurnId,
            throughT = null,
            sourceHashes = currentHashes,
            version = loaded.state.head.version + 1,
            stale = false
        )
        val next = loaded.state.copy(
            head = corrected,
            revision = loaded.state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(next)
        compileAndCacheLocked(loaded.copy(state = next))
    }

    suspend fun restoreTierRevision(sessionId: String, revisionId: String) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        val checkpoint = revisions.restore(loaded.state, revisionId)
        memoryRepository.saveState(checkpoint.state)
        val restoredNodes = memoryRepository.getReachableNodes(
            checkpoint.state.activeNodeIds + checkpoint.state.legacyReferenceNodeIds
        ).associateBy { it.id }.toMutableMap()
        compileAndCacheLocked(loaded.copy(state = checkpoint.state, nodes = restoredNodes))
    }

    suspend fun markSourcesCorrected(sessionId: String, nodeIds: List<String>) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        if (nodeIds.isEmpty()) {
            val throughOrder = loaded.state.head.throughSourceTurnId?.let { sourceId ->
                loaded.state.timeline.firstOrNull { it.sourceTurnId == sourceId }?.sourceOrder
            }
            val hashes = loaded.state.timeline.asSequence()
                .filter { throughOrder != null && it.sourceOrder <= throughOrder }
                .associate { it.sourceTurnId to sourceHash(it.sourceTurnId, loaded.messages, loaded.session) }
            val next = loaded.state.copy(
                head = loaded.state.head.copy(
                    sourceHashes = hashes,
                    version = loaded.state.head.version + 1,
                    stale = false
                ),
                revision = loaded.state.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveState(next)
            compileAndCacheLocked(loaded.copy(state = next))
            return@stateLock
        }

        val requested = nodeIds.toSet()
        val replacements = loaded.state.activeNodeIds.mapNotNull { nodeId ->
            val old = loaded.nodes[nodeId] ?: return@mapNotNull null
            if (nodeId !in requested || nodeId !in loaded.state.staleSourcesByNodeId) return@mapNotNull null
            val hashes = old.sourceTurnIds.associateWith { sourceId ->
                sourceHash(sourceId, loaded.messages, loaded.session)
            }
            old to old.copy(
                id = MemoryNode.newId(),
                sourceHash = if (old.childIds.isEmpty()) {
                    MemoryHashes.sourceIds(old.sourceTurnIds, old.sourceTurnIds.map(hashes::getValue))
                } else {
                    old.sourceHash
                },
                sourceHashes = hashes,
                author = MemoryAuthor.USER,
                createdAt = System.currentTimeMillis()
            )
        }
        if (replacements.isEmpty()) return@stateLock
        val replacementByOldId = replacements.associate { (old, replacement) -> old.id to replacement }
        memoryRepository.saveNodes(replacements.map { it.second })
        loaded.nodes.putAll(replacements.map { it.second }.associateBy { it.id })
        var next = loaded.state
        listOf(MemoryTier.EPISODE, MemoryTier.ARC, MemoryTier.ERA).forEach { tier ->
            val page = next.page(tier)
            val changed = page.activeNodeIds.map { replacementByOldId[it]?.id ?: it }
            if (changed != page.activeNodeIds) {
                next = revisions.checkpoint(
                    initialState = next,
                    tier = tier,
                    afterNodeIds = changed,
                    operation = MemoryRevisionOperation.USER_EDIT,
                    author = MemoryAuthor.USER,
                    affectedSourceTurnIds = replacements
                        .filter { it.first.tier == tier }
                        .flatMap { it.first.sourceTurnIds }
                ).state
            }
        }
        next = next.copy(
            staleSourcesByNodeId = next.staleSourcesByNodeId - replacementByOldId.keys,
            revision = next.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(next)
        compileAndCacheLocked(loaded.copy(state = next))
    }

    suspend fun increaseLimit(sessionId: String) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        if (!MemoryBudgetPolicy.canIncrease(loaded.session.memoryLimitChars)) return@stateLock
        val updatedSession = loaded.session.copy(
            memoryLimitChars = MemoryBudgetPolicy.increase(loaded.session.memoryLimitChars),
            memoryArchiveStatus = MemoryUpdateStatus.IDLE,
            memoryUpdateStatus = MemoryUpdateStatus.IDLE
        )
        val nextState = loaded.state.copy(
            pendingDecision = null,
            revision = loaded.state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(nextState)
        chatRepository.updateSession(updatedSession)
        compileAndCacheLocked(loaded.copy(session = updatedSession, state = nextState))
    }

    suspend fun resolveCompressionDecision(
        sessionId: String,
        expand: Boolean,
        modelConfig: ModelConfig,
        contextWindowSize: Int? = null
    ) {
        val resumeBackfill = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            loaded.state.pendingDecision != null &&
                loaded.state.backfill.status == MemoryBackfillStatus.PAUSED &&
                loaded.state.backfill.pendingSourceTurnIds.isNotEmpty()
        }
        if (expand) {
            increaseLimit(sessionId)
            if (resumeBackfill) startBackfill(sessionId, modelConfig)
            return
        }
        stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            val decision = loaded.state.pendingDecision ?: return@stateLock
            val next = when (decision.tier) {
                MemoryDecisionTier.EPISODE -> loaded.state.copy(
                    episodeCompressionPromptDeclined = true
                )
                MemoryDecisionTier.ARC -> loaded.state.copy(
                    arcCompressionPromptDeclined = true
                )
                MemoryDecisionTier.ERA -> loaded.state.copy(
                    eraCompressionPromptDeclined = true,
                    eraCompressionsSincePrompt = 0
                )
            }.copy(
                pendingDecision = null,
                revision = loaded.state.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveState(next)
            setArchiveStatusLocked(loaded.session, MemoryUpdateStatus.IDLE, null)
        }
        if (resumeBackfill) {
            startBackfill(sessionId, modelConfig)
        } else {
            updateArchiveAfterReply(sessionId, modelConfig, contextWindowSize)
        }
    }

    suspend fun consumeCompressionEvents(sessionId: String): List<MemoryCompressionEvent> =
        stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            val events = loaded.state.pendingCompressionEvents
            if (events.isNotEmpty()) {
                memoryRepository.saveState(
                    loaded.state.copy(
                        pendingCompressionEvents = emptyList(),
                        revision = loaded.state.revision + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            events
        }

    suspend fun setEnabled(sessionId: String, enabled: Boolean) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        if (loaded.session.longTermMemoryEnabled == enabled) return@stateLock
        val covered = loaded.state.activeNodeIds.mapNotNull(loaded.nodes::get)
            .flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val alreadyUnavailable = loaded.state.gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        alreadyUnavailable += loaded.state.pendingSourceTurnIds
        val disabledIds = if (enabled) {
            val baseline = loaded.state.disabledAfterSourceOrder ?: Long.MAX_VALUE
            stableSourceTurnIds(loaded.messages).filter { sourceId ->
                sourceOrder(sourceId, loaded.messages, loaded.session) > baseline &&
                    sourceId !in covered && sourceId !in alreadyUnavailable
            }
        } else {
            loaded.state.pendingSourceTurnIds
        }
        val gaps = if (disabledIds.isEmpty()) loaded.state.gaps else {
            val sorted = sortSourceIds(disabledIds, loaded.messages, loaded.session)
            loaded.state.gaps + MemoryGap(
                id = MemoryGap.newId(),
                sourceTurnIds = sorted,
                startSourceOrder = sorted.firstOrNull()?.let {
                    sourceOrder(it, loaded.messages, loaded.session)
                },
                endSourceOrder = sorted.lastOrNull()?.let {
                    sourceOrder(it, loaded.messages, loaded.session)
                },
                reason = MemoryGapReason.DISABLED
            )
        }
        val lastStableOrder = stableSourceTurnIds(loaded.messages).lastOrNull()
            ?.let { sourceOrder(it, loaded.messages, loaded.session) }
            ?: -1L
        val nextState = loaded.state.copy(
            gaps = gaps,
            pendingSourceTurnIds = if (enabled) loaded.state.pendingSourceTurnIds else emptyList(),
            memoryWasEnabled = enabled,
            disabledAfterSourceOrder = if (enabled) null else lastStableOrder,
            revision = loaded.state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(nextState)
        val nextSession = loaded.session.copy(
            longTermMemoryEnabled = enabled,
            memoryArchiveStatus = MemoryUpdateStatus.IDLE,
            memoryHeadStatus = MemoryUpdateStatus.IDLE,
            memoryUpdateStatus = MemoryUpdateStatus.IDLE
        )
        chatRepository.updateSession(nextSession)
        if (enabled) compileAndCacheLocked(loaded.copy(session = nextSession, state = nextState))
    }

    suspend fun estimateBackfill(sessionId: String): MemoryBackfillEstimate = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        val appSettings = settingsRepository.getAppSettings()
        val available = backfillableSourceTurnIds(
            loaded,
            appSettings.defaultContextWindowSize.coerceAtLeast(1)
        )
        val n = appSettings.episodeMaxSourceTurns.coerceIn(1, 6)
        val episodeMax = available.size
        val activeCount = loaded.state.activeNodeIds.size
        val sourceCharacters = loaded.messages
            .filter { it.sourceTurnId in available && it.role != MessageRole.SYSTEM }
            .sumOf { it.displayContent.length }
        MemoryBackfillEstimate(
            missingSourceTurns = available.size,
            episodeCallsMin = if (available.isEmpty()) 0 else (available.size + n - 1) / n,
            episodeCallsMax = episodeMax,
            compressionCallsMin = 0,
            // 输出长度和语义false无法预知；给UI一个保守结构上界，不用于执行决策。
            compressionCallsMax = (activeCount + episodeMax) * 4,
            sourceCharacters = sourceCharacters
        )
    }

    suspend fun startBackfill(
        sessionId: String,
        modelConfig: ModelConfig,
        onProgress: (MemoryBackfillProgress) -> Unit = {}
    ) = archiveLock(sessionId) {
        val initial = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            val appSettings = settingsRepository.getAppSettings()
            val missing = backfillableSourceTurnIds(
                loaded,
                appSettings.defaultContextWindowSize.coerceAtLeast(1)
            )
            val stableIds = stableSourceTurnIds(loaded.messages)
            val headBackfillRequired = MemoryHeadUpdatePolicy.requiresBackfill(
                hasHeadContent = loaded.state.head.render().isNotBlank(),
                throughSourceTurnId = loaded.state.head.throughSourceTurnId,
                stableSourceTurnIds = stableIds,
                hasHistoricalMemory = loaded.state.activeNodeIds.isNotEmpty() ||
                    loaded.state.legacyReferenceNodeIds.isNotEmpty() ||
                    loaded.state.gaps.isNotEmpty()
            )
            if (missing.isEmpty() && !headBackfillRequired) return@stateLock null
            val n = appSettings.episodeMaxSourceTurns.coerceIn(1, 6)
            val finalTimeline = sourceTimeline(loaded.messages, loaded.session)
            val next = loaded.state.copy(
                timeline = finalTimeline,
                backfill = MemoryBackfillState(
                    status = MemoryBackfillStatus.RUNNING,
                    pendingSourceTurnIds = sortSourceIds(missing, loaded.messages, loaded.session),
                    finalTimeline = finalTimeline,
                    estimatedEpisodeCallsMin = (missing.size + n - 1) / n,
                    estimatedEpisodeCallsMax = missing.size,
                    capturedEpisodeMaxSourceTurns = n
                ),
                revision = loaded.state.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
            activeBackfillSessionIds.add(sessionId)
            try {
                memoryRepository.saveState(next)
            } catch (error: Throwable) {
                activeBackfillSessionIds.remove(sessionId)
                throw error
            }
            loaded.copy(state = next)
        } ?: return@archiveLock

        val totalSourceTurns = initial.state.backfill.pendingSourceTurnIds.size
        fun notifyProgress(progress: MemoryBackfillProgress) {
            runCatching { onProgress(progress) }
        }
        notifyProgress(
            MemoryBackfillProgress(
                phase = MemoryBackfillPhase.PREPARING,
                totalSourceTurns = totalSourceTurns,
                completedSourceTurns = 0,
                completedEpisodes = 0
            )
        )
        try {
            var loaded = initial
            while (loaded.state.backfill.status == MemoryBackfillStatus.RUNNING &&
                loaded.state.backfill.pendingSourceTurnIds.isNotEmpty()
            ) {
                val n = settingsRepository.getAppSettings().episodeMaxSourceTurns.coerceIn(1, 6)
                val pending = loaded.state.backfill.pendingSourceTurnIds
                val batch = nextSourceBatch(
                    pending = pending,
                    state = loaded.state,
                    messages = loaded.messages,
                    maxTurns = n,
                    forcePartial = true
                )
                check(batch.isNotEmpty()) { "补录范围无法按连续source turn分组" }
                val refs = sourceRefs(batch, loaded.messages, loaded.session)
                val sourceHash = MemoryHashes.sourceRefs(refs)
                val baseRevision = loaded.state.revision
                val completedTurnsBefore = loaded.state.backfill.completedSourceTurnIds.size
                val completedEpisodesBefore = loaded.state.backfill.completedEpisodeCount
                val range = MemoryTimelinePolicy.range(batch, loaded.state.timeline)
                val rangeLabel = if (range == null) "" else "T${range.startT}-T${range.endT}"
                fun progress(
                    phase: MemoryBackfillPhase,
                    streamingSummary: String = ""
                ) = MemoryBackfillProgress(
                    phase = phase,
                    totalSourceTurns = totalSourceTurns,
                    completedSourceTurns = completedTurnsBefore,
                    completedEpisodes = completedEpisodesBefore,
                    currentBatchSourceTurnIds = batch,
                    currentRangeLabel = rangeLabel,
                    streamingSummary = streamingSummary
                )
                notifyProgress(progress(MemoryBackfillPhase.GENERATING_EPISODE))
                val summaryMaxChars = MemoryEpisodeSummaryPolicy.maxChars(batch.size)
                val response = ai.episode(
                    modelConfig,
                    renderSourceTurns(batch, loaded.messages, loaded.state.timeline, loaded.session),
                    summaryMaxChars,
                    onStreamingSummary = { summary ->
                        notifyProgress(progress(MemoryBackfillPhase.GENERATING_EPISODE, summary))
                    }
                ) { output -> validateEpisodeResponse(output, batch.size) }
                val node = createEpisodeNode(sessionId, refs, response)
                val current = stateLock(sessionId) { loadLocked(sessionId) }
                if (current.state.backfill.status != MemoryBackfillStatus.RUNNING) break
                check(current.state.revision == baseRevision) { "补录Episode运行期间版本已变化" }
                val currentHash = MemoryHashes.sourceRefs(
                    sourceRefs(batch, current.messages, current.session)
                )
                check(currentHash == sourceHash) { "补录Episode来源已变化，丢弃过期结果" }
                notifyProgress(progress(MemoryBackfillPhase.CHECKING_SPACE, response.summary.trim()))
                val maintenance = maintainUntilFits(
                    sessionId = sessionId,
                    initialState = current.state,
                    initialNodes = current.nodes,
                    stagedEpisode = node,
                    model = modelConfig,
                    limitChars = current.session.memoryLimitChars
                )
                if (maintenance is MaintenanceResult.DecisionRequired) {
                    stateLock(sessionId) {
                        val waiting = loadLocked(sessionId)
                        memoryRepository.saveState(
                            waiting.state.copy(
                                backfill = waiting.state.backfill.copy(
                                    status = MemoryBackfillStatus.PAUSED,
                                    updatedAt = System.currentTimeMillis()
                                ),
                                revision = waiting.state.revision + 1,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    break
                }
                maintenance as MaintenanceResult.Ready
                val refreshed = stateLock(sessionId) { loadLocked(sessionId) }
                check(refreshed.state.revision == maintenance.state.revision) {
                    "补录Episode提交前版本已变化"
                }
                check(refreshed.state.backfill.status == MemoryBackfillStatus.RUNNING) {
                    "补录已暂停"
                }
                notifyProgress(progress(MemoryBackfillPhase.SAVING_EPISODE, response.summary.trim()))
                val nodes = refreshed.nodes.toMutableMap().apply { put(node.id, node) }
                val nextState = revisions.appendPure(refreshed.state, MemoryTier.EPISODE, node.id).copy(
                    backfill = refreshed.state.backfill.copy(
                        pendingSourceTurnIds = refreshed.state.backfill.pendingSourceTurnIds.filterNot { it in batch },
                        completedSourceTurnIds = refreshed.state.backfill.completedSourceTurnIds + batch,
                        completedEpisodeCount = refreshed.state.backfill.completedEpisodeCount + 1,
                        capturedEpisodeMaxSourceTurns = n,
                        updatedAt = System.currentTimeMillis()
                    ),
                    gaps = refreshed.state.gaps.mapNotNull { gap ->
                        val remaining = gap.sourceTurnIds.filterNot { it in batch }
                        gap.copy(sourceTurnIds = remaining).takeIf { remaining.isNotEmpty() }
                    },
                    revision = refreshed.state.revision + 1,
                    updatedAt = System.currentTimeMillis()
                )
                check(!MemoryBudgetPolicy.isOverLimit(nextState, nodes, refreshed.session.memoryLimitChars)) {
                    "补录Episode提交后仍超过自动预算"
                }
                requireValidNode(node, nodes, nextState)
                memoryRepository.saveNodes(listOf(node))
                memoryRepository.saveState(nextState)
                loaded = refreshed.copy(state = nextState, nodes = nodes)
                notifyProgress(
                    MemoryBackfillProgress(
                        phase = MemoryBackfillPhase.PREPARING,
                        totalSourceTurns = totalSourceTurns,
                        completedSourceTurns = nextState.backfill.completedSourceTurnIds.size,
                        completedEpisodes = nextState.backfill.completedEpisodeCount
                    )
                )
            }
            val completed = stateLock(sessionId) {
                val current = loadLocked(sessionId)
                if (current.state.backfill.pendingSourceTurnIds.isEmpty()) {
                    val next = current.state.copy(
                        legacyReferenceNodeIds = emptyList(),
                        backfill = current.state.backfill.copy(updatedAt = System.currentTimeMillis()),
                        revision = current.state.revision + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                    memoryRepository.saveState(next)
                    compileAndCacheLocked(current.copy(state = next))
                    true
                } else {
                    false
                }
            }
            if (completed) {
                notifyProgress(
                    MemoryBackfillProgress(
                        phase = MemoryBackfillPhase.UPDATING_HEAD,
                        totalSourceTurns = totalSourceTurns,
                        completedSourceTurns = totalSourceTurns,
                        completedEpisodes = loaded.state.backfill.completedEpisodeCount
                    )
                )
                backfillHead(sessionId, modelConfig)
                stateLock(sessionId) {
                    val current = loadLocked(sessionId)
                    if (current.state.backfill.status != MemoryBackfillStatus.RUNNING) {
                        compileAndCacheLocked(current)
                        return@stateLock
                    }
                    val next = current.state.copy(
                        backfill = current.state.backfill.copy(
                            status = MemoryBackfillStatus.IDLE,
                            error = null,
                            updatedAt = System.currentTimeMillis()
                        ),
                        revision = current.state.revision + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                    memoryRepository.saveState(next)
                    compileAndCacheLocked(current.copy(state = next))
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stateLock(sessionId) {
                val current = loadLocked(sessionId)
                memoryRepository.saveState(
                    current.state.copy(
                        backfill = current.state.backfill.copy(
                            status = MemoryBackfillStatus.ERROR,
                            error = error.message,
                            updatedAt = System.currentTimeMillis()
                        ),
                        revision = current.state.revision + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } finally {
            activeBackfillSessionIds.remove(sessionId)
        }
    }

    suspend fun pauseBackfill(sessionId: String) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        if (loaded.state.backfill.status != MemoryBackfillStatus.RUNNING) return@stateLock
        memoryRepository.saveState(
            loaded.state.copy(
                backfill = loaded.state.backfill.copy(
                    status = MemoryBackfillStatus.PAUSED,
                    updatedAt = System.currentTimeMillis()
                ),
                revision = loaded.state.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun declineBackfill(sessionId: String, modelConfig: ModelConfig) = archiveLock(sessionId) {
        val prepared = stateLock(sessionId) {
            val loaded = loadLocked(sessionId)
            val latest = stableSourceTurnIds(loaded.messages).lastOrNull() ?: return@stateLock null
            val hasExistingMemory = loaded.state.activeNodeIds.isNotEmpty() ||
                loaded.state.legacyReferenceNodeIds.isNotEmpty()
            val timeline = if (hasExistingMemory) {
                loaded.state.timeline
            } else {
                listOf(
                    MemoryTimelineEntry(
                        latest,
                        sourceOrder(latest, loaded.messages, loaded.session),
                        0
                    )
                )
            }
            val next = loaded.state.copy(
                timeline = timeline,
                gaps = if (hasExistingMemory) {
                    loaded.state.gaps.mapNotNull { gap ->
                        gap.copy(sourceTurnIds = gap.sourceTurnIds - latest)
                            .takeIf { it.sourceTurnIds.isNotEmpty() }
                    }
                } else {
                    emptyList()
                },
                pendingSourceTurnIds = listOf(latest),
                head = MemoryHead(version = loaded.state.head.version + 1),
                backfill = MemoryBackfillState(),
                revision = loaded.state.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveState(next)
            loaded.copy(state = next) to latest
        } ?: return@archiveLock

        val (base, latest) = prepared
        val refs = sourceRefs(listOf(latest), base.messages, base.session)
        val baseRevision = base.state.revision
        val summaryMaxChars = MemoryEpisodeSummaryPolicy.maxChars(1)
        val response = ai.episode(
            modelConfig,
            renderSourceTurns(listOf(latest), base.messages, base.state.timeline, base.session),
            summaryMaxChars
        ) { output -> validateEpisodeResponse(output, 1) }
        val node = createEpisodeNode(sessionId, refs, response)
        val current = stateLock(sessionId) { loadLocked(sessionId) }
        check(current.state.revision == baseRevision) { "当前锚点运行期间版本已变化" }
        check(current.state.pendingSourceTurnIds == listOf(latest)) { "当前锚点提交前状态已变化" }
        check(
            MemoryHashes.sourceRefs(sourceRefs(listOf(latest), current.messages, current.session)) ==
                MemoryHashes.sourceRefs(refs)
        ) { "当前锚点来源已变化" }
        val maintenance = maintainUntilFits(
            sessionId,
            current.state,
            current.nodes,
            node,
            modelConfig,
            current.session.memoryLimitChars
        )
        if (maintenance is MaintenanceResult.DecisionRequired) return@archiveLock
        maintenance as MaintenanceResult.Ready
        val refreshed = stateLock(sessionId) { loadLocked(sessionId) }
        check(refreshed.state.revision == maintenance.state.revision) { "当前锚点提交前版本已变化" }
        val nodes = refreshed.nodes.toMutableMap().apply { put(node.id, node) }
        val next = revisions.appendPure(refreshed.state, MemoryTier.EPISODE, node.id).copy(
            pendingSourceTurnIds = emptyList(),
            revision = refreshed.state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        check(!MemoryBudgetPolicy.isOverLimit(next, nodes, refreshed.session.memoryLimitChars)) {
            "当前锚点提交后仍超过自动预算"
        }
        requireValidNode(node, nodes, next)
        memoryRepository.saveNodes(listOf(node))
        memoryRepository.saveState(next)
        compileAndCacheLocked(refreshed.copy(state = next, nodes = nodes))
        rebuildHeadFromAnchor(sessionId, latest, modelConfig)
    }

    suspend fun snapshot(sessionId: String): MemorySnapshot? = memoryRepository.snapshot(sessionId)

    suspend fun loadSnapshot(sessionId: String, snapshot: MemorySnapshot?) = stateLock(sessionId) {
        val loaded = loadLocked(sessionId)
        val contextWindowSize = currentContextWindowSize()
        val sourceSnapshot = snapshot ?: legacySaveSlotSnapshot(loaded, contextWindowSize)
        val normalizedSnapshot = if (sourceSnapshot.state == null) {
            migrateLegacySaveSlotSnapshot(sourceSnapshot, loaded, contextWindowSize)
        } else {
            sourceSnapshot
        }
        val rebound = MemorySnapshotImportPolicy.rebind(normalizedSnapshot, sessionId)
        val importedState = rebound.state ?: error("存档缺少长期记忆状态")
        validateImportedSnapshot(rebound, importedState)
        memoryRepository.saveNodes(rebound.nodes)
        var state = loaded.state
        val replacements = listOf(
            MemoryTier.EPISODE to importedState.episodeNodeIds,
            MemoryTier.ARC to importedState.arcNodeIds,
            MemoryTier.ERA to importedState.eraNodeIds
        )
        replacements.forEach { (tier, ids) ->
            if (state.page(tier).activeNodeIds != ids) {
                state = revisions.checkpoint(
                    initialState = state,
                    tier = tier,
                    afterNodeIds = ids,
                    operation = MemoryRevisionOperation.LOAD_SAVE,
                    author = MemoryAuthor.RESTORE,
                    affectedSourceTurnIds = ids.flatMap { id ->
                        rebound.nodes.firstOrNull { it.id == id }?.sourceTurnIds.orEmpty()
                    }
                ).state
            }
        }
        state = state.copy(
            legacyReferenceNodeIds = importedState.legacyReferenceNodeIds,
            head = importedState.head,
            timeline = importedState.timeline,
            gaps = importedState.gaps,
            staleSourcesByNodeId = importedState.staleSourcesByNodeId,
            pendingSourceTurnIds = importedState.pendingSourceTurnIds,
            episodeCompressionPromptDeclined = importedState.episodeCompressionPromptDeclined,
            arcCompressionPromptDeclined = importedState.arcCompressionPromptDeclined,
            eraCompressionPromptDeclined = importedState.eraCompressionPromptDeclined,
            eraCompressionsSincePrompt = importedState.eraCompressionsSincePrompt,
            pendingDecision = importedState.pendingDecision,
            memoryWasEnabled = importedState.memoryWasEnabled,
            disabledAfterSourceOrder = importedState.disabledAfterSourceOrder,
            recordingStartsAfterSourceOrder = importedState.recordingStartsAfterSourceOrder,
            gapRetentionVersion = importedState.gapRetentionVersion,
            backfill = importedState.backfill.copy(
                status = if (importedState.backfill.status == MemoryBackfillStatus.RUNNING) {
                    MemoryBackfillStatus.PAUSED
                } else {
                    importedState.backfill.status
                }
            ),
            pendingCompressionEvents = emptyList(),
            revision = state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(state)
        compileAndCacheLocked(
            loaded.copy(
                state = state,
                nodes = (loaded.nodes + rebound.nodes.associateBy { it.id }).toMutableMap()
            )
        )
    }

    /**
     * schema v1-v3 SaveSlot没有v4节点快照。载入时仍需覆盖当前活跃记忆，避免旧存档消息
     * 与载入前记忆串档；能读取的旧文本只作为Legacy Reference保留，不猜测层级或coverage。
     */
    private fun legacySaveSlotSnapshot(
        loaded: LoadedMemory,
        contextWindowSize: Int
    ): MemorySnapshot {
        val sourceId = loaded.session.longTermMemoryUpdatedThroughMessageId
            ?.let { messageId -> loaded.messages.firstOrNull { it.id == messageId }?.sourceTurnId }
        val legacyNode = loaded.session.longTermMemory.takeIf(String::isNotBlank)?.let { text ->
            MemoryNode(
                id = MemoryNode.newId(),
                sessionId = loaded.session.id,
                tier = MemoryTier.LEGACY_REFERENCE,
                sourceTurnIds = sourceId?.let(::listOf).orEmpty(),
                coverageUnits = listOf(
                    MemoryCoverageUnit(
                        sourceId = sourceId ?: "legacy:${loaded.session.id}",
                        text = if (sourceId == null) {
                            "时间未知，不代表当前进展：$text"
                        } else {
                            text
                        }
                    )
                ),
                overview = "旧存档长期记忆",
                author = MemoryAuthor.MIGRATION
            )
        }
        val archived = completeArchivedSourceTurnIds(
            loaded.messages,
            contextWindowSize
        )
        return MemorySnapshot(
            state = MemorySessionSnapshot(
                legacyReferenceNodeIds = legacyNode?.let { listOf(it.id) }.orEmpty(),
                timeline = sourceTimeline(loaded.messages, loaded.session),
                gaps = archived.takeIf { it.isNotEmpty() }?.let { sourceIds ->
                    listOf(
                        MemoryGap(
                            id = MemoryGap.newId(),
                            sourceTurnIds = sourceIds,
                            reason = MemoryGapReason.LEGACY_UNKNOWN
                        )
                    )
                }.orEmpty(),
                backfill = if (archived.isEmpty()) {
                    MemoryBackfillState()
                } else {
                    MemoryBackfillState(
                        status = MemoryBackfillStatus.PAUSED,
                        pendingSourceTurnIds = archived
                    )
                },
                memoryWasEnabled = loaded.session.longTermMemoryEnabled
            ),
            nodes = legacyNode?.let(::listOf).orEmpty()
        )
    }

    suspend fun clear(sessionId: String) = stateLock(sessionId) {
        val session = chatRepository.getSession(sessionId) ?: return@stateLock
        val messages = chatRepository.ensureSourceTurns(sessionId)
        val timeline = sourceTimeline(messages, session)
        val clearedThroughOrder = timeline.maxOfOrNull { it.sourceOrder }
        memoryRepository.deleteForSession(sessionId)
        val emptyState = MemorySessionState(
            sessionId = sessionId,
            timeline = emptyList(),
            recordingStartsAfterSourceOrder = clearedThroughOrder,
            gapRetentionVersion = CURRENT_MEMORY_GAP_RETENTION_VERSION,
            memoryWasEnabled = session.longTermMemoryEnabled,
            revision = 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(emptyState)
        chatRepository.updateSession(
            session.copy(
                longTermMemory = "",
                longTermMemoryUpdatedThroughMessageId = null,
                memoryStateRevision = emptyState.revision,
                memoryHeadCommitId = null,
                memoryUpdateStatus = MemoryUpdateStatus.IDLE,
                memoryUpdateError = null,
                memoryArchiveStatus = MemoryUpdateStatus.IDLE,
                memoryArchiveError = null,
                memoryHeadStatus = MemoryUpdateStatus.IDLE,
                memoryHeadError = null,
                longTermMemorySchemaVersion = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION
            )
        )
    }

    // ===== 预算与压缩 =====

    private suspend fun maintainUntilFits(
        sessionId: String,
        initialState: MemorySessionState,
        initialNodes: MutableMap<String, MemoryNode>,
        stagedEpisode: MemoryNode,
        model: ModelConfig,
        limitChars: Int
    ): MaintenanceResult {
        var state = initialState
        val nodes = initialNodes
        var rejectedEpisodeCandidate: List<String>? = null
        var rejectedArcCandidate: List<String>? = null
        fun projectedOver(): Boolean {
            val projected = revisions.appendPure(state, MemoryTier.EPISODE, stagedEpisode.id)
            val projectedNodes = nodes + (stagedEpisode.id to stagedEpisode)
            return MemoryBudgetPolicy.isOverLimit(projected, projectedNodes, limitChars)
        }
        while (projectedOver()) {
            val episodeCandidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                nodes = state.episodePage.activeNodeIds.mapNotNull(nodes::get),
                expectedTier = MemoryTier.EPISODE,
                timeline = state.timeline,
                gaps = state.gaps,
                newestRetainedOutsideCandidates = true
            )
            val episodeCandidateIds = episodeCandidate?.candidates?.map { it.id }
            if (episodeCandidate != null && episodeCandidateIds != rejectedEpisodeCandidate) {
                val episodeDecision = requireDecisionIfNeeded(state, MemoryDecisionTier.EPISODE, limitChars)
                if (episodeDecision != null) {
                    persistDecision(sessionId, episodeDecision)
                    return MaintenanceResult.DecisionRequired(episodeDecision)
                }
                val episodeAttempt = compressLowerTier(
                    sessionId,
                    state,
                    nodes,
                    MemoryCompressionKind.EPISODE_TO_ARC,
                    episodeCandidate,
                    model
                )
                if (episodeAttempt.first == CompressionAttempt.SUCCESS) {
                    state = episodeAttempt.second
                    continue
                }
                rejectedEpisodeCandidate = episodeCandidateIds
            }

            val arcCandidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                nodes = state.arcPage.activeNodeIds.mapNotNull(nodes::get),
                expectedTier = MemoryTier.ARC,
                timeline = state.timeline,
                gaps = state.gaps
            )
            val arcCandidateIds = arcCandidate?.candidates?.map { it.id }
            if (arcCandidate != null && arcCandidateIds != rejectedArcCandidate) {
                val arcDecision = requireDecisionIfNeeded(state, MemoryDecisionTier.ARC, limitChars)
                if (arcDecision != null) {
                    persistDecision(sessionId, arcDecision)
                    return MaintenanceResult.DecisionRequired(arcDecision)
                }
                val arcAttempt = compressLowerTier(
                    sessionId,
                    state,
                    nodes,
                    MemoryCompressionKind.ARC_TO_ERA,
                    arcCandidate,
                    model
                )
                if (arcAttempt.first == CompressionAttempt.SUCCESS) {
                    state = arcAttempt.second
                    continue
                }
                rejectedArcCandidate = arcCandidateIds
            }

            val eraCandidate = MemoryCompressionPolicy.eraCandidate(
                nodes = state.eraPage.activeNodeIds.mapNotNull(nodes::get),
                timeline = state.timeline,
                gaps = state.gaps
            ) ?: error("长期记忆压缩失败：Episode、Arc均不可压缩，且可用Era少于3条")
            val eraDecision = requireDecisionIfNeeded(state, MemoryDecisionTier.ERA, limitChars)
            if (eraDecision != null) {
                persistDecision(sessionId, eraDecision)
                return MaintenanceResult.DecisionRequired(eraDecision)
            }
            val eraAttempt = compressEra(sessionId, state, nodes, eraCandidate, model)
            if (eraAttempt.first != CompressionAttempt.SUCCESS) {
                error("长期记忆压缩失败：Episode、Arc均不可压缩，且可用Era少于3条")
            }
            state = eraAttempt.second
        }
        return MaintenanceResult.Ready(state, nodes)
    }

    private fun requireDecisionIfNeeded(
        state: MemorySessionState,
        tier: MemoryDecisionTier,
        limitChars: Int
    ): MemorySessionState? {
        if (limitChars >= MAX_MEMORY_LIMIT_CHARS) return null
        val needsPrompt = when (tier) {
            MemoryDecisionTier.EPISODE -> !state.episodeCompressionPromptDeclined
            MemoryDecisionTier.ARC -> !state.arcCompressionPromptDeclined
            MemoryDecisionTier.ERA -> !state.eraCompressionPromptDeclined ||
                state.eraCompressionsSincePrompt >= 5
        }
        if (!needsPrompt) return null
        return state.copy(
            pendingDecision = PendingMemoryDecision(tier),
            revision = state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun persistDecision(sessionId: String, state: MemorySessionState) {
        stateLock(sessionId) {
            val current = loadLocked(sessionId)
            check(current.state.revision + 1 == state.revision || current.state.revision == state.revision) {
                "扩容决策提交前版本已变化"
            }
            memoryRepository.saveState(state)
            setArchiveStatusLocked(
                current.session,
                MemoryUpdateStatus.LIMIT_DECISION_REQUIRED,
                null
            )
        }
    }

    private suspend fun compressLowerTier(
        sessionId: String,
        state: MemorySessionState,
        nodes: MutableMap<String, MemoryNode>,
        kind: MemoryCompressionKind,
        candidate: MemoryCompressionCandidate,
        model: ModelConfig
    ): Pair<CompressionAttempt, MemorySessionState> {
        val response = ai.compression(
            model = model,
            kind = kind,
            forcedConsumedChildIds = emptyList(),
            renderedChildren = renderChildren(candidate.candidates, state.timeline)
        ) { output ->
            if (!output.compressible) {
                check(output.consumedChildIds.isEmpty() && output.childCoverage.isEmpty() && output.summary.isBlank()) {
                    "compressible=false时不得携带压缩内容"
                }
            } else {
                validateCompressionResponse(output, candidate)
                val consumed = candidate.candidates.take(output.consumedChildIds.size)
                val outputChars = output.childCoverage.sumOf { it.text.trim().length } +
                    (output.childCoverage.size - 1).coerceAtLeast(0)
                check(outputChars < consumed.sumOf { it.body.length }) { "压缩后正文没有缩短" }
            }
        }
        if (!response.compressible) return CompressionAttempt.NOT_COMPRESSIBLE to state
        val consumed = candidate.candidates.take(response.consumedChildIds.size)
        val parentTier = if (kind == MemoryCompressionKind.EPISODE_TO_ARC) MemoryTier.ARC else MemoryTier.ERA
        val parent = createParentNode(sessionId, parentTier, consumed, response)
        check(parent.body.length < consumed.sumOf { it.body.length }) { "压缩后正文没有缩短" }
        requireValidNode(parent, nodes + (parent.id to parent), state)
        val next = commitCompression(sessionId, state, nodes, kind, consumed, parent, model.id)
        return CompressionAttempt.SUCCESS to next
    }

    private suspend fun compressEra(
        sessionId: String,
        state: MemorySessionState,
        nodes: MutableMap<String, MemoryNode>,
        candidate: MemoryCompressionCandidate,
        model: ModelConfig
    ): Pair<CompressionAttempt, MemorySessionState> {
        val forced = MemoryCompressionPolicy.forcedEraPrefix(candidate).map { it.id }
        check(forced.size in MemoryCompressionPolicy.ERA_MIN_CONSUME..MemoryCompressionPolicy.ERA_MAX_CONSUME) {
            "Era进一步压缩程序候选数量不合法"
        }
        val response = ai.compression(
            model = model,
            kind = MemoryCompressionKind.ERA_TO_ERA,
            forcedConsumedChildIds = forced,
            renderedChildren = renderChildren(candidate.candidates, state.timeline)
        ) { output ->
            check(output.compressible) { "Era进一步压缩不可返回false" }
            check(output.consumedChildIds == forced) { "Era进一步压缩必须消费程序指定child" }
            validateCompressionResponse(
                output,
                candidate.copy(maxConsume = forced.size, minConsume = forced.size)
            )
            val consumed = candidate.candidates.take(forced.size)
            val outputChars = output.childCoverage.sumOf { it.text.trim().length } +
                (output.childCoverage.size - 1).coerceAtLeast(0)
            check(outputChars < consumed.sumOf { it.body.length }) { "Era压缩后正文没有缩短" }
        }
        val consumed = candidate.candidates.take(forced.size)
        val parent = createParentNode(sessionId, MemoryTier.ERA, consumed, response)
        check(parent.body.length < consumed.sumOf { it.body.length }) { "Era压缩后正文没有缩短" }
        requireValidNode(parent, nodes + (parent.id to parent), state)
        val committed = commitCompression(
            sessionId,
            state,
            nodes,
            MemoryCompressionKind.ERA_TO_ERA,
            consumed,
            parent,
            model.id
        )
        val next = committed.copy(
            eraCompressionsSincePrompt = state.eraCompressionsSincePrompt + 1,
            revision = committed.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(next)
        return CompressionAttempt.SUCCESS to next
    }

    private suspend fun commitCompression(
        sessionId: String,
        initialState: MemorySessionState,
        nodes: MutableMap<String, MemoryNode>,
        kind: MemoryCompressionKind,
        consumed: List<MemoryNode>,
        parent: MemoryNode,
        modelId: String
    ): MemorySessionState {
        val current = stateLock(sessionId) { loadLocked(sessionId) }
        check(current.state.revision == initialState.revision) { "压缩提交前版本已变化" }
        val sourceTier = consumed.first().tier
        val targetTier = parent.tier
        val transactionId = MemoryCompressionTransaction.newId()
        memoryRepository.saveNodes(listOf(parent))
        nodes[parent.id] = parent

        val sourceAfter = initialState.page(sourceTier).activeNodeIds.filterNot { id ->
            consumed.any { it.id == id }
        }
        if (sourceTier == targetTier) {
            val after = MemoryTimelinePolicy.sortNodes(
                (sourceAfter + parent.id).mapNotNull(nodes::get),
                initialState.timeline
            ).map { it.id }
            val checkpoint = revisions.checkpoint(
                initialState = initialState,
                tier = sourceTier,
                afterNodeIds = after,
                operation = MemoryRevisionOperation.COMPRESSION_TARGET,
                author = MemoryAuthor.AI,
                modelId = modelId,
                transactionId = transactionId,
                affectedSourceTurnIds = parent.sourceTurnIds
            )
            memoryRepository.saveTransaction(
                MemoryCompressionTransaction(
                    id = transactionId,
                    sessionId = sessionId,
                    kind = kind,
                    sourceTier = sourceTier,
                    targetTier = targetTier,
                    consumedNodeIds = consumed.map { it.id },
                    resultNodeId = parent.id,
                    sourceRevisionId = checkpoint.revision.id,
                    targetRevisionId = checkpoint.revision.id
                )
            )
            val event = MemoryCompressionEvent(
                id = MemoryCompressionEvent.newId(),
                transactionId = transactionId,
                kind = kind,
                consumedCount = consumed.size
            )
            val next = checkpoint.state.copy(
                pendingCompressionEvents = checkpoint.state.pendingCompressionEvents + event,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveState(next)
            return next
        }
        val sourceCheckpoint = revisions.checkpoint(
            initialState = initialState,
            tier = sourceTier,
            afterNodeIds = sourceAfter,
            operation = MemoryRevisionOperation.COMPRESSION_SOURCE,
            author = MemoryAuthor.AI,
            modelId = modelId,
            transactionId = transactionId,
            affectedSourceTurnIds = parent.sourceTurnIds
        )
        val targetBefore = sourceCheckpoint.state.page(targetTier).activeNodeIds
        val targetAfter = MemoryTimelinePolicy.sortNodes(
            (targetBefore + parent.id).mapNotNull(nodes::get),
            sourceCheckpoint.state.timeline
        ).map { it.id }
        val targetCheckpoint = revisions.checkpoint(
            initialState = sourceCheckpoint.state,
            tier = targetTier,
            afterNodeIds = targetAfter,
            operation = MemoryRevisionOperation.COMPRESSION_TARGET,
            author = MemoryAuthor.AI,
            modelId = modelId,
            transactionId = transactionId,
            affectedSourceTurnIds = parent.sourceTurnIds
        )
        memoryRepository.saveTransaction(
            MemoryCompressionTransaction(
                id = transactionId,
                sessionId = sessionId,
                kind = kind,
                sourceTier = sourceTier,
                targetTier = targetTier,
                consumedNodeIds = consumed.map { it.id },
                resultNodeId = parent.id,
                sourceRevisionId = sourceCheckpoint.revision.id,
                targetRevisionId = targetCheckpoint.revision.id
            )
        )
        val event = MemoryCompressionEvent(
            id = MemoryCompressionEvent.newId(),
            transactionId = transactionId,
            kind = kind,
            consumedCount = consumed.size
        )
        val next = targetCheckpoint.state.copy(
            pendingCompressionEvents = targetCheckpoint.state.pendingCompressionEvents + event,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(next)
        return next
    }

    // ===== 迁移、编译与辅助 =====

    private suspend fun loadLocked(
        sessionId: String,
        discoverUntrackedArchived: Boolean = false
    ): LoadedMemory {
        val messages = chatRepository.ensureSourceTurns(sessionId)
        var session = chatRepository.getSession(sessionId) ?: error("会话不存在")
        val contextWindowSize = currentContextWindowSize()
        val normalizedLimit = MemoryBudgetPolicy.normalizedLimit(session.memoryLimitChars)
        if (normalizedLimit != session.memoryLimitChars) {
            session = session.copy(memoryLimitChars = normalizedLimit)
            chatRepository.updateSession(session)
        }
        var state = memoryRepository.getState(sessionId)
        if (state == null || state.schemaVersion < CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION) {
            state = migrateLegacyLocked(session, messages, contextWindowSize)
            session = chatRepository.getSession(sessionId) ?: session
        } else {
            val reconciled = reconcileState(state, messages, session)
            if (reconciled != state) {
                memoryRepository.saveState(reconciled)
                state = reconciled
            }
        }
        val stateBeforeOrderRepair = checkNotNull(state)
        val nodes = memoryRepository.getReachableNodes(
            stateBeforeOrderRepair.activeNodeIds + stateBeforeOrderRepair.legacyReferenceNodeIds
        ).associateBy { it.id }.toMutableMap()
        val orderReconciled = MemoryPageOrderPolicy.normalize(stateBeforeOrderRepair, nodes)
        if (orderReconciled != stateBeforeOrderRepair) {
            var repaired = stateBeforeOrderRepair
            listOf(
                orderReconciled.episodePage,
                orderReconciled.arcPage,
                orderReconciled.eraPage
            ).forEach { orderedPage ->
                val currentPage = repaired.page(orderedPage.tier)
                if (currentPage.activeNodeIds == orderedPage.activeNodeIds) return@forEach
                val repairRevision = MemoryTierRevision(
                    id = MemoryTierRevision.newId(),
                    sessionId = stateBeforeOrderRepair.sessionId,
                    tier = orderedPage.tier,
                    parentRevisionId = currentPage.currentRevisionId,
                    operation = MemoryRevisionOperation.MIGRATE,
                    author = MemoryAuthor.MIGRATION,
                    snapshotNodeIds = orderedPage.activeNodeIds,
                    visible = false
                )
                memoryRepository.saveRevision(repairRevision)
                repaired = repaired.replacePage(
                    currentPage.copy(
                        activeNodeIds = orderedPage.activeNodeIds,
                        currentRevisionId = repairRevision.id,
                        uncheckpointedAddedNodeIds = emptyList(),
                        revisionSequence = currentPage.revisionSequence + 1
                    )
                )
            }
            state = repaired.copy(
                revision = stateBeforeOrderRepair.revision + 1,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveState(state)
        }
        val boundaryReconciled = reconcileBackfillBoundary(
            state = state,
            messages = messages,
            contextWindowSize = contextWindowSize,
            nodes = nodes,
            discoverUntrackedArchived = discoverUntrackedArchived
        )
        if (boundaryReconciled != state) {
            memoryRepository.saveState(boundaryReconciled)
            state = boundaryReconciled
        }
        val refreshed = refreshSourceStaleness(state, nodes, messages, session)
        if (refreshed != state) {
            memoryRepository.saveState(refreshed)
            state = refreshed
        }
        return LoadedMemory(session, messages, state, nodes)
    }

    private suspend fun migrateLegacyLocked(
        session: ChatSession,
        messages: List<ChatMessage>,
        contextWindowSize: Int
    ): MemorySessionState {
        val timeline = sourceTimeline(messages, session)
        val legacyCommits = memoryRepository.legacyHistory(session.id)
        val oldNodes = memoryRepository.getNodesForSession(session.id).associateBy { it.id }
        val converted = mutableMapOf<String, MemoryNode>()
        val converting = mutableSetOf<String>()
        val legacyIds = mutableListOf<String>()
        val legacyTurnMap = messages.filter { it.role != MessageRole.SYSTEM }
            .mapNotNull { message ->
                val oldT = message.timelineTurn ?: message.sourceTurnOrder ?: return@mapNotNull null
                val sourceId = message.sourceTurnId ?: return@mapNotNull null
                oldT to sourceId
            }.toMap()

        fun legacyReference(old: MemoryNode, reason: String): MemoryNode = MemoryNode(
            id = MemoryNode.newId(),
            sessionId = session.id,
            tier = MemoryTier.LEGACY_REFERENCE,
            coverageUnits = listOf(
                MemoryCoverageUnit(
                    sourceId = "legacy:${old.id}",
                    text = "时间未知，不代表当前进展：${old.body.ifBlank { "旧节点无法读取正文" }}"
                )
            ),
            overview = reason,
            author = MemoryAuthor.MIGRATION
        ).also { legacyIds += it.id }

        fun convert(id: String): MemoryNode {
            converted[id]?.let { return it }
            if (!converting.add(id)) {
                return legacyReference(
                    MemoryNode(id, session.id, MemoryTier.LEGACY_REFERENCE),
                    "旧节点存在循环引用"
                ).also { converted[id] = it }
            }
            val old = oldNodes[id] ?: return legacyReference(
                MemoryNode(id, session.id, MemoryTier.LEGACY_REFERENCE),
                "旧版本缺少节点"
            ).also {
                converted[id] = it
                converting.remove(id)
            }
            if (old.tier == MemoryTier.LEGACY_REFERENCE) {
                return legacyReference(old, "旧版参考").also {
                    converted[id] = it
                    converting.remove(id)
                }
            }
            if (old.coverageUnits.isNotEmpty() &&
                MemoryTimelinePolicy.isContinuous(old.sourceTurnIds, timeline)
            ) {
                return old.also {
                    converted[id] = it
                    converting.remove(id)
                }
            }

            val result = if (old.tier == MemoryTier.EPISODE) {
                val ids = old.sourceTurns.mapNotNull { legacyTurnMap[it.timelineTurn] }
                if (ids.size != old.sourceTurns.size || old.sourceTurns.any { it.coverageText.isBlank() }) {
                    legacyReference(old, "旧Episode缺少逐轮coverage")
                } else {
                    val units = old.sourceTurns.zip(ids).map { (source, sourceId) ->
                        MemoryCoverageUnit(sourceId, source.coverageText)
                    }
                    val hashes = old.sourceTurns.zip(ids).associate { (source, sourceId) ->
                        sourceId to source.sourceHash
                    }
                    old.copy(
                        id = MemoryNode.newId(),
                        sourceTurnIds = ids,
                        coverageUnits = units,
                        overview = old.summary,
                        sourceHashes = hashes,
                        sourceHash = MemoryHashes.sourceIds(ids, ids.map { hashes.getValue(it) }),
                        coverageHash = MemoryHashes.coverageUnits(units),
                        author = MemoryAuthor.MIGRATION
                    )
                }
            } else {
                val childPairs = old.childIds.map { childId -> childId to convert(childId) }
                val children = childPairs.map { it.second }
                val proofByOldChildId = old.childCoverage.associateBy { it.childId }
                val hasExactProof = old.childIds.isNotEmpty() &&
                    old.childCoverage.size == old.childIds.size &&
                    proofByOldChildId.size == old.childIds.size &&
                    old.childIds.all { childId ->
                        proofByOldChildId[childId]?.coverageText?.isNotBlank() == true
                    }
                if (children.any { it.tier == MemoryTier.LEGACY_REFERENCE } || !hasExactProof) {
                    legacyReference(old, "旧父节点无法证明完整child覆盖")
                } else {
                    val units = childPairs.map { (oldChildId, child) ->
                        MemoryCoverageUnit(
                            child.id,
                            proofByOldChildId.getValue(oldChildId).coverageText
                        )
                    }
                    old.copy(
                        id = MemoryNode.newId(),
                        sourceTurnIds = children.flatMap { it.sourceTurnIds },
                        childIds = children.map { it.id },
                        coverageUnits = units,
                        compressionLevel = if (
                            old.tier == MemoryTier.ERA && children.all { it.tier == MemoryTier.ERA }
                        ) {
                            children.maxOf { it.compressionLevel } + 1
                        } else {
                            0
                        },
                        overview = old.summary,
                        sourceHashes = children.flatMap { it.sourceHashes.entries }
                            .associate { it.key to it.value },
                        sourceHash = MemoryHashes.text(
                            children.joinToString("\n") { "${it.id}:${it.sourceHash}" }
                        ),
                        coverageHash = MemoryHashes.parentCoverage(children, units),
                        author = MemoryAuthor.MIGRATION
                    )
                }
            }
            converted[id] = result
            converting.remove(id)
            return result
        }

        var state = MemorySessionState(
            sessionId = session.id,
            timeline = timeline,
            gapRetentionVersion = CURRENT_MEMORY_GAP_RETENTION_VERSION
        )
        if (legacyCommits.isNotEmpty()) {
            var previous = mapOf(
                MemoryTier.EPISODE to emptyList<String>(),
                MemoryTier.ARC to emptyList(),
                MemoryTier.ERA to emptyList()
            )
            legacyCommits.forEach { commit ->
                val byTier = mapOf(
                    MemoryTier.EPISODE to commit.activeEpisodeNodeIds.map { convert(it).id }
                        .filterNot { it in legacyIds },
                    MemoryTier.ARC to commit.activeArcNodeIds.map { convert(it).id }
                        .filterNot { it in legacyIds },
                    MemoryTier.ERA to commit.activeEraNodeIds.map { convert(it).id }
                        .filterNot { it in legacyIds }
                )
                byTier.forEach { (tier, ids) ->
                    if (ids != previous.getValue(tier)) {
                        var page = state.page(tier)
                        if (page.currentRevisionId == null) {
                            val baseline = MemoryTierRevision(
                                id = MemoryTierRevision.newId(),
                                sessionId = session.id,
                                tier = tier,
                                operation = MemoryRevisionOperation.PURE_APPEND_SYNC,
                                author = MemoryAuthor.MIGRATION,
                                snapshotNodeIds = previous.getValue(tier),
                                visible = false,
                                createdAt = (commit.createdAt - 1).coerceAtLeast(0)
                            )
                            memoryRepository.saveRevision(baseline)
                            state = state.replacePage(
                                page.copy(
                                    currentRevisionId = baseline.id,
                                    revisionSequence = page.revisionSequence + 1
                                )
                            )
                            page = state.page(tier)
                        }
                        val visible = !(tier == MemoryTier.EPISODE &&
                            commit.operation == MemoryCommitOperation.EPISODE_UPDATE &&
                            previous.getValue(tier).all { it in ids })
                        val revision = MemoryTierRevision(
                            id = MemoryTierRevision.newId(),
                            sessionId = session.id,
                            tier = tier,
                            parentRevisionId = page.currentRevisionId,
                            operation = MemoryRevisionOperation.MIGRATE,
                            author = commit.author,
                            modelId = commit.modelId,
                            addedNodeIds = ids.filterNot { it in previous.getValue(tier) },
                            removedNodeIds = previous.getValue(tier).filterNot { it in ids },
                            snapshotNodeIds = ids.takeIf { page.currentRevisionId == null },
                            visible = visible,
                            createdAt = commit.createdAt
                        )
                        memoryRepository.saveRevision(revision)
                        state = state.replacePage(
                            MemoryPageState(
                                tier = tier,
                                activeNodeIds = ids,
                                currentRevisionId = revision.id,
                                revisionSequence = page.revisionSequence + 1
                            )
                        )
                    }
                }
                previous = byTier
                val throughSourceId = commit.head.throughSourceTurnId
                    ?: commit.head.throughT?.let(legacyTurnMap::get)
                state = state.copy(
                    head = commit.head.copy(
                        throughSourceTurnId = throughSourceId,
                        throughT = null,
                        version = state.head.version + 1
                    ),
                    legacyReferenceNodeIds = (
                        state.legacyReferenceNodeIds +
                            commit.legacyReferenceNodeIds.map { convert(it).id } +
                            legacyIds
                        ).distinct()
                )
            }
        } else if (session.longTermMemory.isNotBlank()) {
            val sourceId = session.longTermMemoryUpdatedThroughMessageId
                ?.let { id -> messages.firstOrNull { it.id == id }?.sourceTurnId }
            val legacy = MemoryNode(
                id = MemoryNode.newId(),
                sessionId = session.id,
                tier = MemoryTier.LEGACY_REFERENCE,
                sourceTurnIds = sourceId?.let(::listOf).orEmpty(),
                coverageUnits = listOf(
                    MemoryCoverageUnit(
                        "legacy:${session.id}",
                        if (sourceId == null) {
                            "时间未知，不代表当前进展：${session.longTermMemory}"
                        } else {
                            session.longTermMemory
                        }
                    )
                ),
                overview = "旧版长期记忆",
                author = MemoryAuthor.MIGRATION
            )
            converted[legacy.id] = legacy
            legacyIds += legacy.id
            val archived = completeArchivedSourceTurnIds(messages, contextWindowSize)
            state = state.copy(
                legacyReferenceNodeIds = listOf(legacy.id),
                gaps = archived.takeIf { it.isNotEmpty() }?.let { sourceIds ->
                    listOf(
                        MemoryGap(
                            id = MemoryGap.newId(),
                            sourceTurnIds = sourceIds,
                            reason = MemoryGapReason.LEGACY_UNKNOWN
                        )
                    )
                }.orEmpty(),
                backfill = if (archived.isEmpty()) MemoryBackfillState() else MemoryBackfillState(
                    status = MemoryBackfillStatus.PAUSED,
                    pendingSourceTurnIds = archived
                )
            )
        } else {
            val archived = completeArchivedSourceTurnIds(messages, contextWindowSize)
            if (archived.isNotEmpty()) {
                val gap = MemoryGap(
                    id = MemoryGap.newId(),
                    sourceTurnIds = archived,
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
                state = state.copy(
                    gaps = listOf(gap),
                    backfill = MemoryBackfillState(
                        status = MemoryBackfillStatus.PAUSED,
                        pendingSourceTurnIds = archived
                    )
                )
            }
        }

        if (legacyIds.isNotEmpty() && state.gaps.isEmpty()) {
            val convertedByNewId = converted.values.associateBy { it.id }
            val covered = state.activeNodeIds.mapNotNull(convertedByNewId::get)
                .flatMapTo(mutableSetOf()) { it.sourceTurnIds }
            val missing = completeArchivedSourceTurnIds(messages, contextWindowSize)
                .filterNot { it in covered }
            if (missing.isNotEmpty()) {
                state = state.copy(
                    gaps = listOf(
                        MemoryGap(
                            id = MemoryGap.newId(),
                            sourceTurnIds = missing,
                            reason = MemoryGapReason.LEGACY_UNKNOWN
                        )
                    ),
                    backfill = MemoryBackfillState(
                        status = MemoryBackfillStatus.PAUSED,
                        pendingSourceTurnIds = missing
                    )
                )
            }
        }
        if (converted.isNotEmpty()) memoryRepository.saveNodes(converted.values.toList())
        state = state.copy(
            schemaVersion = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION,
            legacyReferenceNodeIds = (state.legacyReferenceNodeIds + legacyIds).distinct(),
            revision = 1,
            updatedAt = System.currentTimeMillis()
        )
        memoryRepository.saveState(state)
        chatRepository.updateSession(
            session.copy(
                memoryStateRevision = state.revision,
                memoryHeadCommitId = null,
                memoryUpdateStatus = MemoryUpdateStatus.IDLE,
                memoryArchiveStatus = MemoryUpdateStatus.IDLE,
                memoryHeadStatus = MemoryUpdateStatus.IDLE,
                longTermMemorySchemaVersion = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION
            )
        )
        return state
    }

    private fun reconcileState(
        state: MemorySessionState,
        messages: List<ChatMessage>,
        session: ChatSession
    ): MemorySessionState {
        val known = state.timeline.mapTo(mutableSetOf()) { it.sourceTurnId }
        val watermark = state.recordingStartsAfterSourceOrder
        val newEntries = sourceTimeline(messages, session)
            .filter { watermark == null || it.sourceOrder > watermark }
            .filterNot { it.sourceTurnId in known }
        val appendable = newEntries.sortedBy { it.sourceOrder }.mapIndexed { index, entry ->
            entry.copy(displayT = (state.timeline.maxOfOrNull { it.displayT } ?: -1) + index + 1)
        }
        val pausedBackfill = MemoryBackfillPolicy.pauseOrphanedRun(
            backfill = state.backfill,
            hasActiveRunner = session.id in activeBackfillSessionIds
        )
        val existingGapSourceIds = state.gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val deletedSourceIds = session.sourceTurnTombstones
            .filter { watermark == null || it.sourceOrder > watermark }
            .map { it.sourceTurnId }
            .filterNot(existingGapSourceIds::contains)
        val deletionGaps = deletedSourceIds.map { sourceId ->
            val order = session.sourceTurnTombstones.first { it.sourceTurnId == sourceId }.sourceOrder
            MemoryGap(
                id = MemoryGap.newId(),
                sourceTurnIds = listOf(sourceId),
                startSourceOrder = order,
                endSourceOrder = order,
                reason = MemoryGapReason.DELETED_SOURCE
            )
        }
        val unavailable = session.sourceTurnTombstones.mapTo(mutableSetOf()) { it.sourceTurnId }
        val reconciledBackfill = pausedBackfill.copy(
            pendingSourceTurnIds = pausedBackfill.pendingSourceTurnIds.filterNot(unavailable::contains)
        )
        return state.copy(
            timeline = state.timeline + appendable,
            gaps = state.gaps + deletionGaps,
            pendingSourceTurnIds = state.pendingSourceTurnIds.filterNot(unavailable::contains),
            backfill = reconciledBackfill,
            memoryWasEnabled = session.longTermMemoryEnabled,
            revision = if (
                appendable.isNotEmpty() || deletionGaps.isNotEmpty() ||
                reconciledBackfill != state.backfill ||
                state.pendingSourceTurnIds.any(unavailable::contains)
            ) {
                state.revision + 1
            } else {
                state.revision
            },
            updatedAt = if (
                appendable.isNotEmpty() || deletionGaps.isNotEmpty() ||
                reconciledBackfill != state.backfill ||
                state.pendingSourceTurnIds.any(unavailable::contains)
            ) {
                System.currentTimeMillis()
            } else {
                state.updatedAt
            }
        )
    }

    private fun refreshSourceStaleness(
        state: MemorySessionState,
        nodes: Map<String, MemoryNode>,
        messages: List<ChatMessage>,
        session: ChatSession
    ): MemorySessionState {
        val currentHashes = sourceTimeline(messages, session).associate { entry ->
            entry.sourceTurnId to sourceHash(entry.sourceTurnId, messages, session)
        }
        val stale = state.activeNodeIds.mapNotNull { id ->
            val node = nodes[id] ?: return@mapNotNull null
            val changed = node.sourceHashes.mapNotNull { (sourceId, storedHash) ->
                sourceId.takeIf { currentHashes[sourceId] != storedHash }
            }
            id.takeIf { changed.isNotEmpty() }?.let { it to changed }
        }.toMap()
        val headStale = if (state.head.sourceHashes.isNotEmpty()) {
            state.head.sourceHashes.any { (sourceId, storedHash) ->
                currentHashes[sourceId] != storedHash
            }
        } else {
            state.head.throughSourceTurnId?.let { throughId ->
                val throughOrder = state.timeline.firstOrNull { it.sourceTurnId == throughId }?.sourceOrder
                stale.values.flatten().any { sourceId ->
                    val order = state.timeline.firstOrNull { it.sourceTurnId == sourceId }?.sourceOrder
                    order != null && throughOrder != null && order <= throughOrder
                }
            } ?: false
        }
        if (stale == state.staleSourcesByNodeId && headStale == state.head.stale) return state
        return state.copy(
            staleSourcesByNodeId = stale,
            head = state.head.copy(stale = headStale),
            revision = state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun renderArchive(loaded: LoadedMemory): String {
        return MemoryArchiveInjectionPolicy.render(
            activeNodes = loaded.state.activeNodeIds.mapNotNull(loaded.nodes::get),
            legacyReferenceNodes = loaded.state.legacyReferenceNodeIds.mapNotNull(loaded.nodes::get),
            timeline = loaded.state.timeline
        )
    }

    private fun headPathCrossesGap(loaded: LoadedMemory, targetSourceTurnId: String): Boolean {
        val targetOrder = sourceOrder(targetSourceTurnId, loaded.messages, loaded.session)
        val previousOrder = loaded.state.head.throughSourceTurnId?.let { sourceId ->
            sourceOrder(sourceId, loaded.messages, loaded.session)
        } ?: Long.MIN_VALUE
        return loaded.state.gaps.any { gap ->
            gap.sourceTurnIds.any { sourceId ->
                val order = sourceOrder(sourceId, loaded.messages, loaded.session)
                order > previousOrder && order <= targetOrder
            }
        }
    }

    private suspend fun compileAndCacheLocked(
        loaded: LoadedMemory,
        archiveStatus: MemoryUpdateStatus? = null,
        archiveError: String? = null,
        headStatus: MemoryUpdateStatus? = null,
        headError: String? = null
    ): MemoryPromptView {
        val state = loaded.state
        val active = MemoryTimelinePolicy.sortNodes(
            state.activeNodeIds.mapNotNull(loaded.nodes::get),
            state.timeline
        )
        val legacyReferences = state.legacyReferenceNodeIds.mapNotNull(loaded.nodes::get)
        val archive = MemoryArchiveInjectionPolicy.render(
            activeNodes = active,
            legacyReferenceNodes = legacyReferences,
            timeline = state.timeline
        )
        val archiveThroughT = active.mapNotNull {
            MemoryTimelinePolicy.verifiedRange(it, state.timeline)?.endT
        }
            .maxOrNull()
        val stableIds = stableSourceTurnIds(loaded.messages)
        val latestStableT = stableIds.lastOrNull()
            ?.let { MemoryTimelinePolicy.displayT(it, state.timeline) }
            ?: state.timeline.maxOfOrNull { it.displayT }
            ?: 0L
        val headPresent = state.head.render().isNotBlank()
        val hasHistoricalMemory = state.activeNodeIds.isNotEmpty() ||
            state.legacyReferenceNodeIds.isNotEmpty() ||
            state.gaps.isNotEmpty()
        val headBackfillRequired = MemoryHeadUpdatePolicy.requiresBackfill(
            hasHeadContent = headPresent,
            throughSourceTurnId = state.head.throughSourceTurnId,
            stableSourceTurnIds = stableIds,
            hasHistoricalMemory = hasHistoricalMemory
        ) || MemoryHeadUpdatePolicy.baselineSourceTurnId(stableIds)?.let { targetId ->
            headPathCrossesGap(loaded, targetId)
        } == true
        val headInitializationPending = !headPresent && !headBackfillRequired
        val headThroughT = MemoryTimelinePolicy.displayT(
            state.head.throughSourceTurnId,
            state.timeline
        )
        val activeRanges = active.mapNotNull { MemoryTimelinePolicy.verifiedRange(it, state.timeline) }
            .sortedBy { it.startT }
        val archiveRangeUnverifiable = active.any {
            it.body.isNotBlank() && MemoryTimelinePolicy.verifiedRange(it, state.timeline) == null
        } || legacyReferences.any { it.body.isNotBlank() }
        val archiveIsContinuousFromZero = activeRanges.firstOrNull()?.startT == 0L &&
            activeRanges.zipWithNext().all { (left, right) -> right.startT == left.endT + 1 }
        val archiveLabel = if (archiveIsContinuousFromZero) {
            "Archive T0-T$archiveThroughT"
        } else {
            "Archive最大T T$archiveThroughT"
        }
        val hasGapAfterArchive = archiveThroughT != null && state.gaps.any { gap ->
            gap.sourceTurnIds.any { sourceId ->
                val t = MemoryTimelinePolicy.displayT(sourceId, state.timeline)
                t != null && t > archiveThroughT && t <= latestStableT
            }
        }
        val directLabel = PromptTemplates.memoryTimelineDirectLabel(
            archivePresent = archive.isNotBlank(),
            archiveRangeUnverifiable = archiveRangeUnverifiable,
            archiveLabel = archiveLabel,
            archiveThroughT = archiveThroughT,
            hasGapAfterArchive = hasGapAfterArchive,
            latestStableT = latestStableT
        )
        val headAndTimeline = buildString {
            if (headPresent && !headBackfillRequired && headThroughT != null) {
                appendLine("【HEAD｜当前状态｜截至 T$headThroughT】")
                appendLine(state.head.render())
            }
            appendLine("【时间线约束】")
            appendLine(directLabel)
            append(PromptTemplates.MEMORY_TIMELINE_CONTRACT.trim())
        }
        val full = buildString {
            if (archive.isNotBlank()) appendLine(archive)
            append(headAndTimeline)
        }
        val integrity = MemoryIntegrityAudit.warnings(state, loaded.nodes)
            .filterNot { it.message.contains("未生成长期记忆") }
        val missingMemoryWarnings = displayTRanges(
            backfillableSourceTurnIds(loaded, currentContextWindowSize()),
            state.timeline
        )
            .map { range ->
                MemoryIntegrityWarning(
                    "T${range.first}-T${range.last}未生成长期记忆，推荐一键补录。",
                    emptyList()
                )
            }
        val warnings = buildList {
            addAll(integrity)
            addAll(missingMemoryWarnings)
            if (state.staleSourcesByNodeId.isNotEmpty()) add(
                MemoryIntegrityWarning("部分来源已修改，旧摘要仍会注入，等待人工校正。", state.staleSourcesByNodeId.keys.toList())
            )
            if (MemoryBudgetPolicy.isOverLimit(state, loaded.nodes, loaded.session.memoryLimitChars)) add(
                MemoryIntegrityWarning("人工版本超过当前自动预算，将在下一稳定剧情轮尝试维护。", emptyList())
            )
            if (headBackfillRequired) add(
                MemoryIntegrityWarning("当前状态未生成或已落后，推荐一键补录长期记忆。", emptyList())
            )
        }.distinctBy { it.message }
        val view = MemoryPromptView(
            archive = archive,
            headAndTimeline = headAndTimeline,
            fullText = full,
            archiveThroughT = archiveThroughT,
            latestStableT = latestStableT,
            effectiveBudgetChars = loaded.session.memoryLimitChars,
            usedArchiveChars = MemoryBudgetPolicy.archiveChars(state, loaded.nodes),
            displayTBySourceTurnId = state.timeline.associate {
                it.sourceTurnId to it.displayT
            },
            pendingSourceTurnIds = state.pendingSourceTurnIds.toSet(),
            headPresent = headPresent,
            headInitializationPending = headInitializationPending,
            headBackfillRequired = headBackfillRequired,
            warnings = warnings
        )
        val currentSession = chatRepository.getSession(loaded.session.id) ?: loaded.session
        chatRepository.updateSession(
            currentSession.copy(
                longTermMemory = full,
                memoryStateRevision = state.revision,
                memoryArchiveStatus = archiveStatus ?: currentSession.memoryArchiveStatus,
                memoryArchiveError = if (archiveStatus != null) archiveError else currentSession.memoryArchiveError,
                memoryHeadStatus = headStatus ?: currentSession.memoryHeadStatus,
                memoryHeadError = if (headStatus != null) headError else currentSession.memoryHeadError,
                memoryUpdateStatus = archiveStatus ?: currentSession.memoryArchiveStatus,
                memoryUpdateError = if (archiveStatus != null) archiveError else currentSession.memoryArchiveError,
                longTermMemorySchemaVersion = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION
            )
        )
        return view
    }

    private suspend fun setArchiveStatusLocked(
        session: ChatSession,
        status: MemoryUpdateStatus,
        error: String?
    ) {
        val current = chatRepository.getSession(session.id) ?: session
        chatRepository.updateSession(
            current.copy(
                memoryArchiveStatus = status,
                memoryArchiveError = error,
                memoryUpdateStatus = status,
                memoryUpdateError = error
            )
        )
    }

    private suspend fun setHeadStatusLocked(
        session: ChatSession,
        status: MemoryUpdateStatus,
        error: String?
    ) {
        val current = chatRepository.getSession(session.id) ?: session
        chatRepository.updateSession(
            current.copy(memoryHeadStatus = status, memoryHeadError = error)
        )
    }

    private suspend fun persistStateIfCurrent(
        state: MemorySessionState,
        expectedPreviousRevision: Long
    ) = stateLock(state.sessionId) {
        val current = memoryRepository.getState(state.sessionId) ?: error("记忆状态不存在")
        check(current.revision == expectedPreviousRevision) { "记忆状态已变化" }
        memoryRepository.saveState(state)
    }

    private fun validateEpisodeResponse(response: EpisodeResponse, sourceTurnCount: Int) {
        val summary = response.summary.trim()
        check(summary.isNotBlank()) { "Episode summary为空" }
        val maxChars = MemoryEpisodeSummaryPolicy.maxChars(sourceTurnCount)
        check(MemoryEpisodeSummaryPolicy.characterCount(summary) <= maxChars) {
            "Episode summary超过${maxChars}字上限"
        }
        check(MemorySummaryPolicy.hasOnlyQualifiedStateWords(summary)) {
            "Episode包含无T限定的现在/目前/仍然"
        }
    }

    private fun validateCompressionResponse(
        response: CompressionResponse,
        candidate: MemoryCompressionCandidate
    ) {
        val prefix = MemoryCompressionPolicy.validateConsumedPrefix(candidate, response.consumedChildIds)
        check(prefix.valid) { prefix.error.orEmpty() }
        check(response.summary.isNotBlank()) { "压缩summary为空" }
        check(response.childCoverage.map { it.childId } == response.consumedChildIds) {
            "压缩未逐child同序覆盖"
        }
        check(response.childCoverage.all { it.text.isNotBlank() }) { "childCoverage为空" }
        response.childCoverage.forEach { unit ->
            check(MemorySummaryPolicy.hasOnlyQualifiedStateWords(unit.text)) {
                "压缩正文包含无T限定的现在/目前/仍然"
            }
        }
    }

    private fun buildNodeRegenerationRequest(
        loaded: LoadedMemory,
        nodeId: String
    ): MemoryNodeRegenerationRequest {
        val node = loaded.nodes[nodeId] ?: error("记忆节点不存在")
        check(nodeId in loaded.state.page(node.tier).activeNodeIds) { "只能重新生成当前分页活跃节点" }
        val plan = MemoryNodeRegenerationPolicy.plan(node, loaded.nodes)
        val renderedEvidence = if (node.tier == MemoryTier.EPISODE) {
            val availableSourceIds = loaded.messages.asSequence()
                .filter { it.role != MessageRole.SYSTEM }
                .mapNotNull { it.sourceTurnId }
                .toSet()
            check(node.sourceTurnIds.all { it in availableSourceIds }) {
                "原始聊天轮已删除，无法重新生成Episode"
            }
            renderSourceTurns(
                node.sourceTurnIds,
                loaded.messages,
                loaded.state.timeline,
                loaded.session
            )
        } else {
            renderChildren(plan.children, loaded.state.timeline)
        }
        val evidenceHash = if (node.tier == MemoryTier.EPISODE) {
            MemoryHashes.text(node.sourceTurnIds.joinToString("\n") { sourceId ->
                "$sourceId:${sourceHash(sourceId, loaded.messages, loaded.session)}"
            })
        } else {
            MemoryHashes.text(plan.children.joinToString("\n") { child ->
                "${child.id}:${child.sourceHash}:${child.coverageHash}:${MemoryHashes.text(child.body)}"
            })
        }
        return MemoryNodeRegenerationRequest(
            plan = plan,
            renderedEvidence = renderedEvidence,
            evidenceHash = evidenceHash
        )
    }

    private fun createEpisodeNode(
        sessionId: String,
        sourceRefs: List<com.example.chatbar.data.local.entity.MemorySourceTurnRef>,
        response: EpisodeResponse
    ): MemoryNode {
        val hashes = sourceRefs.associate { it.sourceTurnId to it.sourceHash }
        val content = response.summary.trim()
        val sourceTurnIds = sourceRefs.map { it.sourceTurnId }
        return MemoryNode(
            id = MemoryNode.newId(),
            sessionId = sessionId,
            tier = MemoryTier.EPISODE,
            sourceTurnIds = sourceTurnIds,
            coverageUnits = emptyList(),
            content = content,
            overview = content,
            sourceHash = MemoryHashes.sourceRefs(sourceRefs),
            sourceHashes = hashes,
            coverageHash = MemoryHashes.episodeCoverage(sourceTurnIds, hashes, content),
            author = MemoryAuthor.AI
        )
    }

    private fun createParentNode(
        sessionId: String,
        tier: MemoryTier,
        children: List<MemoryNode>,
        response: CompressionResponse
    ): MemoryNode {
        val units = response.childCoverage.map { MemoryCoverageUnit(it.childId, it.text.trim()) }
        return MemoryNode(
            id = MemoryNode.newId(),
            sessionId = sessionId,
            tier = tier,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id },
            coverageUnits = units,
            compressionLevel = if (tier == MemoryTier.ERA && children.all { it.tier == MemoryTier.ERA }) {
                children.maxOf { it.compressionLevel } + 1
            } else {
                0
            },
            content = response.summary.trim(),
            overview = response.summary.trim(),
            sourceHash = MemoryHashes.text(children.joinToString("\n") { "${it.id}:${it.sourceHash}" }),
            sourceHashes = children.flatMap { it.sourceHashes.entries }.associate { it.key to it.value },
            coverageHash = MemoryHashes.parentCoverage(children, units),
            author = MemoryAuthor.AI
        )
    }

    private fun requireValidNode(
        node: MemoryNode,
        nodes: Map<String, MemoryNode>,
        state: MemorySessionState
    ) {
        val validation = MemoryContinuityPolicy.validateNode(
            root = node,
            nodesById = nodes,
            timeline = state.timeline,
            gaps = state.gaps
        )
        check(validation.valid) { validation.error ?: "记忆节点覆盖校验失败" }
    }

    private fun nextEpisodeBatch(
        state: MemorySessionState,
        messages: List<ChatMessage>,
        maxTurns: Int,
        forcePartial: Boolean
    ): List<String> = nextSourceBatch(
        pending = state.pendingSourceTurnIds,
        state = state,
        messages = messages,
        maxTurns = maxTurns,
        forcePartial = forcePartial
    )

    private fun nextSourceBatch(
        pending: List<String>,
        state: MemorySessionState,
        messages: List<ChatMessage>,
        maxTurns: Int,
        forcePartial: Boolean
    ): List<String> {
        val sortedPending = sortSourceIds(pending, messages, null)
        if (sortedPending.isEmpty()) return emptyList()
        val firstSegment = sortedPending.takeWhileIndexed { index, sourceId ->
            if (index == 0) true else {
                val previous = sortedPending[index - 1]
                areSourceTurnsAdjacent(previous, sourceId, state.timeline)
            }
        }
        val lastT = firstSegment.lastOrNull()
            ?.let { MemoryTimelinePolicy.displayT(it, state.timeline) }
        val endsAtGap = lastT != null && state.gaps
            .flatMap { it.sourceTurnIds }
            .any { gapSourceId ->
                MemoryTimelinePolicy.displayT(gapSourceId, state.timeline) == lastT + 1
            }
        val endsAtPendingBoundary = firstSegment.size < sortedPending.size
        return when {
            firstSegment.size >= maxTurns -> firstSegment.take(maxTurns)
            forcePartial || endsAtGap || endsAtPendingBoundary -> firstSegment
            else -> emptyList()
        }
    }

    private fun List<String>.takeWhileIndexed(predicate: (Int, String) -> Boolean): List<String> {
        val result = mutableListOf<String>()
        forEachIndexed { index, value ->
            if (!predicate(index, value)) return result
            result += value
        }
        return result
    }

    private fun areSourceTurnsAdjacent(
        left: String,
        right: String,
        timeline: List<MemoryTimelineEntry>
    ): Boolean {
        val byId = timeline.associateBy { it.sourceTurnId }
        val leftT = byId[left]?.displayT ?: return false
        val rightT = byId[right]?.displayT ?: return false
        return rightT == leftT + 1
    }

    private fun completeArchivedSourceTurnIds(messages: List<ChatMessage>, windowSize: Int): List<String> {
        val archived = contextWindowManager.getMessagesToArchive(messages, windowSize)
        val archivedIds = archived.mapTo(mutableSetOf()) { it.id }
        return messages.filter { it.role != MessageRole.SYSTEM && it.sourceTurnId != null }
            .groupBy { it.sourceTurnId!! }
            .filterValues { turnMessages ->
                turnMessages.any { it.role == MessageRole.ASSISTANT } &&
                    turnMessages.all { it.id in archivedIds }
            }
            .keys
            .sortedBy { id -> messages.first { it.sourceTurnId == id }.sourceTurnOrder }
    }

    private fun stableSourceTurnIds(messages: List<ChatMessage>): List<String> = messages
        .filter { it.role != MessageRole.SYSTEM && it.sourceTurnId != null }
        .sortedWith(ChatMessage.TimelineComparator)
        .groupBy { it.sourceTurnId!! }
        .filterValues { turnMessages -> turnMessages.any { it.role == MessageRole.ASSISTANT } }
        .keys
        .toList()

    private fun sourceTimeline(
        messages: List<ChatMessage>,
        session: ChatSession
    ): List<MemoryTimelineEntry> {
        val messageEntries = messages.filter { it.role != MessageRole.SYSTEM }
            .mapNotNull { message ->
                val id = message.sourceTurnId ?: return@mapNotNull null
                val order = message.sourceTurnOrder ?: return@mapNotNull null
                MemoryTimelineEntry(id, order, 0)
            }
        val tombstones = session.sourceTurnTombstones.map {
            MemoryTimelineEntry(it.sourceTurnId, it.sourceOrder, 0, tombstone = true)
        }
        return MemoryTimelinePolicy.normalize(messageEntries + tombstones)
    }

    private fun sourceRefs(
        sourceIds: List<String>,
        messages: List<ChatMessage>,
        session: ChatSession
    ): List<com.example.chatbar.data.local.entity.MemorySourceTurnRef> = sourceIds.map { id ->
        val sourceMessages = messages.filter { it.sourceTurnId == id && it.role != MessageRole.SYSTEM }
        check(sourceMessages.isNotEmpty()) { "source turn $id 已删除或没有有效原始来源" }
        com.example.chatbar.data.local.entity.MemorySourceTurnRef(
            sourceTurnId = id,
            sourceOrder = sourceMessages.first().sourceTurnOrder
                ?: error("source turn $id 缺少稳定顺序"),
            messageIds = sourceMessages.map { it.id },
            sourceHash = sourceHash(id, messages, session)
        )
    }

    private fun sourceHash(
        sourceTurnId: String,
        messages: List<ChatMessage>,
        session: ChatSession
    ): String {
        val sourceMessages = messages.filter { it.sourceTurnId == sourceTurnId && it.role != MessageRole.SYSTEM }
        if (sourceMessages.isEmpty()) {
            val tombstone = session.sourceTurnTombstones.firstOrNull { it.sourceTurnId == sourceTurnId }
            return MemoryHashes.text("$sourceTurnId:tombstone:${tombstone?.sourceOrder}")
        }
        return MemoryHashes.text(sourceMessages.joinToString("\n") { message ->
            "${message.id}:${message.sourceTurnOrder}:${message.orderKey}:${message.updatedAt}:" +
                "${message.displayContent}:${message.images.joinToString(",")}"
        })
    }

    private fun renderSourceTurns(
        sourceIds: List<String>,
        messages: List<ChatMessage>,
        timeline: List<MemoryTimelineEntry>,
        session: ChatSession
    ): String = sourceIds.joinToString("\n\n") { id ->
        val displayT = MemoryTimelinePolicy.displayT(id, timeline)
        val content = messages.filter { it.sourceTurnId == id && it.role != MessageRole.SYSTEM }
            .joinToString("\n") { message ->
                val role = if (message.role == MessageRole.USER) "用户" else "AI"
                val body = message.displayContent.ifBlank {
                    if (message.images.isNotEmpty()) "[图片]" else "（空）"
                }
                "$role：$body"
            }
        "[sourceTurnId=$id｜T${displayT ?: "?"}]\n${content.ifBlank { "[已删除轮次 tombstone]" }}"
    }

    private fun renderChildren(
        children: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>
    ): String = children.joinToString("\n\n") { child ->
        val range = MemoryTimelinePolicy.range(child, timeline)
        "[childId=${child.id}｜${child.tier.displayName()}｜T${range?.startT ?: "?"}-T${range?.endT ?: "?"}]\n${child.body}"
    }

    private fun backfillableSourceTurnIds(
        loaded: LoadedMemory,
        contextWindowSize: Int
    ): List<String> {
        val availableSourceIds = loaded.messages.mapNotNull { it.sourceTurnId }.toSet()
        val archivedSourceIds = completeArchivedSourceTurnIds(
            loaded.messages,
            contextWindowSize
        ).toSet()
        val archivedEligible = MemoryBackfillPolicy.eligibleSourceTurnIds(
            activeNodes = loaded.state.activeNodeIds.mapNotNull(loaded.nodes::get),
            gaps = loaded.state.gaps,
            timeline = loaded.state.timeline,
            availableSourceTurnIds = availableSourceIds,
            archivedSourceTurnIds = archivedSourceIds
        )
        val disabledEligible = loaded.state.gaps
            .filter { it.reason == MemoryGapReason.DISABLED }
            .flatMap { it.sourceTurnIds }
            .filter { it in availableSourceIds }
        return sortSourceIds(
            archivedEligible + disabledEligible,
            loaded.messages,
            loaded.session
        )
    }

    private fun reconcileBackfillBoundary(
        state: MemorySessionState,
        messages: List<ChatMessage>,
        contextWindowSize: Int,
        nodes: Map<String, MemoryNode>,
        discoverUntrackedArchived: Boolean = false
    ): MemorySessionState {
        val availableSourceIds = messages.mapNotNullTo(mutableSetOf()) { it.sourceTurnId }
        val archivedSourceIds = completeArchivedSourceTurnIds(messages, contextWindowSize).toSet()
        val coveredSourceIds = state.activeNodeIds.mapNotNull(nodes::get)
            .flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val boundary = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = state.gaps,
            backfill = state.backfill,
            timeline = state.timeline,
            availableSourceTurnIds = availableSourceIds,
            archivedSourceTurnIds = archivedSourceIds,
            coveredSourceTurnIds = coveredSourceIds,
            normalPendingSourceTurnIds = state.pendingSourceTurnIds.toSet(),
            recordingStartsAfterSourceOrder = state.recordingStartsAfterSourceOrder,
            discoverUntrackedArchived = discoverUntrackedArchived ||
                state.gapRetentionVersion < CURRENT_MEMORY_GAP_RETENTION_VERSION
        )
        val gapRetentionVersion = if (boundary.recoveryCompleted) {
            CURRENT_MEMORY_GAP_RETENTION_VERSION
        } else {
            state.gapRetentionVersion
        }
        if (
            boundary.gaps == state.gaps &&
            boundary.backfill == state.backfill &&
            gapRetentionVersion == state.gapRetentionVersion
        ) {
            return state
        }
        return state.copy(
            gaps = boundary.gaps,
            backfill = boundary.backfill.copy(updatedAt = System.currentTimeMillis()),
            gapRetentionVersion = gapRetentionVersion,
            revision = state.revision + 1,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun currentContextWindowSize(): Int =
        settingsRepository.getAppSettings().defaultContextWindowSize.coerceAtLeast(1)

    private fun displayTRanges(
        sourceTurnIds: List<String>,
        timeline: List<MemoryTimelineEntry>
    ): List<LongRange> {
        val displayTs = timeline
            .filter { it.sourceTurnId in sourceTurnIds }
            .map { it.displayT }
            .distinct()
            .sorted()
        if (displayTs.isEmpty()) return emptyList()
        val ranges = mutableListOf<LongRange>()
        var start = displayTs.first()
        var end = start
        displayTs.drop(1).forEach { value ->
            if (value == end + 1) {
                end = value
            } else {
                ranges.add(start..end)
                start = value
                end = value
            }
        }
        ranges.add(start..end)
        return ranges
    }

    private fun sortSourceIds(
        ids: List<String>,
        messages: List<ChatMessage>,
        session: ChatSession?
    ): List<String> = ids.distinct().sortedBy { id ->
        messages.firstOrNull { it.sourceTurnId == id }?.sourceTurnOrder
            ?: session?.sourceTurnTombstones?.firstOrNull { it.sourceTurnId == id }?.sourceOrder
            ?: Long.MAX_VALUE
    }

    private fun sourceOrder(
        sourceId: String,
        messages: List<ChatMessage>,
        session: ChatSession
    ): Long = messages.firstOrNull { it.sourceTurnId == sourceId }?.sourceTurnOrder
        ?: session.sourceTurnTombstones.firstOrNull { it.sourceTurnId == sourceId }?.sourceOrder
        ?: error("source turn没有绝对顺序")

    private fun reachableNodes(
        state: MemorySessionState,
        nodes: Map<String, MemoryNode>
    ): List<MemoryNode> {
        val result = linkedMapOf<String, MemoryNode>()
        fun visit(id: String) {
            if (id in result) return
            val node = nodes[id] ?: return
            result[id] = node
            node.childIds.forEach(::visit)
        }
        (state.activeNodeIds + state.legacyReferenceNodeIds).forEach(::visit)
        return result.values.toList()
    }

    /** 将v2草稿SaveSlot转换为v4快照；coverage不完整时只保留为Legacy Reference。 */
    private fun migrateLegacySaveSlotSnapshot(
        snapshot: MemorySnapshot,
        loaded: LoadedMemory,
        contextWindowSize: Int
    ): MemorySnapshot {
        val commit = snapshot.legacyCommit ?: return snapshot.copy(
            state = MemorySessionSnapshot(
                timeline = sourceTimeline(loaded.messages, loaded.session),
                memoryWasEnabled = loaded.session.longTermMemoryEnabled
            )
        )
        val oldById = snapshot.nodes.associateBy { it.id }
        val converted = mutableMapOf<String, MemoryNode>()
        val converting = mutableSetOf<String>()
        val legacyIds = mutableListOf<String>()
        val sourceIdByLegacyT = loaded.messages.asSequence()
            .filter { it.role != MessageRole.SYSTEM }
            .mapNotNull { message ->
                val legacyT = message.timelineTurn ?: message.sourceTurnOrder ?: return@mapNotNull null
                val sourceId = message.sourceTurnId ?: return@mapNotNull null
                legacyT to sourceId
            }
            .toMap()
        val timeline = sourceTimeline(loaded.messages, loaded.session)

        fun legacyReference(old: MemoryNode?, reason: String, oldId: String): MemoryNode {
            val mappedIds = old?.sourceTurns
                ?.mapNotNull { sourceIdByLegacyT[it.timelineTurn] }
                ?.takeIf { ids -> ids.size == old.sourceTurns.size }
                ?: old?.sourceTurnIds.orEmpty().filter { sourceId ->
                    loaded.messages.any { it.sourceTurnId == sourceId }
                }
            val raw = old?.summary.orEmpty().ifBlank { old?.overview.orEmpty() }
                .ifBlank { old?.body.orEmpty() }
                .ifBlank { "旧节点无法读取正文" }
            return MemoryNode(
                id = MemoryNode.newId(),
                sessionId = loaded.session.id,
                tier = MemoryTier.LEGACY_REFERENCE,
                sourceTurnIds = mappedIds,
                coverageUnits = listOf(
                    MemoryCoverageUnit(
                        sourceId = "legacy:$oldId",
                        text = if (mappedIds.isEmpty()) {
                            "时间未知，不代表当前进展：$raw"
                        } else {
                            raw
                        }
                    )
                ),
                overview = reason,
                author = MemoryAuthor.MIGRATION
            ).also { legacyIds += it.id }
        }

        fun convert(oldId: String): MemoryNode {
            converted[oldId]?.let { return it }
            if (!converting.add(oldId)) {
                return legacyReference(oldById[oldId], "旧节点存在循环引用", oldId)
                    .also { converted[oldId] = it }
            }
            val old = oldById[oldId]
            if (old == null) {
                return legacyReference(null, "旧版本缺少节点", oldId).also {
                    converted[oldId] = it
                    converting.remove(oldId)
                }
            }
            if (old.tier == MemoryTier.LEGACY_REFERENCE) {
                return legacyReference(old, old.overview.ifBlank { "旧版参考" }, oldId).also {
                    converted[oldId] = it
                    converting.remove(oldId)
                }
            }
            if (old.coverageUnits.isNotEmpty() &&
                MemoryTimelinePolicy.isContinuous(old.sourceTurnIds, timeline)
            ) {
                return old.also {
                    converted[oldId] = it
                    converting.remove(oldId)
                }
            }

            val result = if (old.tier == MemoryTier.EPISODE) {
                val sourceIds = old.sourceTurns.mapNotNull { sourceIdByLegacyT[it.timelineTurn] }
                if (sourceIds.size != old.sourceTurns.size ||
                    old.sourceTurns.isEmpty() ||
                    old.sourceTurns.any { it.coverageText.isBlank() }
                ) {
                    legacyReference(old, "旧Episode缺少逐轮coverage", oldId)
                } else {
                    val units = old.sourceTurns.zip(sourceIds).map { (source, sourceId) ->
                        MemoryCoverageUnit(sourceId, source.coverageText.trim())
                    }
                    val hashes = old.sourceTurns.zip(sourceIds).associate { (source, sourceId) ->
                        sourceId to source.sourceHash.ifBlank {
                            sourceHash(sourceId, loaded.messages, loaded.session)
                        }
                    }
                    old.copy(
                        id = MemoryNode.newId(),
                        sessionId = loaded.session.id,
                        sourceTurnIds = sourceIds,
                        childIds = emptyList(),
                        coverageUnits = units,
                        overview = old.summary,
                        sourceHashes = hashes,
                        sourceHash = MemoryHashes.sourceIds(
                            sourceIds,
                            sourceIds.map(hashes::getValue)
                        ),
                        coverageHash = MemoryHashes.coverageUnits(units),
                        author = MemoryAuthor.MIGRATION
                    )
                }
            } else {
                val childPairs = old.childIds.map { childId -> childId to convert(childId) }
                val children = childPairs.map { it.second }
                val proofByOldChildId = old.childCoverage.associateBy { it.childId }
                val hasExactProof = old.childIds.isNotEmpty() &&
                    old.childCoverage.size == old.childIds.size &&
                    proofByOldChildId.size == old.childIds.size &&
                    old.childIds.all { childId ->
                        proofByOldChildId[childId]?.coverageText?.isNotBlank() == true
                    }
                if (children.any { it.tier == MemoryTier.LEGACY_REFERENCE } || !hasExactProof) {
                    legacyReference(old, "旧父节点无法证明完整child覆盖", oldId)
                } else {
                    val units = childPairs.map { (oldChildId, child) ->
                        MemoryCoverageUnit(
                            sourceId = child.id,
                            text = proofByOldChildId.getValue(oldChildId).coverageText.trim()
                        )
                    }
                    old.copy(
                        id = MemoryNode.newId(),
                        sessionId = loaded.session.id,
                        sourceTurnIds = children.flatMap { it.sourceTurnIds },
                        childIds = children.map { it.id },
                        coverageUnits = units,
                        compressionLevel = if (
                            old.tier == MemoryTier.ERA && children.all { it.tier == MemoryTier.ERA }
                        ) {
                            children.maxOf { it.compressionLevel } + 1
                        } else {
                            0
                        },
                        overview = old.summary,
                        sourceHashes = children.flatMap { it.sourceHashes.entries }
                            .associate { it.key to it.value },
                        sourceHash = MemoryHashes.text(
                            children.joinToString("\n") { "${it.id}:${it.sourceHash}" }
                        ),
                        coverageHash = MemoryHashes.parentCoverage(children, units),
                        author = MemoryAuthor.MIGRATION
                    )
                }
            }
            converted[oldId] = result
            converting.remove(oldId)
            return result
        }

        fun active(ids: List<String>, tier: MemoryTier): List<String> = ids.map(::convert)
            .filter { it.tier == tier }
            .map { it.id }

        val episodeIds = active(commit.activeEpisodeNodeIds, MemoryTier.EPISODE)
        val arcIds = active(commit.activeArcNodeIds, MemoryTier.ARC)
        val eraIds = active(commit.activeEraNodeIds, MemoryTier.ERA)
        commit.legacyReferenceNodeIds.forEach(::convert)
        val allConverted = converted.values.distinctBy { it.id }
        val covered = (episodeIds + arcIds + eraIds)
            .mapNotNull { id -> allConverted.firstOrNull { it.id == id } }
            .flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val missing = completeArchivedSourceTurnIds(
            loaded.messages,
            contextWindowSize
        ).filterNot(covered::contains)
        val throughSourceId = commit.head.throughSourceTurnId
            ?: commit.head.throughT?.let(sourceIdByLegacyT::get)
        return MemorySnapshot(
            state = MemorySessionSnapshot(
                episodeNodeIds = episodeIds,
                arcNodeIds = arcIds,
                eraNodeIds = eraIds,
                legacyReferenceNodeIds = (
                    legacyIds + allConverted.filter { it.tier == MemoryTier.LEGACY_REFERENCE }.map { it.id }
                    ).distinct(),
                head = commit.head.copy(
                    throughSourceTurnId = throughSourceId,
                    throughT = null,
                    version = commit.head.version.coerceAtLeast(1)
                ),
                timeline = timeline,
                gaps = missing.takeIf { it.isNotEmpty() }?.let { sourceIds ->
                    listOf(
                        MemoryGap(
                            id = MemoryGap.newId(),
                            sourceTurnIds = sourceIds,
                            reason = MemoryGapReason.LEGACY_UNKNOWN
                        )
                    )
                }.orEmpty(),
                backfill = if (missing.isEmpty()) MemoryBackfillState() else MemoryBackfillState(
                    status = MemoryBackfillStatus.PAUSED,
                    pendingSourceTurnIds = missing
                ),
                memoryWasEnabled = loaded.session.longTermMemoryEnabled
            ),
            nodes = allConverted,
            legacyCommit = null
        )
    }

    private fun validateImportedSnapshot(
        snapshot: MemorySnapshot,
        state: MemorySessionSnapshot
    ) {
        val nodes = snapshot.nodes.associateBy { it.id }
        val activeIds = state.episodeNodeIds + state.arcNodeIds + state.eraNodeIds
        check(activeIds.distinct().size == activeIds.size) { "存档活跃记忆包含重复节点ID" }
        activeIds.forEach { id ->
            val node = nodes[id] ?: error("存档活跃记忆缺少节点：$id")
            val result = MemoryContinuityPolicy.validateNode(
                root = node,
                nodesById = nodes,
                timeline = state.timeline,
                // 来源删除后旧节点仍允许注入并显示警告；Gap只阻止后续自动压缩。
                gaps = emptyList()
            )
            check(result.valid) { result.error ?: "存档记忆节点校验失败" }
        }
        state.legacyReferenceNodeIds.forEach { id ->
            check(nodes[id]?.tier == MemoryTier.LEGACY_REFERENCE) {
                "存档Legacy Reference不存在或层级错误：$id"
            }
        }
    }

    private fun MemoryTier.displayName(): String = when (this) {
        MemoryTier.EPISODE -> "Episode"
        MemoryTier.ARC -> "Arc"
        MemoryTier.ERA -> "Era"
        MemoryTier.LEGACY_REFERENCE -> "Legacy"
    }

    private suspend fun <T> stateLock(sessionId: String, block: suspend () -> T): T =
        stateMutexes.getOrPut(sessionId) { Mutex() }.withLock { block() }

    private suspend fun <T> archiveLock(sessionId: String, block: suspend () -> T): T =
        archiveMutexes.getOrPut(sessionId) { Mutex() }.withLock { block() }

    private suspend fun <T> headLock(sessionId: String, block: suspend () -> T): T =
        headMutexes.getOrPut(sessionId) { Mutex() }.withLock { block() }
}
