package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import org.junit.Test
import org.junit.Assert.*

class ChatHistoryActionProjectionTest {
    private fun message(id: String, role: MessageRole, content: String, order: Long) =
        ChatMessage(id, "s", role, content, orderKey = order, createdAt = order, updatedAt = order)
    private val options = "————————\n【行动选项】\n1. OLD_NOT_TAKEN_LEFT\n2. OLD_NOT_TAKEN_RIGHT\n————————"
    private fun known(messages: List<ChatMessage>, complete: Set<String>) = ChatHistoryActionProjection.prepare(messages, complete,
        if (messages.lastOrNull()?.role == MessageRole.USER) ChatHistoryActionRequestSource.PERSISTED_USER
        else ChatHistoryActionRequestSource.UI_CONTINUATION)

    @Test fun `ordinary explicit text removes only reliable assistant actions while user source stays exact`() {
        val a = message("a", MessageRole.ASSISTANT, "衣着：青色大衣\n$options\n```status\n位置：广场\n```", 1)
        val user = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 2)
        val p = known(listOf(a, user), setOf(a.id, user.id))
        val result = p.project(a, a.displayContent)
        assertFalse(result.contains("OLD_NOT_TAKEN")); assertTrue(result.contains("青色大衣")); assertTrue(result.contains("位置：广场"))
        assertEquals(user.displayContent, p.project(user, user.displayContent))
        assertTrue(a.displayContent.contains("OLD_NOT_TAKEN"))
    }

    @Test fun `historical unresolved references protect the earlier prefix but not later independent action sources`() {
        for (reference in listOf("1", "１", "②", "⑴，然后看看她接下来的反应。", "贰，然后看看她接下来的反应。", "选择第二项，然后观察她的反应。", "1。接着观察她会如何回答。", "就照刚才的那个行动做。", "I choose two and wait quietly.", "I will carry it out tomorrow.", "I shall proceed with them tomorrow.", "我打算照她所说的计划去做然后再回来。", "请继续生成接下来发生的故事。", "Please continue the story for a while.", "好", "unknown", "сделай это")) {
            val a = message("a", MessageRole.ASSISTANT, options, 1)
            val u = message("u", MessageRole.USER, reference, 2)
            val b = message("b", MessageRole.ASSISTANT, options.replace("OLD", "NEW"), 3)
            val current = message("current", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 4)
            val all = listOf(a, u, b, current)
            val p = known(all, all.map { it.id }.toSet())
            assertEquals(reference, a.displayContent, p.project(a, a.displayContent))
            assertFalse(reference, p.project(b, b.displayContent).contains("NEW_NOT_TAKEN"))
            assertEquals(reference, p.project(u, reference))
        }
    }

    @Test fun `missing tail proof stale candidate duplicate identity and unknown order preserve originals`() {
        val a = message("a", MessageRole.ASSISTANT, options, 1)
        val u = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 2)
        val sparse = known(listOf(a, u), setOf(u.id))
        assertEquals(options, sparse.project(a, options))
        val valid = known(listOf(a, u), setOf(a.id, u.id))
        assertEquals(options, valid.project(a.copy(updatedAt = 9), options))
        assertEquals(options, ChatHistoryActionProjection.prepare(listOf(a, u), setOf(a.id, u.id)).project(a, options))
        for (all in listOf(listOf(u, a), listOf(a, a, u), listOf(a, u.copy(sessionId = "other")))) {
            val p = known(all, all.map { it.id }.toSet())
            assertEquals(options, p.project(a, options))
        }
    }

    @Test fun `unreliable list narration code and legacy status projection keep their separate semantics`() {
        val text = "旁白与衣着：蓝色长裙\n清单\n1. 地图\n2. 水\n```text\n$options\n```\n" +
            "————————\n【行动选项】\n1. 地图\n3. 水\n————————"
        val a = message("a", MessageRole.ASSISTANT, text, 1)
        val p = known(listOf(a), setOf(a.id))
        assertEquals(text, p.project(a, text))
        val reliable = a.copy(content = "旁白\n$options\n```status\nSTATUS_STAYS\n```")
        val q = known(listOf(reliable), setOf(a.id))
        val previous = ChatHistoryPromptPolicy.sourceText(reliable, true, ChatHistoryPromptZone.PREVIOUS_TURN)
        assertTrue(q.project(reliable, previous).contains("STATUS_STAYS"))
        val earlier = ChatHistoryPromptPolicy.sourceText(reliable, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        assertFalse(q.project(reliable, earlier).contains("STATUS_STAYS"))
    }

    @Test fun `trusted regeneration without a current user may still have older users in its candidate universe`() {
        val old = message("u", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 1)
        val a = message("a", MessageRole.ASSISTANT, options, 2)
        val all = listOf(old, a)
        val control = ChatHistoryActionProjection.prepare(all, all.map { it.id }.toSet(),
            ChatHistoryActionRequestSource.NO_USER_REGENERATION)
        assertFalse(control.project(a, options).contains("OLD_NOT_TAKEN"))
        assertEquals(options, ChatHistoryActionProjection.prepare(all, all.map { it.id }.toSet()).project(a, options))
    }
}
