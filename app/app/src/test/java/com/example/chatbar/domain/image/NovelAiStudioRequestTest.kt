package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiStudioRequestTest {
    @Test
    fun `v5 request serializes explicit settings and ordered character negatives`() {
        val plan = NovelAiPromptPlan(
            baseCaption = "scene",
            characterCaptions = listOf(
                NovelAiCharacterCaption("first", DesignedCharacterCenter(0.3f, 0.5f), "bad first"),
                NovelAiCharacterCaption("second", DesignedCharacterCenter(0.7f, 0.5f), "bad second")
            )
        )
        val body = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = plan,
                imageSize = NovelAiImageSize(1536, 1024, "Large Landscape"),
                settings = NovelAiGenerationSettings(
                    model = NovelAiImageModel.V5_FULL,
                    sizeTier = NovelAiSizeTier.LARGE,
                    aspectRatio = NovelAiAspectRatio.LANDSCAPE,
                    count = 4,
                    steps = 42,
                    guidance = 7.5f,
                    seedMode = NovelAiSeedMode.FIXED,
                    seed = 1234,
                    sampler = NovelAiSampler.DPM_PLUS_PLUS_2M
                )
            )
        ).jsonObject
        val parameters = body.getValue("parameters").jsonObject
        assertEquals("nai-diffusion-5-full", body.getValue("model").jsonPrimitive.content)
        assertEquals("42", parameters.getValue("steps").jsonPrimitive.content)
        assertEquals("7.5", parameters.getValue("scale").jsonPrimitive.content)
        assertEquals("k_dpmpp_2m", parameters.getValue("sampler").jsonPrimitive.content)
        assertEquals("4", parameters.getValue("n_samples").jsonPrimitive.content)
        val negatives = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject.getValue("char_captions").jsonArray
        assertEquals(listOf("bad first", "bad second"), negatives.map { it.jsonObject.getValue("char_caption").jsonPrimitive.content })
    }

    @Test
    fun `v5 request appends quoted text block from base and character prompts`() {
        val body = requestBody(
            model = NovelAiImageModel.V5_FULL,
            plan = NovelAiPromptPlan(
                baseCaption = "speech bubble reading \"Hello, world!\"",
                characterCaptions = listOf(
                    NovelAiCharacterCaption(
                        prompt = "holding a sign marked “再见”",
                        center = DesignedCharacterCenter(0.5f, 0.5f)
                    )
                )
            )
        )

        assertEquals(
            "speech bubble reading \"Hello, world!\"\n\nText: Hello, world!\n\n再见",
            baseCaption(body)
        )
        assertEquals(baseCaption(body), body.getValue("input").jsonPrimitive.content)
    }

    @Test
    fun `explicit text block disables v5 quoted text automation`() {
        val original = "poster reading \"ignored\"\n\nText: 手动文字"
        val body = requestBody(
            model = NovelAiImageModel.V5_FULL,
            plan = NovelAiPromptPlan(original, emptyList())
        )

        assertEquals(original, baseCaption(body))
    }

    @Test
    fun `v45 request leaves quoted text untouched`() {
        val original = "speech bubble reading \"Hello, world!\""
        val body = requestBody(
            model = NovelAiImageModel.V4_5_FULL,
            plan = NovelAiPromptPlan(original, emptyList())
        )

        assertEquals(original, baseCaption(body))
    }

    private fun requestBody(model: NovelAiImageModel, plan: NovelAiPromptPlan) =
        Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = plan,
                imageSize = NovelAiImageSize(1024, 1024, "Normal Square"),
                settings = NovelAiGenerationSettings(model = model)
            )
        ).jsonObject

    private fun baseCaption(body: kotlinx.serialization.json.JsonObject): String =
        body.getValue("parameters").jsonObject
            .getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject
            .getValue("base_caption").jsonPrimitive.content
}
