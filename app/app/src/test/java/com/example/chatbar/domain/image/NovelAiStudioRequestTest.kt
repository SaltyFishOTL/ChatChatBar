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
                    cfgRescale = 0.35f,
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
        assertEquals("0.35", parameters.getValue("cfg_rescale").jsonPrimitive.content)
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

    @Test
    fun `request normalizes Chinese commas in every outbound prompt field`() {
        val body = requestBody(
            model = NovelAiImageModel.V4_5_FULL,
            plan = NovelAiPromptPlan(
                baseCaption = "1girl，red eyes",
                characterCaptions = listOf(
                    NovelAiCharacterCaption(
                        prompt = "green hair，smile",
                        center = DesignedCharacterCenter(0.5f, 0.5f),
                        negativePrompt = "bad hands，extra fingers"
                    )
                ),
                negativePrompt = "lowres，blurry"
            )
        )
        val parameters = body.getValue("parameters").jsonObject
        val positiveCaption = parameters.getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject
        val negativeCaption = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject

        assertEquals("1girl,red eyes", body.getValue("input").jsonPrimitive.content)
        assertEquals("1girl,red eyes", positiveCaption.getValue("base_caption").jsonPrimitive.content)
        assertEquals(
            "green hair,smile",
            positiveCaption.getValue("char_captions").jsonArray.single().jsonObject
                .getValue("char_caption").jsonPrimitive.content
        )
        assertEquals("lowres,blurry", parameters.getValue("negative_prompt").jsonPrimitive.content)
        assertEquals("lowres,blurry", negativeCaption.getValue("base_caption").jsonPrimitive.content)
        assertEquals(
            "bad hands,extra fingers",
            negativeCaption.getValue("char_captions").jsonArray.single().jsonObject
                .getValue("char_caption").jsonPrimitive.content
        )
    }

    @Test
    fun `image to image writes action source strength and noise`() {
        val body = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(832, 1216, "Normal Portrait"),
                settings = NovelAiGenerationSettings(seedMode = NovelAiSeedMode.FIXED, seed = 9),
                imageGuidance = NovelAiPreparedImageGuidance(
                    action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                    imageBase64 = "source",
                    imageToImageStrength = 0.5f,
                    imageToImageNoise = 0.1f
                )
            )
        ).jsonObject
        val parameters = body.getValue("parameters").jsonObject
        assertEquals("img2img", body.getValue("action").jsonPrimitive.content)
        assertEquals("source", parameters.getValue("image").jsonPrimitive.content)
        assertEquals("0.5", parameters.getValue("strength").jsonPrimitive.content)
        assertEquals("0.1", parameters.getValue("noise").jsonPrimitive.content)
    }

    @Test
    fun `v5 inpaint selects inpainting model and mask contract`() {
        val body = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(1024, 1024, "Normal Square"),
                settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL),
                imageGuidance = NovelAiPreparedImageGuidance(
                    action = NovelAiGenerationAction.INPAINT,
                    imageBase64 = "source",
                    maskBase64 = "mask",
                    inpaintStrength = 1f
                )
            )
        ).jsonObject
        val parameters = body.getValue("parameters").jsonObject
        assertEquals("infill", body.getValue("action").jsonPrimitive.content)
        assertEquals("nai-diffusion-5-full-inpainting", body.getValue("model").jsonPrimitive.content)
        assertEquals("mask", parameters.getValue("mask").jsonPrimitive.content)
        assertEquals("0.5", parameters.getValue("strength").jsonPrimitive.content)
        assertEquals("0.1", parameters.getValue("noise").jsonPrimitive.content)
        assertEquals("1.0", parameters.getValue("inpaintImg2ImgStrength").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("add_original_image").jsonPrimitive.content)
        assertEquals(null, parameters["img2img"])
    }

    @Test
    fun `focused inpaint strength uses official nested img2img color correction`() {
        val parameters = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(1024, 1024, "Focused Inpainting"),
                settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL),
                imageGuidance = NovelAiPreparedImageGuidance(
                    action = NovelAiGenerationAction.INPAINT,
                    imageBase64 = "source",
                    maskBase64 = "mask",
                    inpaintStrength = 0.65f
                )
            )
        ).jsonObject.getValue("parameters").jsonObject
        val img2img = parameters.getValue("img2img").jsonObject
        assertEquals("0.65", img2img.getValue("strength").jsonPrimitive.content)
        assertEquals("true", img2img.getValue("color_correct").jsonPrimitive.content)
    }

    @Test
    fun `precise arrays use fixed fidelity wire adapter`() {
        val body = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(1024, 1024, "Normal Square"),
                settings = NovelAiGenerationSettings(),
                imageGuidance = NovelAiPreparedImageGuidance(
                    action = NovelAiGenerationAction.TEXT_TO_IMAGE,
                    preciseReferenceBase64 = "precise-reference-base64",
                    preciseReferenceType = NovelAiPreciseReferenceType.CHARACTER_AND_STYLE,
                    preciseReferenceStrength = 0.8f,
                    preciseReferenceFidelity = 0.75f
                )
            )
        ).jsonObject.getValue("parameters").jsonObject
        val fixture = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/fixtures/novelai_v45_precise_payload.json")).readText()
        ).jsonObject
        val expected = fixture.getValue("parameters").jsonObject
        assertEquals("precise-reference-base64", body.getValue("director_reference_images").jsonArray.single().jsonPrimitive.content)
        assertEquals(
            "character&style",
            body.getValue("director_reference_descriptions").jsonArray.single().jsonObject
                .getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content
        )
        assertEquals("0.25", body.getValue("director_reference_secondary_strength_values").jsonArray.single().jsonPrimitive.content)
        listOf(
            "director_reference_images",
            "director_reference_descriptions",
            "director_reference_information_extracted",
            "director_reference_strength_values",
            "director_reference_secondary_strength_values"
        ).forEach { key -> assertEquals(expected.getValue(key), body.getValue(key)) }
    }

    @Test
    fun `vibe arrays write cached encoding information and strength`() {
        val body = Json.parseToJsonElement(
            NovelAiImageService().buildRequestBody(
                prompt = NovelAiPromptPlan("scene", emptyList()),
                imageSize = NovelAiImageSize(1024, 1024, "Normal Square"),
                settings = NovelAiGenerationSettings(),
                imageGuidance = NovelAiPreparedImageGuidance(
                    action = NovelAiGenerationAction.TEXT_TO_IMAGE,
                    vibes = listOf(NovelAiPreparedVibeReference("encoded", 0.9f, 0.6f))
                )
            )
        ).jsonObject.getValue("parameters").jsonObject
        assertEquals("encoded", body.getValue("reference_image_multiple").jsonArray.single().jsonPrimitive.content)
        assertEquals("0.9", body.getValue("reference_information_extracted_multiple").jsonArray.single().jsonPrimitive.content)
        assertEquals("0.6", body.getValue("reference_strength_multiple").jsonArray.single().jsonPrimitive.content)
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
