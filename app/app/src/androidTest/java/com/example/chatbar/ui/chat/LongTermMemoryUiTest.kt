package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
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
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.domain.memory.MemoryHashes
import com.example.chatbar.domain.memory.MemoryBackfillEstimate
import com.example.chatbar.domain.memory.MemoryBackfillPhase
import com.example.chatbar.domain.memory.MemoryBackfillProgress
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
