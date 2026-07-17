package com.example.chatbar.data.local.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class MemoryTier {
    EPISODE,
    ARC,
    ERA,
    LEGACY_REFERENCE
}

@Serializable
enum class MemoryAuthor {
    AI,
    USER,
    MIGRATION,
    RESTORE
}

@Serializable
enum class MemoryRevisionOperation {
    MIGRATE,
    PURE_APPEND_SYNC,
    COMPRESSION_SOURCE,
    COMPRESSION_TARGET,
    USER_EDIT,
    PRE_RESTORE_CHECKPOINT,
    RESTORE,
    LOAD_SAVE,
    SOURCE_MUTATION_REPAIR,
    DEBUG_REBUILD
}

@Serializable
enum class MemoryCompressionKind {
    EPISODE_TO_ARC,
    ARC_TO_ERA,
    ERA_TO_ERA
}

@Serializable
enum class MemoryDecisionTier {
    EPISODE,
    ARC,
    ERA
}

@Serializable
enum class MemoryBackfillStatus {
    IDLE,
    RUNNING,
    PAUSED,
    ERROR
}

@Serializable
enum class MemorySourceRepairStatus {
    IDLE,
    RUNNING,
    PAUSED,
    ERROR
}

@Serializable
enum class MemoryGapReason {
    DISABLED,
    DELETED_SOURCE,
    DECLINED_BACKFILL,
    LEGACY_UNKNOWN,
    INTERRUPTED_BACKFILL,
    DEBUG_REBUILD
}

@Serializable
data class MemoryCoverageUnit(
    /** Episode引用sourceTurnId；Arc/Era引用child node ID。 */
    val sourceId: String,
    val text: String
)

@Serializable
data class MemorySourceTurnRef(
    val sourceTurnId: String,
    val sourceOrder: Long,
    val messageIds: List<String> = emptyList(),
    val sourceHash: String
)

/**
 * 不可变压缩树节点。content是唯一正式正文；coverageUnits仅证明完整覆盖来源，
 * 不参与展示、注入或用户编辑。旧数据缺少content时由[body]兼容读取。
 */
@Serializable
data class MemoryNode(
    val id: String,
    val sessionId: String,
    val tier: MemoryTier,
    val sourceTurnIds: List<String> = emptyList(),
    val childIds: List<String> = emptyList(),
    val coverageUnits: List<MemoryCoverageUnit> = emptyList(),
    val compressionLevel: Int = 0,
    val content: String = "",
    val overview: String = "",
    val sourceHash: String = "",
    val sourceHashes: Map<String, String> = emptyMap(),
    val coverageHash: String = "",
    val staleSourceTurnIds: Set<String> = emptySet(),
    val author: MemoryAuthor = MemoryAuthor.MIGRATION,
    val createdAt: Long = System.currentTimeMillis(),

    // v2草稿兼容字段。schema v4迁移后新节点不再依赖这些字段。
    val startT: Long? = null,
    val endT: Long? = null,
    val sourceTurns: List<MemoryTurnSource> = emptyList(),
    val childCoverage: List<MemoryChildCoverage> = emptyList(),
    val summary: String = "",
    val closed: Boolean = true
) {
    val body: String
        get() = content.trim().ifBlank {
            when {
                tier == MemoryTier.LEGACY_REFERENCE -> coverageText()
                    .ifBlank { summary.trim().ifBlank { overview.trim() } }
                author == MemoryAuthor.USER -> coverageText()
                    .ifBlank { overview.trim().ifBlank { summary.trim() } }
                else -> overview.trim()
                    .ifBlank { summary.trim().ifBlank { coverageText() } }
            }
        }

    private fun coverageText(): String = coverageUnits
        .joinToString("\n") { it.text.trim() }
        .trim()

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

@Serializable
data class MemoryHead(
    val throughSourceTurnId: String? = null,
    /** 仅用于旧schema解码及AI返回核验；显示T运行时派生。 */
    val throughT: Long? = null,
    val location: String = "",
    val participants: String = "",
    val relationships: String = "",
    val goals: String = "",
    val unresolved: String = "",
    val worldState: String = "",
    /** HEAD正文实际采用过的source turn哈希；用于持久识别旧来源编辑/删除。 */
    val sourceHashes: Map<String, String> = emptyMap(),
    val version: Long = 0,
    val stale: Boolean = false
) {
    fun render(): String = buildList {
        if (location.isNotBlank()) add("位置：$location")
        if (participants.isNotBlank()) add("人物状态：$participants")
        if (relationships.isNotBlank()) add("关系：$relationships")
        if (goals.isNotBlank()) add("目标：$goals")
        if (unresolved.isNotBlank()) add("未解决：$unresolved")
        if (worldState.isNotBlank()) add("世界状态：$worldState")
    }.joinToString("\n")
}

@Serializable
data class MemoryTimelineEntry(
    val sourceTurnId: String,
    val sourceOrder: Long,
    val displayT: Long,
    val tombstone: Boolean = false
)

@Serializable
data class MemoryGap(
    val id: String,
    val sourceTurnIds: List<String> = emptyList(),
    val startSourceOrder: Long? = null,
    val endSourceOrder: Long? = null,
    val reason: MemoryGapReason,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

@Serializable
data class PendingMemoryDecision(
    val tier: MemoryDecisionTier,
    val id: String = MemoryTierRevision.newId(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class MemoryBackfillState(
    val status: MemoryBackfillStatus = MemoryBackfillStatus.IDLE,
    val pendingSourceTurnIds: List<String> = emptyList(),
    val completedSourceTurnIds: List<String> = emptyList(),
    val completedEpisodeCount: Int = 0,
    val finalTimeline: List<MemoryTimelineEntry> = emptyList(),
    val estimatedEpisodeCallsMin: Int = 0,
    val estimatedEpisodeCallsMax: Int = 0,
    val capturedEpisodeMaxSourceTurns: Int = 2,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MemorySourceRepairState(
    val status: MemorySourceRepairStatus = MemorySourceRepairStatus.IDLE,
    /** 固定本轮待修活跃根；每个根成功后立即移除并持久化。 */
    val pendingRootNodeIds: List<String> = emptyList(),
    val completedRootCount: Int = 0,
    val totalRootCount: Int = 0,
    /** Archive修复后是否还需从当前Archive重建HEAD。 */
    val repairHead: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MemoryCompressionEvent(
    val id: String,
    val transactionId: String,
    val kind: MemoryCompressionKind,
    val consumedCount: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

@Serializable
data class MemoryPageState(
    val tier: MemoryTier,
    val activeNodeIds: List<String> = emptyList(),
    val currentRevisionId: String? = null,
    /** 纯新增暂存；下次可见Checkpoint前写入隐藏revision。 */
    val uncheckpointedAddedNodeIds: List<String> = emptyList(),
    /** 本分页已提交revision数量；用于周期物化，不扫描完整历史。 */
    val revisionSequence: Long = 0
)

@Serializable
data class MemorySessionState(
    val sessionId: String,
    val schemaVersion: Int = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION,
    val episodePage: MemoryPageState = MemoryPageState(MemoryTier.EPISODE),
    val arcPage: MemoryPageState = MemoryPageState(MemoryTier.ARC),
    val eraPage: MemoryPageState = MemoryPageState(MemoryTier.ERA),
    val legacyReferenceNodeIds: List<String> = emptyList(),
    val head: MemoryHead = MemoryHead(),
    val timeline: List<MemoryTimelineEntry> = emptyList(),
    val gaps: List<MemoryGap> = emptyList(),
    val staleSourcesByNodeId: Map<String, List<String>> = emptyMap(),
    val pendingSourceTurnIds: List<String> = emptyList(),
    val episodeCompressionPromptDeclined: Boolean = false,
    val arcCompressionPromptDeclined: Boolean = false,
    val eraCompressionPromptDeclined: Boolean = false,
    val eraCompressionsSincePrompt: Int = 0,
    val pendingDecision: PendingMemoryDecision? = null,
    val backfill: MemoryBackfillState = MemoryBackfillState(),
    val sourceRepair: MemorySourceRepairState = MemorySourceRepairState(),
    /** UI消费后删除；SaveSlot不携带。 */
    val pendingCompressionEvents: List<MemoryCompressionEvent> = emptyList(),
    val memoryWasEnabled: Boolean = true,
    /** 禁用瞬间最后一个稳定source turn；重新启用时据此生成禁用期Gap。 */
    val disabledAfterSourceOrder: Long? = null,
    /** 用户永久清空后，旧source turn不得在后台自动重新进入pending。 */
    val recordingStartsAfterSourceOrder: Long? = null,
    /** 旧版曾按上下文边界误删Gap；0表示仍需执行一次安全修复。 */
    val gapRetentionVersion: Int = 0,
    val revision: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun page(tier: MemoryTier): MemoryPageState = when (tier) {
        MemoryTier.EPISODE -> episodePage
        MemoryTier.ARC -> arcPage
        MemoryTier.ERA -> eraPage
        MemoryTier.LEGACY_REFERENCE -> error("Legacy Reference没有版本分页")
    }

    fun replacePage(page: MemoryPageState): MemorySessionState = when (page.tier) {
        MemoryTier.EPISODE -> copy(episodePage = page)
        MemoryTier.ARC -> copy(arcPage = page)
        MemoryTier.ERA -> copy(eraPage = page)
        MemoryTier.LEGACY_REFERENCE -> error("Legacy Reference没有版本分页")
    }

    val activeNodeIds: List<String>
        get() = episodePage.activeNodeIds + arcPage.activeNodeIds + eraPage.activeNodeIds
}

@Serializable
data class MemoryTierRevision(
    val id: String,
    val sessionId: String,
    val tier: MemoryTier,
    val parentRevisionId: String? = null,
    val operation: MemoryRevisionOperation,
    val author: MemoryAuthor,
    val modelId: String? = null,
    val transactionId: String? = null,
    val addedNodeIds: List<String> = emptyList(),
    val removedNodeIds: List<String> = emptyList(),
    /** 周期性物化，避免读取历史时无限递归。 */
    val snapshotNodeIds: List<String>? = null,
    val affectedSourceTurnIds: List<String> = emptyList(),
    val visible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

@Serializable
data class MemoryCompressionTransaction(
    val id: String,
    val sessionId: String,
    val kind: MemoryCompressionKind,
    val sourceTier: MemoryTier,
    val targetTier: MemoryTier,
    val consumedNodeIds: List<String>,
    val resultNodeId: String,
    val sourceRevisionId: String,
    val targetRevisionId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newId(): String = Uuid.random().toString()
    }
}

@Serializable
data class MemorySessionSnapshot(
    val episodeNodeIds: List<String> = emptyList(),
    val arcNodeIds: List<String> = emptyList(),
    val eraNodeIds: List<String> = emptyList(),
    val legacyReferenceNodeIds: List<String> = emptyList(),
    val head: MemoryHead = MemoryHead(),
    val timeline: List<MemoryTimelineEntry> = emptyList(),
    val gaps: List<MemoryGap> = emptyList(),
    val staleSourcesByNodeId: Map<String, List<String>> = emptyMap(),
    val pendingSourceTurnIds: List<String> = emptyList(),
    val episodeCompressionPromptDeclined: Boolean = false,
    val arcCompressionPromptDeclined: Boolean = false,
    val eraCompressionPromptDeclined: Boolean = false,
    val eraCompressionsSincePrompt: Int = 0,
    val pendingDecision: PendingMemoryDecision? = null,
    val memoryWasEnabled: Boolean = true,
    val disabledAfterSourceOrder: Long? = null,
    val recordingStartsAfterSourceOrder: Long? = null,
    val gapRetentionVersion: Int = 0,
    val backfill: MemoryBackfillState = MemoryBackfillState(),
    val sourceRepair: MemorySourceRepairState = MemorySourceRepairState()
)

/** SaveSlot v4只携带当前活跃快照和完整可达树，不复制版本历史。 */
@Serializable
data class MemorySnapshot(
    val schemaVersion: Int = CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION,
    val state: MemorySessionSnapshot? = null,
    val nodes: List<MemoryNode> = emptyList(),
    /** v3草稿兼容入口。 */
    @SerialName("commit") val legacyCommit: MemoryCommit? = null
)

// ===== v2草稿兼容类型；只供懒迁移读取 =====

@Serializable
enum class MemoryCommitOperation {
    MIGRATE,
    EPISODE_UPDATE,
    ARC_COMPRESSION,
    ERA_COMPRESSION,
    RECURSIVE_ERA_COMPRESSION,
    HEAD_UPDATE,
    USER_EDIT,
    RESTORE_VERSION,
    RESTORE_NODE,
    REBUILD_FROM_ORIGINAL,
    LOAD_SAVE
}

@Serializable
data class MemoryTurnSource(
    val timelineTurn: Long,
    val messageIds: List<String> = emptyList(),
    val sourceHash: String = "",
    val coverageText: String = ""
)

@Serializable
data class MemoryChildCoverage(
    val childId: String,
    val startT: Long,
    val endT: Long,
    val coverageText: String = ""
)

@Serializable
data class MemoryCommit(
    val id: String,
    val sessionId: String,
    val parentCommitId: String? = null,
    val activeEraNodeIds: List<String> = emptyList(),
    val activeArcNodeIds: List<String> = emptyList(),
    val activeEpisodeNodeIds: List<String> = emptyList(),
    val legacyReferenceNodeIds: List<String> = emptyList(),
    val head: MemoryHead = MemoryHead(),
    val operation: MemoryCommitOperation = MemoryCommitOperation.MIGRATE,
    val author: MemoryAuthor = MemoryAuthor.MIGRATION,
    val modelId: String? = null,
    val affectedStartT: Long? = null,
    val affectedEndT: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val activeNodeIds: List<String>
        get() = activeEraNodeIds + activeArcNodeIds + activeEpisodeNodeIds
}

const val CURRENT_LONG_TERM_MEMORY_SCHEMA_VERSION = 4
const val DEFAULT_MEMORY_LIMIT_CHARS = 2000
const val MAX_MEMORY_LIMIT_CHARS = 20000
const val MEMORY_LIMIT_STEP_CHARS = 2000
