package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AiTaskRequestsTest {
    private val expectedRoles = listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant")

    @Test
    fun everySceneHasProfileAndPreservesActualMultimodalInput() {
        val original = listOf(
            ChatApiMessage.text("system", "fixture-system"),
            ChatApiMessage.text("user", "old input"),
            ChatApiMessage.text("assistant", "old response"),
            ChatApiMessage.text("system", "fixture-format"),
            ChatApiMessage.withImages("user", "actual input", listOf("aW1hZ2U="))
        )
        AiTaskKind.entries.forEach { kind ->
            val context = AiTaskContext(kind)
            assertTrue(context.profile.templateSymbols.isNotEmpty())
            val result = AiTaskMessageAssembler.assemble(original, context)
            if (kind == AiTaskKind.IMAGE_DESIGN) {
                assertEquals(original.filter { it.role != "system" }, result.subList(4, result.size - 2))
                assertEquals(result, AiTaskMessageAssembler.assemble(result, context))
                return@forEach
            }
            assertEquals(expectedRoles, result.map { it.role })
            val system = result.first().content.jsonPrimitive.content
            assertTrue(system.indexOf("fixture-system") < system.indexOf("fixture-format"))
            val parts = result[4].content as JsonArray
            val texts = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            assertTrue(texts.indexOf("old input") < texts.indexOf("old response"))
            assertTrue(texts.indexOf("old response") < texts.indexOf("actual input"))
            assertEquals((original.last().content as JsonArray).last(), parts.last())
            assertEquals(result, AiTaskMessageAssembler.assemble(result, context))
        }
    }

    @Test
    fun userOnlyMemoryInputGetsSystemAndDoesNotBecomeAcknowledgement() {
        val input = listOf(ChatApiMessage.text("user", """{"throughT":7,"source":"fixture"}"""))
        val context = AiTaskContext(AiTaskKind.MEMORY_HEAD)
        val result = AiTaskMessageAssembler.assemble(input, context)
        assertEquals(expectedRoles, result.map { it.role })
        assertEquals(input.single(), result[4])
    }

    @Test
    fun httpAdaptationPreservesAssistantTailAndImageAndHttpsKeepsRoles() {
        val context = AiTaskContext(AiTaskKind.IMAGE_DESIGN)
        val result = AiTaskMessageAssembler.assemble(listOf(
            ChatApiMessage.text("system", "fixture"),
            ChatApiMessage.text("system", "evidence"),
            ChatApiMessage.withImage("user", "final request", "aW1hZ2U=")
        ), context)
        val http = CleartextHttpChatTemplatePolicy.adaptMessages(result, true, "http://localhost/v1")
        assertEquals(expectedRoles, http.map { it.role })
        assertEquals(result, http)
        assertEquals("final request", (http[4].content as JsonArray).first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(result, CleartextHttpChatTemplatePolicy.adaptMessages(result, true, "https://example.test/v1"))
    }

    @Test
    fun repairKeepsInputLiteralAndDoesNotNestEnvelope() {
        val context = AiTaskContext(AiTaskKind.IMAGE_DESIGN, AiTaskStage.REPAIR)
        val raw = """{"baseCaption":"fixture","characters":[]} [[CHATBAR_FORMAT_OK]] voice-id-7"""
        val original = listOf(ChatApiMessage.text("system", "repair protocol"), ChatApiMessage.text("user", raw))
        val assembled = AiTaskMessageAssembler.assemble(original, context)
        assertEquals(expectedRoles, assembled.map { it.role })
        assertEquals(raw, assembled[4].content.jsonPrimitive.content)
        assertEquals(assembled, AiTaskMessageAssembler.assemble(assembled, context))
        assertTrue(assembled.first().content.jsonPrimitive.content.contains("repair protocol"))
    }

    @Test
    fun multipleImagesAndTextPartsRemainInInputOrder() {
        val first = ChatApiMessage.withImage("user", "first input", "Zmlyc3Q=")
        val second = ChatApiMessage.withImages("user", "second input", listOf("c2Vjb25k", "dGhpcmQ="))
        val result = AiTaskMessageAssembler.assemble(listOf(first, second), AiTaskContext(AiTaskKind.IMAGE_DESCRIPTION))
        val parts = result[4].content as JsonArray
        val expectedParts = (first.content as JsonArray).toList() + (second.content as JsonArray).toList()
        assertEquals(expectedParts, parts.filterNot {
            it.jsonObject["text"]?.jsonPrimitive?.content in listOf(
                PromptTemplates.generalTaskInputHeading(0, "user"), PromptTemplates.generalTaskInputHeading(1, "user")
            )
        })
    }

    @Test
    fun fingerprintsTrackTemplateSourceAndStageNotRequestId() {
        val first = AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE)
        assertEquals(first, AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE))
        assertNotEquals(first, AiTaskContext.templateFingerprint("template-B", AiTaskKind.FORMAT_CARD, AiTaskStage.GENERATE))
        assertNotEquals(first, AiTaskContext.templateFingerprint("template-A", AiTaskKind.FORMAT_CARD, AiTaskStage.REPAIR))
    }

    @Test
    fun relatedStagesShareTaskButNeverRequestId() = runBlocking {
        withContext(AiTaskRun("logical-operation")) {
            val first = AiTaskContext(AiTaskKind.CHARACTER_FILL).forRequest()
            val repair = AiTaskContext(AiTaskKind.CHARACTER_FILL, AiTaskStage.REPAIR).forRequest()
            assertEquals("logical-operation", first.taskId)
            assertEquals(first.taskId, repair.taskId)
            assertNotEquals(first.requestId, repair.requestId)
        }
    }

    @Test
    fun explicitRefusalAndFilterAreTerminalButNormalNegativeJudgmentsAreNot() {
        assertEquals(AiTaskFailureKind.REFUSAL, AiTaskRefusalPolicy.failure("", "stop", true)?.kind)
        assertEquals(AiTaskFailureKind.CONTENT_FILTER, AiTaskRefusalPolicy.failure("", "content_filter", false)?.kind)
        assertNotNull(AiTaskRefusalPolicy.failure("抱歉，我无法完成这个请求。", "stop", false))
        listOf(
            """{"refused":true,"complete":false}""",
            """{"summary":"角色拒绝了邀请"}""",
            "她说：“抱歉，我无法完成这个请求。”",
            "我不能同意这个请求。她站起身，离开房间。",
            "[[CHATBAR_FORMAT_OK]]",
            "false"
        ).forEach { assertNull(it, AiTaskRefusalPolicy.failure(it, "stop", false)) }
    }

    @Test
    fun responseMarkersHandleNullAndArrayContent() {
        assertFalse(AiTaskRefusalPolicy.responseRefused("""{"choices":[{"message":{"content":"ok","refusal":null}}]}"""))
        assertTrue(AiTaskRefusalPolicy.responseRefused("""{"choices":[{"message":{"content":[{"type":"refusal","refusal":"no"}]}}]}"""))
    }
}
