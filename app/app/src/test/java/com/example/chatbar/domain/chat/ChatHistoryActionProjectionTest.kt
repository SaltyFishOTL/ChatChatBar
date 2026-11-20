package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import org.junit.Test
import org.junit.Assert.*

class ChatHistoryActionProjectionTest {
    @Test fun `Chinese continuation retains an offered letter adjacent to Han text`() {
        val assistant = message("assistant", MessageRole.ASSISTANT,
            "场景。\n<actions>\nA) 检查站台\nB) 问守卫\n</actions>", 1).copy(
            actionOptionsFormat = ActionOptionsFormat("<actions>", "</actions>", "", ActionOptionLabels.LETTERS),
        )
        for ((label, expected) in listOf("B" to true, "b" to true, "C" to false)) {
            val user = message("user", MessageRole.USER, "${label}保持安静并贴着墙走", 2)
            val projected = known(listOf(assistant, user), setOf(assistant.id, user.id))
                .sourceForHistory(assistant, stripStatus = false)
            assertEquals(expected, "B) 问守卫" in projected)
        }
    }
    @Test fun `nested examples stay literal and an explicit image hint retains one chosen item`() {
        val format = ActionOptionsFormat("<actions>", "</actions>", "Actions")
        val menu = "<actions>\nActions\n1. 检查站台\n2. 问守卫\n</actions>"
        val nested = "- 格式示例\n\n" + menu.lineSequence().joinToString("\n") { "  $it" }
        assertTrue(RoleplayActionOptionsPolicy.blocks(nested, format).isEmpty())
        assertEquals(nested, RoleplayActionOptionsPolicy.withoutExplicitOptions(nested, format))
        assertEquals("站台。", RoleplayActionOptionsPolicy.imageScene("站台。\n$menu", format, "").trim())
        assertEquals("站台。\n问守卫", RoleplayActionOptionsPolicy.imageScene("站台。\n$menu", format, "请画第2项"))
        assertEquals("站台。", RoleplayActionOptionsPolicy.imageScene("站台。\n$menu", format, "不要画第2项").trim())
    }

    @Test fun `repair can recognize a declared title typo without making it clickable`() {
        val format = ActionOptionsFormat("<actions>", "</actions>", "Actions")
        val draft = "<actions>\nAcitons\n1. 检查站台\n2. 问守卫\n</actions>"
        assertTrue(RoleplayActionOptionsPolicy.blocks(draft, format).isEmpty())
        assertEquals(
            RoleplayActionOptionsPolicy.blocks(draft.replace("Acitons", "Actions"), format).single().options,
            RoleplayActionOptionsPolicy.blocksBeforeRepair(draft, format).single().options,
        )
    }

    @Test fun `status filtering retains a referenced earlier menu and removes separate statuses`() {
        val original = "她拉开门。\n\n---\n行动选项\n1. 检查站台\n2. 问守卫\n3. 等下一班\n---\n\n```status\n实际位置：站里\n```"
        val assistant = message("assistant", MessageRole.ASSISTANT, original, 1)
        for (reference in listOf("Please select option 2.", "Please carry out the middle one quietly.")) {
            val user = message("user", MessageRole.USER, reference, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            val earlier = projection.sourceForHistory(assistant, stripStatus = true)
            assertTrue("2. 问守卫" in earlier)
            assertFalse("实际位置" in earlier)
            val retrievedText = projection.forRetrieval(assistant).displayContent
            assertFalse("检查站台" in retrievedText)
            assertFalse("等下一班" in retrievedText)
            assertFalse("2. 问守卫" in retrievedText)
            assertEquals(reference.startsWith("Please select"), "问守卫" in retrievedText)
            assertEquals(reference, projection.forRetrieval(user).displayContent)
        }
        val independent = message("user", MessageRole.USER, "我们安静地给窗台边的花草浇水。", 2)
        val projection = known(listOf(assistant, independent), setOf(assistant.id, independent.id))
        assertFalse("2. 问守卫" in projection.sourceForHistory(assistant, stripStatus = true))
        assertFalse("2. 问守卫" in projection.forRetrieval(assistant).displayContent)
        assertEquals(original, assistant.displayContent)
    }

    @Test fun `retrieval sends only one explicit adjacent letter selection`() {
        val format = ActionOptionsFormat("<actions>", "</actions>", "Actions", ActionOptionLabels.LETTERS)
        val assistant = message("assistant", MessageRole.ASSISTANT,
            "站台亮了。\n<actions>\nActions\nA) 检查站台\nB) 问守卫\n</actions>", 1).copy(
            actionOptionsFormat = format,
        )
        val user = message("user", MessageRole.USER, "A", 2)
        val prepared = known(listOf(assistant, user), setOf(assistant.id, user.id))
        assertTrue("B) 问守卫" in prepared.sourceForHistory(assistant, stripStatus = false))
        assertEquals("站台亮了。\n检查站台", prepared.forRetrieval(assistant).displayContent)
        val sparse = known(listOf(assistant, user), setOf(user.id))
        assertEquals("站台亮了。", sparse.forRetrieval(assistant).displayContent.trim())
    }

    @Test fun `earlier history retains fenced examples while removing actual statuses`() {
        val literal = "```text\n$options\n```"
        val quoted = "> ```status\n> 衣着：示例\n> ```"
        val listed = "- 格式示例\n\n  ```status\n  位置：示例\n  ```"
        val statuses = "```status\n真实状态\n```\n\n```text\n🕰时间：黄昏\n```"
        val body = "$literal\n\n$quoted\n\n$listed"
        val assistant = message("assistant", MessageRole.ASSISTANT, "$body\n\n$statuses", 1)
        for (content in listOf("我们安静地给窗台边的花草浇水。", "Please select option 2.")) {
            val user = message("user", MessageRole.USER, content, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            assertEquals(body, projection.sourceForHistory(assistant, stripStatus = true))
        }
    }
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
        for (reference in listOf("1", "１", "②", "⑴，然后看看她接下来的反应。", "贰，然后看看她接下来的反应。", "选择第二项，然后观察她的反应。", "1。接着观察她会如何回答。", "就照刚才的那个行动做。", "I choose two and wait quietly.", "I will carry it out tomorrow.", "Please carry it out tomorrow.", "We shall proceed with them tomorrow.", "Please select B and wait quietly.", "I choose B and then wait for her.", "那就选乙，然后再看看她接下来的反应。", "I shall proceed with them tomorrow.", "我打算照她所说的计划去做然后再回来。", "请继续生成接下来发生的故事。", "Please continue the story for a while.", "好", "unknown", "сделай это")) {
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

    @Test fun `retain an unknown letter reference without treating ordinary English as a choice`() {
        val source = "旁白\n<choices>\nA) Read the map\nB) Wait by the door\n</choices>"
        val assistant = message("a", MessageRole.ASSISTANT, source, 1)
            .copy(actionOptionsFormat = ActionOptionsFormat("<choices>", "</choices>", "", ActionOptionLabels.LETTERS))
        for (reference in listOf(
            "Let's go with B and wait quietly.",
            "Perhaps ‘b’ would work better here.",
            "Let's go with A and wait quietly.",
            "She suggested B so we will wait quietly.",
            "She suggested A so we will wait quietly.",
            "a 然后静静地等待门口下一轮反应。",
            "A然后静静地等待门口下一轮反应。",
            "a　然后静静地等待门口下一轮反应。"
        )) {
            val user = message("u", MessageRole.USER, reference, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            assertEquals(reference, source, projection.project(assistant, source))
        }
        for (text in listOf("I'll open a window while we're waiting nearby.", "I open a window and wait nearby.")) {
            val independent = message("u", MessageRole.USER, text, 2)
            val projection = known(listOf(assistant, independent), setOf(assistant.id, independent.id))
            assertEquals("旁白\n", projection.project(assistant, source))
        }
        val nameEndingInA = message("u", MessageRole.USER, "Maria 然后静静地等待门口下一轮反应。", 2)
        val nameProjection = known(listOf(assistant, nameEndingInA), setOf(assistant.id, nameEndingInA.id))
        assertEquals("旁白\n", nameProjection.project(assistant, source))
        val nineOptions = ('A'..'I').joinToString("\n") { "$it) Action $it" }
        val longMenu = assistant.copy(content = "旁白\n<choices>\n$nineOptions\n</choices>")
        val referenceI = message("u", MessageRole.USER, "She suggested I so we will wait quietly.", 2)
        val projection = known(listOf(longMenu, referenceI), setOf(longMenu.id, referenceI.id))
        assertEquals(longMenu.content, projection.project(longMenu, longMenu.content))

        val lowerMenu = assistant.copy(content = source.replace("A)", "a)").replace("B)", "b)"))
        val lowerReference = message("u", MessageRole.USER, "a 然后静静地等待门口下一轮反应。", 2)
        val lowerProjection = known(listOf(lowerMenu, lowerReference), setOf(lowerMenu.id, lowerReference.id))
        assertEquals(lowerMenu.content, lowerProjection.project(lowerMenu, lowerMenu.content))
        val numbered = assistant.copy(content = "旁白\n$options", actionOptionsFormat = null)
        val numberedProjection = known(listOf(numbered, lowerReference), setOf(numbered.id, lowerReference.id))
        assertEquals("旁白\n", numberedProjection.project(numbered, numbered.content))
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

    @Test fun `attached Chinese labels and English order references keep the source menu`() {
        val letters = "旁白\n<choices>\nA) 查地图\nB) 问向导\nC) 等一会儿\n</choices>"
        val assistant = message("a", MessageRole.ASSISTANT, letters, 1)
            .copy(actionOptionsFormat = ActionOptionsFormat("<choices>", "</choices>", "", ActionOptionLabels.LETTERS))
        for (reference in listOf(
            "我同意B方案，先检查营地周边。",
            "我同意b　方案，先检查营地周边。",
            "我同意B，并请她把补给送到码头。",
            "我同意B并请她把补给送到码头。",
            "我赞成B的方案，今天沿着山路向前探索。",
            "我赞成 B 的方案，今天沿着山路向前探索。",
            "我同意b　的　计划，今天沿着山路向前探索。",
            "我赞成C，并请她把补给送到码头。",
            "Please carry out the penultimate plan quietly."
        )) {
            val user = message("u", MessageRole.USER, reference, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            assertEquals(reference, letters, projection.project(assistant, letters))
            assertEquals(reference, projection.project(user, reference))
        }
        for (narration in listOf("我看见B站门口有几个人在等车。", "我看见LAB方案的标牌旁边有一扇门。")) {
            val user = message("u", MessageRole.USER, narration, 2)
            val projection = known(listOf(assistant, user), setOf(assistant.id, user.id))
            assertEquals(narration, "旁白\n", projection.project(assistant, letters))
        }
        val twoOptions = assistant.copy(content = letters.replace("\nC) 等一会儿", ""))
        val unrelated = message("u", MessageRole.USER, "我沿着C路线巡查空置工厂并锁门。", 2)
        val projection = known(listOf(twoOptions, unrelated), setOf(twoOptions.id, unrelated.id))
        assertEquals("旁白\n", projection.project(twoOptions, twoOptions.displayContent))
        for (text in listOf("我赞成C的方案，今天沿着山路向前探索。", "我赞成 C 的方案，今天沿着山路向前探索。")) {
            val possessiveWithoutMatchingLabel = unrelated.copy(content = text)
            val unknownReference = known(listOf(twoOptions, possessiveWithoutMatchingLabel), setOf(twoOptions.id, unrelated.id))
            assertEquals(text, "旁白\n", unknownReference.project(twoOptions, twoOptions.displayContent))
        }
        val numbered = assistant.copy(content = "旁白\n$options", actionOptionsFormat = null)
        val noLetterMenu = unrelated.copy(content = "我赞成 B 的方案，今天沿着山路向前探索。")
        val numberedProjection = known(listOf(numbered, noLetterMenu), setOf(numbered.id, noLetterMenu.id))
        assertEquals("旁白\n", numberedProjection.project(numbered, numbered.displayContent))
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
    @Test fun `image user protects its earlier sources while later independent options can be omitted`() {
        val old = message("old", MessageRole.ASSISTANT, options, 1)
        val imageUser = message("image", MessageRole.USER, "A long generated description appears independent.", 2)
            .copy(images = listOf("/synthetic.png"))
        val next = message("next", MessageRole.ASSISTANT, options.replace("OLD", "NEW"), 3)
        val current = message("current", MessageRole.USER, "我推开木门，走到窗边观察街上的雨。", 4)
        val all = listOf(old, imageUser, next, current)
        val p = known(all, all.map { it.id }.toSet())
        assertEquals(old.displayContent, p.project(old, old.displayContent))
        assertFalse(p.project(next, next.displayContent).contains("NEW_NOT_TAKEN"))
        val withCurrentImage = all.dropLast(1) + current.copy(images = listOf("/current.png"))
        val q = known(withCurrentImage, all.map { it.id }.toSet())
        assertEquals(next.displayContent, q.project(next, next.displayContent))
        assertEquals(imageUser.displayContent, p.project(imageUser, imageUser.displayContent))
    }

}
