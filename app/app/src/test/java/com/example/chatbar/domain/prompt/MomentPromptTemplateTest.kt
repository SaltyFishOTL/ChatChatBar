package com.example.chatbar.domain.prompt

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.MomentPost
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MomentPromptTemplateTest {
    @Test
    fun momentGenerationUserPrompt_includesRoleCardDetails() {
        val card = CharacterCard(
            id = "card",
            name = "夜城剧组",
            characters = listOf(
                CharacterInfo(
                    id = "char",
                    name = "林澄",
                    profile = "表面是人气偶像，私下敏感又警惕。",
                    appearance = "银发，浅灰眼，舞台妆很淡。",
                    clothing = "宽松卫衣和鸭舌帽。",
                    abilities = "擅长即兴表演。",
                    habits = "紧张时会摸耳钉。",
                    background = "从地下Livehouse出道。",
                    relationships = "只把用户当作能看见真实自己的熟人。",
                    speakingStyle = "短句，克制，偶尔带一点冷幽默。"
                )
            ),
            basicSetting = "地下偶像企划，角色对公众形象很谨慎。",
            freeformCharacterText = "自由卡里写着她只在深夜发动态。",
            greeting = "别从正门进。",
            alternateGreetings = listOf("灯灭之后再发消息。"),
            systemPrompt = "朋友圈必须贴合角色人设。",
            postHistoryInstructions = "隐藏占有欲，不要直白复述聊天。",
            mesExample = "林澄：你看见也别说。",
            creatorNotes = "私下很怕寂寞。",
            tags = listOf("明星", "秘密关系"),
            createdAt = 1L,
            updatedAt = 1L
        )
        val session = ChatSession(
            id = "session",
            characterCardId = "card",
            title = "后台",
            createdAt = 1L,
            updatedAt = 1L
        )
        val messages = listOf(
            ChatMessage(
                id = "message",
                sessionId = "session",
                role = MessageRole.USER,
                content = "今晚舞台后门见。",
                createdAt = 2L,
                updatedAt = 2L
            )
        )

        val prompt = PromptTemplates.momentGenerationUserPrompt(card, session, messages, latestPost = null)

        assertTrue(prompt.contains("角色卡设定摘要"))
        assertTrue(prompt.contains("地下偶像企划"))
        assertTrue(prompt.contains("自由卡里写着她只在深夜发动态"))
        assertTrue(prompt.contains("外貌=银发"))
        assertTrue(prompt.contains("关系=只把用户当作能看见真实自己的熟人"))
        assertTrue(prompt.contains("说话方式=短句"))
        assertFalse(prompt.contains("角色卡开场白"))
        assertFalse(prompt.contains("别从正门进。"))
        assertFalse(prompt.contains("备用开场白"))
        assertFalse(prompt.contains("灯灭之后再发消息。"))
    }

    @Test
    fun momentGenerationUserPrompt_includesLongTermMemoryAndDoesNotTruncateMessages() {
        val longMessage = "开头-" + "很长的聊天内容".repeat(80) + "-结尾"
        val session = ChatSession(
            id = "session",
            characterCardId = "card",
            title = "雨夜",
            longTermMemory = "长期记忆：角色已经决定离开公开舞台，只把用户当作共犯。",
            createdAt = 1L,
            updatedAt = 1L
        )
        val messages = listOf(
            ChatMessage(
                id = "message",
                sessionId = "session",
                role = MessageRole.ASSISTANT,
                content = longMessage,
                createdAt = 2L,
                updatedAt = 2L
            )
        )

        val prompt = PromptTemplates.momentGenerationUserPrompt(
            card = CharacterCard(id = "card", name = "林澄", createdAt = 1L, updatedAt = 1L),
            session = session,
            messages = messages,
            latestPost = null
        )

        assertTrue(prompt.contains("长期记忆：角色已经决定离开公开舞台，只把用户当作共犯。"))
        assertTrue(prompt.contains("最近 3 条完整聊天消息"))
        assertTrue(prompt.contains(longMessage))
        assertTrue(prompt.contains("-结尾"))
    }

    @Test
    fun momentJudgeUserPrompt_usesMemoryAndPreviousMomentWithoutChatMessages() {
        val session = ChatSession(
            id = "session",
            characterCardId = "card",
            title = "后台",
            longTermMemory = "长期记忆：用户和角色刚刚约定不公开关系。",
            createdAt = 1L,
            updatedAt = 1L
        )
        val previous = MomentPost(
            id = "post",
            characterCardId = "card",
            sessionId = "session",
            senderName = "林澄",
            text = "今天风很大。",
            imageBrief = "后台走廊里的随手拍。",
            scheduledAt = 1L,
            generatedAt = 1L
        )

        val prompt = PromptTemplates.momentJudgeUserPrompt(
            session = session,
            latestPost = previous
        )

        assertTrue(prompt.contains("长期记忆：用户和角色刚刚约定不公开关系。"))
        assertTrue(prompt.contains("林澄: 今天风很大。"))
        assertTrue(prompt.contains("图片设计：后台走廊里的随手拍。"))
        assertFalse(prompt.contains("角色卡"))
        assertFalse(prompt.contains("会话"))
        assertFalse(prompt.contains("角色列表"))
        assertFalse(prompt.contains("最近 3 条完整聊天消息"))
        assertFalse(prompt.contains("近期对话"))
    }
}
