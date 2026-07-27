package com.example.chatbar.domain.voice

import com.example.chatbar.domain.chat.StreamingChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FishAudioTagPolicyTest {
    private val service = FishAudioTagService(StreamingChatService { false })
    private val input = VoiceTagInput(
        id = "segment-1",
        text = "你好，欢迎回来。",
        characterName = "林雾",
        speakingStyle = "温柔"
    )

    @Test
    fun `s2 accepts square tags without rewriting speech`() {
        val result = FishAudioTagPolicy.validate(
            input.text,
            "[happy]你好，[pause]欢迎回来。",
            FishAudioMarkerMode.SQUARE
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `s1 accepts parentheses and rejects square Fish tags`() {
        assertTrue(
            FishAudioTagPolicy.validate(
                input.text,
                "(happy)你好，(break)欢迎回来。",
                FishAudioMarkerMode.PARENTHESIS
            ).isSuccess
        )
        assertTrue(
            FishAudioTagPolicy.validate(
                input.text,
                "[happy]你好，欢迎回来。",
                FishAudioMarkerMode.PARENTHESIS
            ).isFailure
        )
    }

    @Test
    fun `illegal tag fails while rewritten speech is distinguishable`() {
        assertTrue(
            FishAudioTagPolicy.validate(
                input.text,
                "[自由自然语言情绪]你好，欢迎回来。",
                FishAudioMarkerMode.SQUARE
            ).isSuccess
        )
        assertTrue(
            FishAudioTagPolicy.validate(
                input.text,
                "(invented)你好，欢迎回来。",
                FishAudioMarkerMode.PARENTHESIS
            ).isFailure
        )
        assertTrue(
            FishAudioTagPolicy.validate(
                input.text,
                "[happy]你好，欢迎你回来。",
                FishAudioMarkerMode.SQUARE
            ).isFailure
        )
        val analysis = FishAudioTagPolicy.analyze(
            input.text,
            "[happy]你好，欢迎你回来。",
            FishAudioMarkerMode.SQUARE
        ).getOrThrow()
        assertTrue(!analysis.spokenTextMatches)
    }

    @Test
    fun `batch parser requests confirmation for rewritten speech`() {
        val proposed = "[happy]你好，欢迎你回来。"
        val result = service.parseAndValidate(
            """
            {"segments":[
              {"id":"segment-1","ttsText":"$proposed"}
            ]}
            """.trimIndent(),
            listOf(input),
            FishAudioMarkerMode.SQUARE
        )

        assertTrue(result.taggedTextById.isEmpty())
        assertEquals(proposed, result.confirmationRequiredById["segment-1"])
        assertTrue(result.errorsById.isEmpty())
    }

    @Test
    fun `batch parser rejects duplicate and missing ids without plain text fallback`() {
        val second = input.copy(id = "segment-2", text = "第二句。")
        val result = service.parseAndValidate(
            """
            {"segments":[
              {"id":"segment-1","ttsText":"[happy]你好，欢迎回来。"},
              {"id":"segment-1","ttsText":"[sad]你好，欢迎回来。"}
            ]}
            """.trimIndent(),
            listOf(input, second),
            FishAudioMarkerMode.SQUARE
        )

        assertTrue(result.taggedTextById.isEmpty())
        assertEquals(
            setOf("segment-1", "segment-2"),
            result.errorsById.keys
        )
    }

    @Test
    fun `batch parser keeps valid items while reporting unknown ids`() {
        val result = service.parseAndValidate(
            """
            {"segments":[
              {"id":"segment-1","ttsText":"[happy]你好，欢迎回来。"},
              {"id":"unknown","ttsText":"额外内容"}
            ]}
            """.trimIndent(),
            listOf(input),
            FishAudioMarkerMode.SQUARE
        )

        assertEquals("[happy]你好，欢迎回来。", result.taggedTextById["segment-1"])
        assertTrue("unknown" in result.errorsById)
    }

    @Test
    fun `batch parser rejects markdown fences and unknown fields`() {
        val fenced = service.parseAndValidate(
            """```json
            {"segments":[{"id":"segment-1","ttsText":"[happy]你好，欢迎回来。"}]}
            ```""".trimIndent(),
            listOf(input),
            FishAudioMarkerMode.SQUARE
        )
        val unknownField = service.parseAndValidate(
            """
            {"segments":[{"id":"segment-1","ttsText":"[happy]你好，欢迎回来。","extra":true}]}
            """.trimIndent(),
            listOf(input),
            FishAudioMarkerMode.SQUARE
        )

        assertTrue(fenced.taggedTextById.isEmpty())
        assertTrue(unknownField.taggedTextById.isEmpty())
    }

    @Test
    fun `translation parser accepts one translated text per input id`() {
        val second = input.copy(id = "segment-2", text = "晚安。")
        val result = service.parseTranslation(
            """
            {"segments":[
              {"id":"segment-1","translatedText":"Hello, welcome back."},
              {"id":"segment-2","translatedText":"Good night."}
            ]}
            """.trimIndent(),
            listOf(input, second)
        )

        assertEquals("Hello, welcome back.", result.translatedTextById["segment-1"])
        assertEquals("Good night.", result.translatedTextById["segment-2"])
        assertTrue(result.errorsById.isEmpty())
    }

    @Test
    fun `translation parser reports duplicate missing unknown and blank results`() {
        val second = input.copy(id = "segment-2", text = "晚安。")
        val result = service.parseTranslation(
            """
            {"segments":[
              {"id":"segment-1","translatedText":"Hello."},
              {"id":"segment-1","translatedText":"Hi."},
              {"id":"unknown","translatedText":""}
            ]}
            """.trimIndent(),
            listOf(input, second)
        )

        assertTrue(result.translatedTextById.isEmpty())
        assertEquals(
            setOf("segment-1", "segment-2", "unknown"),
            result.errorsById.keys
        )
    }

    @Test
    fun `translation parser rejects markdown and unknown fields`() {
        val fenced = service.parseTranslation(
            """```json
            {"segments":[{"id":"segment-1","translatedText":"Hello."}]}
            ```""".trimIndent(),
            listOf(input)
        )
        val unknownField = service.parseTranslation(
            """
            {"segments":[{"id":"segment-1","translatedText":"Hello.","extra":true}]}
            """.trimIndent(),
            listOf(input)
        )

        assertTrue(fenced.translatedTextById.isEmpty())
        assertTrue(unknownField.translatedTextById.isEmpty())
    }
}
