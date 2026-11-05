package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class FormatCardAutoFillDraft(
    val name: String,
    val content: String,
    val userTools: List<FormatCardUserToolConfig>
)

/** Shared by the editor's entry gate and final apply gate. Never overwrites populated fields. */
object FormatCardAutoFillPolicy {
    fun canFill(formatCardId: String?, content: String, tools: List<FormatCardUserToolConfig>): Boolean =
        formatCardId == null && content.isBlank() && tools.isEmpty()

    fun prepareApply(
        formatCardId: String?,
        currentName: String,
        currentContent: String,
        currentTools: List<FormatCardUserToolConfig>,
        draft: FormatCardAutoFillDraft
    ): FormatCardAutoFillDraft {
        require(canFill(formatCardId, currentContent, currentTools)) {
            "仅可填充正文和用户工具均为空的新格式卡，当前内容已变化"
        }
        require(draft.name.isNotBlank() && draft.content.isNotBlank()) { "候选名称或正文为空" }
        FormatCardUserToolPolicy.requireValid(draft.userTools)
        return draft.copy(name = currentName.takeIf(String::isNotBlank) ?: draft.name)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object FormatCardAutoFillParser {
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    fun parse(raw: String): FormatCardAutoFillDraft {
        // Accept only a complete object or an enclosing Markdown fence, not nested fragments
        // salvaged from malformed output (which could silently discard tools).
        val text = raw.trim().let {
            if (it.startsWith("```") && it.endsWith("```") && it.contains('\n')) {
                it.substringAfter('\n').dropLast(3).trim()
            } else it
        }
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: error("候选必须是 JSON 对象")
        require(listOf("name", "content", "userTools").all(root::containsKey)) {
            "候选缺少 name、content 或 userTools"
        }
        val draft = json.decodeFromJsonElement<FormatCardAutoFillDraft>(root)
        require(draft.name.isNotBlank()) { "候选名称为空" }
        require(draft.content.isNotBlank()) { "候选正文为空" }
        FormatCardUserToolPolicy.requireValid(draft.userTools)
        return draft
    }
}

internal suspend fun collectFormatCardAutoFillText(
    events: Flow<StreamEvent>,
    onRawText: (String) -> Unit
): String {
    val raw = StringBuilder()
    var completed = false
    var lastPublishedAt = 0L
    try {
        events.collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    val now = System.nanoTime()
                    if (now - lastPublishedAt >= 80_000_000L) {
                        onRawText(raw.toString())
                        lastPublishedAt = now
                    }
                }
                is StreamEvent.Error -> throw event.asException()
                StreamEvent.Done -> completed = true
                is StreamEvent.ReasoningDelta, is StreamEvent.Usage, is StreamEvent.ContentPreview -> Unit
            }
        }
    } finally {
        onRawText(raw.toString())
    }
    check(completed) { "生成连接提前结束，未收到完成信号" }
    check(raw.isNotBlank()) { "AI 返回空内容" }
    return raw.toString()
}

class FormatCardAutoFillService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val settingsProvider: suspend () -> AppSettings
) {
    suspend fun generateStreaming(
        character: CharacterCard,
        request: String,
        requestedName: String,
        modelId: String?,
        onStatus: (String) -> Unit = {},
        onRawText: (String) -> Unit = {},
        onRepairText: (String) -> Unit = {},
        onValidationIssue: (String) -> Unit = {}
    ): FormatCardAutoFillDraft = withAiTaskRun(Dispatchers.IO) {
        val settings = settingsProvider()
        val model = if (modelId == null) {
            modelResolver.defaultChatModel(settings) ?: error("未配置可用的默认对话模型")
        } else {
            modelResolver.availableChatModels(settings).firstOrNull { it.id == modelId }
                ?: error("所选模型不可用，请重新选择")
        }
        require(model.hasConfiguredAuthentication(settings)) { "所选模型的 API 地址或认证配置不可用" }

        suspend fun stream(stage: AiTaskStage, messages: List<ChatApiMessage>, publish: (String) -> Unit): String =
            collectFormatCardAutoFillText(
                chatService.streamText(
                    taskContext = AiTaskContext(AiTaskKind.FORMAT_CARD, stage),
                    messages = messages,
                    modelConfig = model,
                    readTimeoutSeconds = 600L
                ),
                publish
            )

        onStatus("正在为「${character.name}」设计格式卡")
        val raw = stream(
            AiTaskStage.GENERATE,
            listOf(
                ChatApiMessage.text("system", PromptTemplates.formatCardAutoFillSystemPrompt()),
                ChatApiMessage.text("user", PromptTemplates.formatCardAutoFillUserPrompt(character, request, requestedName))
            ),
            onRawText
        )
        onStatus("正在检查候选格式")
        val parsed = try {
            FormatCardAutoFillParser.parse(raw)
        } catch (error: IllegalArgumentException) {
            onValidationIssue(error.message ?: "候选结构或工具配置不正确")
            null
        } catch (error: IllegalStateException) {
            onValidationIssue(error.message ?: "候选结构不正确")
            null
        }
        val draft = parsed ?: run {
            onStatus("候选校验失败，正在修复 JSON（1/1）")
            val repaired = stream(
                AiTaskStage.REPAIR,
                listOf(
                    ChatApiMessage.text("system", PromptTemplates.FORMAT_CARD_AUTO_FILL_REPAIR_PROMPT),
                    ChatApiMessage.text("user", raw)
                ),
                onRepairText
            )
            FormatCardAutoFillParser.parse(repaired)
        }
        onStatus("候选已生成，应用后仍需保存格式卡")
        draft.copy(name = requestedName.takeIf(String::isNotBlank) ?: draft.name)
    }
}
