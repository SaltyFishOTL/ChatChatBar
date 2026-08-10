package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiPromptPostProcessorTest {
    @Test
    fun `rewrites full tag atoms ignoring case spaces and underscores`() {
        val processor = processor(
            NovelAiTagRewriteRule(
                aliases = listOf("lying on back"),
                replacements = listOf("on_back")
            )
        )

        val result = processor.process(
            DesignedImagePrompt(baseCaption = "from above, LYING_ON_BACK, lying   on backrest,")
        )

        assertEquals("from above, on_back, lying   on backrest,", result.prompt.baseCaption)
        assertEquals(1, result.rewrites.size)
    }

    @Test
    fun `preserves NAI wrappers and expands one tag to multiple tags`() {
        val processor = processor(
            NovelAiTagRewriteRule(
                aliases = listOf("bikini pulled aside"),
                replacements = listOf("side-tie_bikini", "untied_bikini"),
                mode = NovelAiTagRewriteMode.EXPAND
            )
        )

        val first = processor.process(
            DesignedImagePrompt(baseCaption = "1.2::Bikini_Pulled Aside::, {bikini pulled aside},")
        )
        val second = processor.process(first.prompt)

        assertEquals(
            "1.2::side-tie_bikini, untied_bikini::, {side-tie_bikini, untied_bikini},",
            first.prompt.baseCaption
        )
        assertEquals(first.prompt, second.prompt)
    }

    @Test
    fun `ambiguous rule warns without guessing`() {
        val processor = processor(
            NovelAiTagRewriteRule(
                aliases = listOf("中出"),
                replacements = listOf("creampie", "cum_in_pussy"),
                mode = NovelAiTagRewriteMode.AMBIGUOUS
            )
        )

        val result = processor.process(DesignedImagePrompt(baseCaption = "中出,"))

        assertEquals("中出,", result.prompt.baseCaption)
        assertTrue(result.issues.single().message.contains("歧义"))
    }

    @Test
    fun `converts only complete Stable Diffusion weight atoms`() {
        val result = processor().process(
            DesignedImagePrompt(
                scenePrompt = "(from_above:1.3), name_(series),",
                characters = listOf(DesignedCharacterPrompt(adjustment = "(smile:0.8),"))
            )
        )

        assertEquals("1.3::from_above::, name_(series),", result.prompt.scenePrompt)
        assertEquals("0.8::smile::,", result.prompt.characters.single().adjustment)
        assertEquals(2, result.rewrites.size)
    }

    @Test
    fun `canonicalizer runs before syntax normalizer then style is prepended`() {
        val processor = processor(
            NovelAiTagRewriteRule(
                aliases = listOf("from above"),
                replacements = listOf("from_above")
            )
        )

        val processed = processor.process(
            DesignedImagePrompt(baseCaption = "(From Above:1.3),")
        )
        val plan = NovelAiPromptDesigner.convert(
            designed = processed.prompt,
            stylePrompt = "locked_card_style"
        )

        assertEquals("(From Above:1.3)", processed.rewrites[0].before)
        assertEquals("(from_above:1.3)", processed.rewrites[0].after)
        assertEquals("(from_above:1.3)", processed.rewrites[1].before)
        assertEquals("1.3::from_above::", processed.rewrites[1].after)
        assertTrue(processed.issues.isEmpty())
        assertEquals("locked_card_style, 1.3::from_above::", plan.baseCaption)
    }

    private fun processor(vararg rules: NovelAiTagRewriteRule) =
        NovelAiPromptPostProcessor(rules.toList())
}
