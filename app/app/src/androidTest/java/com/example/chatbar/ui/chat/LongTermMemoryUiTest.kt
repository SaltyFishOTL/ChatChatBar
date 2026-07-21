package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemorySourceRepairState
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.domain.memory.MemoryHashes
import com.example.chatbar.domain.memory.MemoryBackfillEstimate
import com.example.chatbar.domain.memory.MemoryBackfillPhase
import com.example.chatbar.domain.memory.MemoryBackfillProgress
import com.example.chatbar.domain.memory.MemorySourceRepairPhase
import com.example.chatbar.domain.memory.MemorySourceRepairProgress
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LongTermMemoryUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tierPageShowsOneFormalBodyWithoutInternalCoverageProof() {
        val episode = episode()
        val state = uiState(
            episode,
            memoryState = memoryState().copy(
                gaps = listOf(MemoryGap("gap", listOf("s1"), reason = MemoryGapReason.DISABLED))
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryTierCurrent(
                    state,
                    MemoryTier.EPISODE
                )
            }
        }

        composeTestRule.onNodeWithText("EPISODE T0-T0").assertIsDisplayed()
        composeTestRule.onNodeWithText("episode event").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("覆盖证明（只读）：", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("从原始对话补录").fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodesWithText("跳过补录并建立当前锚点").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun missingMemoryShowsSingleTopLevelBackfillAction() {
        var started = false
        val state = uiState(episode()).copy(
            backfillEstimate = MemoryBackfillEstimate(
                missingSourceTurns = 1,
                episodeCallsMin = 1,
                episodeCallsMax = 1
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryBackfillAction(
                    state = state,
                    onStart = { started = true },
                    onPause = {}
                )
            }
        }

        composeTestRule.onNodeWithText("一键补录长期记忆").performClick()
        composeTestRule.runOnIdle { assertTrue(started) }
    }

    @Test
    fun backfillActionsDoNotExposeDebugRebuild() {
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryBackfillAction(
                    state = uiState(episode()),
                    onStart = {},
                    onPause = {}
                )
            }
        }

        assertTrue(
            composeTestRule.onAllNodesWithText("重新补录长期记忆（Debug）")
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    @Test
    fun failedBackfillShowsReasonAndRetryAction() {
        var retried = false
        val state = uiState(
            episode(),
            memoryState = memoryState().copy(
                backfill = MemoryBackfillState(
                    status = MemoryBackfillStatus.ERROR,
                    error = "补录Episode运行期间版本已变化"
                )
            )
        ).copy(
            backfillEstimate = MemoryBackfillEstimate(
                missingSourceTurns = 1,
                episodeCallsMin = 1,
                episodeCallsMax = 1
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryBackfillAction(
                    state = state,
                    onStart = { retried = true },
                    onPause = {}
                )
            }
        }

        composeTestRule.onNodeWithText(
            "补录失败：补录Episode运行期间版本已变化"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("重试补录").performClick()
        composeTestRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun runningBackfillShowsProgressAndStreamingSummary() {
        val backfill = MemoryBackfillState(
            status = MemoryBackfillStatus.RUNNING,
            pendingSourceTurnIds = listOf("s1"),
            completedSourceTurnIds = listOf("s0"),
            completedEpisodeCount = 1
        )
        val state = uiState(
            episode(),
            memoryState = memoryState().copy(backfill = backfill)
        ).copy(
            backfillProgress = MemoryBackfillProgress(
                phase = MemoryBackfillPhase.GENERATING_EPISODE,
                totalSourceTurns = 2,
                completedSourceTurns = 1,
                completedEpisodes = 1,
                currentBatchSourceTurnIds = listOf("s1"),
                currentRangeLabel = "T1-T1",
                streamingSummary = "正在流式生成的近期流程"
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryBackfillAction(state, onStart = {}, onPause = {})
            }
        }

        composeTestRule.onNodeWithText("正在生成 T1-T1").assertIsDisplayed()
        composeTestRule.onNodeWithText("已处理 1/2 轮 · 已生成 1 条近期流程").assertIsDisplayed()
        composeTestRule.onNodeWithText("正在流式生成的近期流程").assertIsDisplayed()
    }

    @Test
    fun queuedApplicationBackfillShowsRangeLoadingFeedback() {
        val state = uiState(
            episode(),
            memoryState = memoryState().copy(
                backfill = MemoryBackfillState(status = MemoryBackfillStatus.RUNNING)
            )
        ).copy(
            backfillProgress = MemoryBackfillProgress(
                phase = MemoryBackfillPhase.PREPARING,
                totalSourceTurns = 0,
                completedSourceTurns = 0,
                completedEpisodes = 0
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryBackfillAction(state, onStart = {}, onPause = {})
            }
        }

        composeTestRule.onNodeWithText("正在准备下一条近期流程").assertIsDisplayed()
        composeTestRule.onNodeWithText("正在读取待补录范围").assertIsDisplayed()
    }

    @Test
    fun failedArchiveMaintenanceExplainsRetryPurpose() {
        var retried = false
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryArchiveMaintenanceAction(
                    status = MemoryUpdateStatus.ERROR,
                    retryRunning = false,
                    onRetry = { retried = true }
                )
            }
        }

        composeTestRule.onNodeWithText("历史归档维护失败").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "重试会扫描尚未归档的旧对话，生成 Episode，并在空间不足时压缩已有记忆；不会修改聊天原文。"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("重新维护历史归档").performClick()
        composeTestRule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun retryingArchiveMaintenanceShowsImmediateProgress() {
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryArchiveMaintenanceAction(
                    status = MemoryUpdateStatus.ERROR,
                    retryRunning = true,
                    onRetry = {}
                )
            }
        }

        composeTestRule.onNodeWithText("正在维护历史归档").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "重试已提交。正在等待或调用模型生成 Episode，并在需要时压缩记忆；耗时取决于模型响应。"
        ).assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithText("重新维护历史归档").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun changedSourceShowsManualRepairAndHidesBackfillAction() {
        var started = false
        val state = uiState(
            episode(),
            memoryState = memoryState().copy(
                staleSourcesByNodeId = mapOf("episode" to listOf("s0"))
            )
        ).copy(
            backfillEstimate = MemoryBackfillEstimate(
                missingSourceTurns = 1,
                episodeCallsMin = 1,
                episodeCallsMax = 1
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                Column {
                    MemorySourceRepairAction(state, onStart = { started = true }, onPause = {})
                    MemoryBackfillAction(state, onStart = {}, onPause = {})
                }
            }
        }

        composeTestRule.onNodeWithText("修复变更后的长期记忆").performClick()
        composeTestRule.runOnIdle { assertTrue(started) }
        assertTrue(composeTestRule.onAllNodesWithText("一键补录长期记忆").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun runningSourceRepairShowsProgressStreamingSummaryAndPause() {
        var paused = false
        val state = uiState(
            episode(),
            memoryState = memoryState().copy(
                staleSourcesByNodeId = mapOf("episode" to listOf("s0")),
                sourceRepair = MemorySourceRepairState(
                    status = MemorySourceRepairStatus.RUNNING,
                    pendingRootNodeIds = listOf("episode"),
                    completedRootCount = 1,
                    totalRootCount = 2
                )
            )
        ).copy(
            sourceRepairProgress = MemorySourceRepairProgress(
                phase = MemorySourceRepairPhase.GENERATING_EPISODE,
                totalRoots = 2,
                completedRoots = 1,
                currentRootNodeId = "episode",
                currentSourceTurnIds = listOf("s0"),
                currentRangeLabel = "T0-T0",
                streamingSummary = "正在流式生成修复摘要"
            )
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemorySourceRepairAction(state, onStart = {}, onPause = { paused = true })
            }
        }

        composeTestRule.onNodeWithText("正在修复近期流程 T0-T0").assertIsDisplayed()
        composeTestRule.onNodeWithText("已处理 1/2 个受影响根节点").assertIsDisplayed()
        composeTestRule.onNodeWithText("正在流式生成修复摘要").assertIsDisplayed()
        composeTestRule.onNodeWithText("完成当前节点后暂停").performClick()
        composeTestRule.runOnIdle { assertTrue(paused) }
    }

    @Test
    fun headAndEpisodeEditSaveIndependently() {
        var savedHead: MemoryHead? = null
        val head = MemoryHead(throughSourceTurnId = "s0", location = "old")
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryHeadPage(uiState(episode(), head = head), onEditHead = { savedHead = it })
            }
        }
        composeTestRule.onNodeWithText("old").performTextReplacement("new")
        composeTestRule.onNodeWithText("保存当前状态").performClick()
        composeTestRule.runOnIdle { assertEquals("new", savedHead?.location) }

        var savedBody = ""
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryTierEditor(
                    state = uiState(episode()),
                    tier = MemoryTier.EPISODE,
                    onEditNode = { _, body -> savedBody = body },
                    onRegenerateNode = { _, _ -> Result.success("regenerated from source") },
                    onOpenNodeEditor = { _, _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithText("当前内容已保存到Checkpoint。").assertIsDisplayed()
        composeTestRule.onNodeWithText("episode event").performTextReplacement("edited event")
        composeTestRule.onNodeWithText("有未保存修改，离开此页面会丢失。").assertIsDisplayed()
        composeTestRule.onNodeWithText("保存修改到Checkpoint（未保存）").performClick()
        composeTestRule.runOnIdle { assertEquals("edited event", savedBody) }
    }

    @Test
    fun aiRegenerationWritesCandidateWithoutSavingAutomatically() {
        var requestedNodeId = ""
        var savedBody = ""
        val finishRegeneration = CompletableDeferred<Unit>()
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryTierEditor(
                    state = uiState(episode()),
                    tier = MemoryTier.EPISODE,
                    onEditNode = { _, body -> savedBody = body },
                    onRegenerateNode = { nodeId, onStreamingSummary ->
                        requestedNodeId = nodeId
                        onStreamingSummary("streaming candidate")
                        finishRegeneration.await()
                        Result.success("regenerated from original evidence")
                    },
                    onOpenNodeEditor = { _, _, _ -> }
                )
            }
        }

        composeTestRule.onNodeWithText("AI重新生成此节点").performClick()
        composeTestRule.onNodeWithText("streaming candidate").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals("episode", requestedNodeId)
            assertEquals("", savedBody)
            finishRegeneration.complete(Unit)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("regenerated from original evidence").assertIsDisplayed()
        composeTestRule.onNodeWithText("有未保存修改，离开此页面会丢失。").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals("episode", requestedNodeId)
            assertEquals("", savedBody)
        }

        composeTestRule.onNodeWithText("保存修改到Checkpoint（未保存）").performClick()
        composeTestRule.runOnIdle { assertEquals("regenerated from original evidence", savedBody) }
    }

    @Test
    fun tierHistoryShowsDiffAndRestoresWholePage() {
        var restored: String? = null
        val revision = MemoryTierRevision(
            id = "revision",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            operation = MemoryRevisionOperation.USER_EDIT,
            author = MemoryAuthor.USER
        )
        val version = MemoryVersionUi(
            revision = revision,
            diffs = listOf(MemoryNodeDiffUi("T0-T0", "before", "after")),
            isCurrent = false,
            affectedRangeLabel = "T0-T0"
        )
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryVersionHistory(
                    LongTermMemoryUiState(versionsByTier = mapOf(MemoryTier.EPISODE to listOf(version))),
                    MemoryTier.EPISODE,
                    onRestoreVersion = { restored = it }
                )
            }
        }

        composeTestRule.onNodeWithText("− before").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ after").assertIsDisplayed()
        composeTestRule.onNodeWithText("恢复此分页").performClick()
        composeTestRule.runOnIdle { assertEquals("revision", restored) }
    }

    @Test
    fun hardLimitDialogOnlyExpandsOrCompresses() {
        var increased = false
        var compressed = false
        composeTestRule.setContent {
            ChatBarTheme {
                MemoryLimitDecisionDialog(
                    currentLimitChars = 2000,
                    onDismiss = {},
                    onIncrease = { increased = true },
                    onCompress = { compressed = true }
                )
            }
        }

        composeTestRule.onNodeWithText("增加 2000 字").performClick()
        composeTestRule.runOnIdle { assertTrue(increased) }
        composeTestRule.onNodeWithText("保持上限并压缩").performClick()
        composeTestRule.runOnIdle { assertTrue(compressed) }
    }

    private fun uiState(
        episode: MemoryNode,
        head: MemoryHead = MemoryHead(throughSourceTurnId = "s0"),
        memoryState: MemorySessionState = memoryState()
    ) = LongTermMemoryUiState(
        nodes = listOf(episode),
        head = head,
        memoryState = memoryState
    )

    private fun memoryState() = MemorySessionState(
        sessionId = "session",
        episodePage = MemoryPageState(MemoryTier.EPISODE, listOf("episode")),
        timeline = listOf(MemoryTimelineEntry("s0", 0, 0), MemoryTimelineEntry("s1", 1, 1))
    )

    private fun episode(): MemoryNode {
        val units = listOf(MemoryCoverageUnit("s0", "episode event"))
        return MemoryNode(
            id = "episode",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = listOf("s0"),
            coverageUnits = units,
            sourceHashes = mapOf("s0" to "source"),
            sourceHash = "source",
            coverageHash = MemoryHashes.coverageUnits(units),
            author = MemoryAuthor.AI
        )
    }
}
