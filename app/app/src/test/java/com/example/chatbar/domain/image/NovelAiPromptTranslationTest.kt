package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiPromptTranslationTest {
    @Test
    fun `tag parser keeps weights and text block commas out of lookup`() {
        val source = "1girl, {{1.2::red eyes::}}, {{Text: Hello, world!}}\n日本語"
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = false)

        assertEquals(listOf("1girl", "red eyes", "Hello, world!"), segments.map { it.lookupText })
        assertEquals(
            listOf(
                NovelAiPromptTranslationSegmentKind.TAG,
                NovelAiPromptTranslationSegmentKind.TAG,
                NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE
            ),
            segments.map { it.kind }
        )
    }

    @Test
    fun `tag parser treats Chinese and English commas as equivalent delimiters`() {
        val segments = NovelAiPromptTranslationParser.parse(
            "1girl，red eyes,green hair",
            naturalLanguage = false
        )

        assertEquals(
            listOf("1girl", "red eyes", "green hair"),
            segments.map { it.lookupText }
        )
    }

    @Test
    fun `quoted comma remains one tag and mixed language is skipped`() {
        val source = "poster reading \"Hello, world!\", \"A girl is standing in a library\", red eyes 红眼, blue_hair"
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = false)

        assertEquals(
            listOf("poster reading \"Hello, world!\"", "A girl is standing in a library", "blue_hair"),
            segments.map { it.lookupText }
        )
        assertEquals(
            NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE,
            segments[1].kind
        )
    }

    @Test
    fun `natural language parser translates complete lines`() {
        val source = "A girl, smiling at the camera.\n夜景\nSoft morning light."
        val segments = NovelAiPromptTranslationParser.parse(source, naturalLanguage = true)

        assertEquals(
            listOf("A girl, smiling at the camera.", "Soft morning light."),
            segments.map { it.lookupText }
        )
    }

    @Test
    fun `small dictionary composes nonstandard tags word by word`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv("".byteInputStream())
        val source = "beautiful_girl standing in a dark library!"
        val translations = dictionary.tokens(source)
            .mapNotNull { token ->
                dictionary.localTranslation(token.normalized)
                    ?.let { token.normalized to it }
            }
            .toMap()

        assertEquals(
            "美丽女孩站立在一昏暗图书馆！",
            dictionary.compose(source, translations)
        )
    }

    @Test
    fun `unknown words remain visibly separated without remote fallback`() {
        val dictionary = NovelAiPromptWordDictionary.fromTsv("".byteInputStream())
        val source = "girl foobarbaz standing"
        val translations = dictionary.tokens(source)
            .mapNotNull { token ->
                dictionary.localTranslation(token.normalized)
                    ?.let { token.normalized to it }
            }
            .toMap()

        assertEquals("女孩 foobarbaz 站立", dictionary.compose(source, translations))
    }

    @Test
    fun `tag suggest query replaces every whitespace run with underscore`() {
        assertEquals("red_eyes_glowing", "  red  eyes\tglowing  ".normalizedTagQuery())
    }

    @Test
    fun `tag suggest translation requires exact normalized tag match`() {
        val outcome = NovelAiTagSearchOutcome(
            effectiveQuery = "red_eyes",
            candidates = listOf(
                NovelAiTagCandidate("red_eyes_glowing", "发光红眼", 20, NovelAiTagCategory.GENERAL),
                NovelAiTagCandidate("red_eyes", "红眼", 100, NovelAiTagCategory.GENERAL)
            )
        )

        assertEquals("红眼", outcome.exactChineseTranslation("red_eyes"))
        assertNull(outcome.exactChineseTranslation("red_eye"))
    }

    @Test
    fun `active segment follows cursor across comma boundaries`() {
        val source = "1girl, red eyes, green hair"

        assertEquals(
            "1girl",
            NovelAiPromptTranslationParser.activeSegment(source, 5, false)?.lookupText
        )
        assertEquals(
            "red eyes",
            NovelAiPromptTranslationParser.activeSegment(source, 7, false)?.lookupText
        )
        assertEquals(
            "green hair",
            NovelAiPromptTranslationParser.activeSegment(source, source.length, false)?.lookupText
        )
        assertNull(
            NovelAiPromptTranslationParser.activeSegment("1girl, ", 7, false)
        )
        assertNull(
            NovelAiPromptTranslationParser.activeSegment("1girl, , red eyes", 7, false)
        )
    }

    @Test
    fun `wrap policy protects only internal tag spaces and preserves comma boundaries`() {
        val source = "winter clothes, black coat,Text: Hello world\nlong hair"
        val plan = NovelAiPromptWrapPolicy.plan(source)

        assertEquals(
            listOf(
                source.indexOf(' '),
                source.indexOf("black coat") + "black".length,
                source.lastIndexOf(' ')
            ),
            plan.nonBreakingSpaceOffsets.toList()
        )
        assertEquals(
            listOf(source.indexOf(','), source.indexOf(',', source.indexOf(',') + 1)),
            plan.breakableCommaOffsets.toList()
        )
    }
}
