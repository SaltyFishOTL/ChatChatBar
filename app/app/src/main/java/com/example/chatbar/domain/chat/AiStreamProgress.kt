package com.example.chatbar.domain.chat

import com.example.chatbar.domain.prompt.AiTaskContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class AiStreamSnapshot(
    val requestId: String,
    val title: String,
    val model: String,
    val reasoning: String = "",
    val content: String = "",
    val status: String = "等待接口输出",
    val active: Boolean = true,
    val startedAtNanos: Long = System.nanoTime(),
    val lastUpdateNanos: Long = startedAtNanos
)

/** Per-operation observer; nested research/repair inherit it, unrelated tasks cannot mix output. */
class AiStreamProgress : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiStreamProgress>

    private val mutable = MutableStateFlow<List<AiStreamSnapshot>>(emptyList())
    val snapshots = mutable.asStateFlow()
    private val pending = linkedMapOf<String, AiStreamSnapshot>()
    private var lastPublish = 0L

    @Synchronized
    fun start(id: String, context: AiTaskContext?, model: String) {
        pending[id] = AiStreamSnapshot(id, context?.profile?.name ?: "AI 生成", model)
        while (pending.size > 24) pending.remove(pending.keys.first())
        publish()
    }

    @Synchronized
    fun append(id: String, reasoning: String?, content: String?) {
        val old = pending[id] ?: return
        if (reasoning.isNullOrEmpty() && content.isNullOrEmpty()) return
        pending[id] = old.copy(
            reasoning = preview(old.reasoning + reasoning.orEmpty()),
            content = preview(old.content + content.orEmpty()),
            status = if (!content.isNullOrEmpty()) "正在输出" else "思考中",
            lastUpdateNanos = System.nanoTime()
        )
        if (System.nanoTime() - lastPublish >= 100_000_000L) publish()
    }

    /** Ordinary-text preview may be replaced when the provider later chooses tool output. */
    @Synchronized
    fun replaceContent(id: String, content: String) {
        val old = pending[id] ?: return
        pending[id] = old.copy(
            content = preview(content),
            status = if (content.isNotEmpty()) "正在输出" else old.status,
            lastUpdateNanos = System.nanoTime()
        )
        if (System.nanoTime() - lastPublish >= 100_000_000L) publish()
    }

    @Synchronized
    fun finish(id: String, status: String) {
        pending[id]?.let { pending[id] = it.copy(active = false, status = status) }
        publish()
    }

    @Synchronized
    fun clear() {
        pending.clear()
        publish()
    }

    @Synchronized
    fun flush() = publish()

    private fun publish() {
        lastPublish = System.nanoTime()
        mutable.value = pending.values.toList()
    }

    private fun preview(text: String): String = if (text.length <= 32_768) text
        else "［仅展示最近内容］\n" + text.takeLast(32_000)
}
