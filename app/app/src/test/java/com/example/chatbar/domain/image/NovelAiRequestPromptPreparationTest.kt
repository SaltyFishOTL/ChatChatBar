package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NovelAiRequestPromptPreparationTest {
    @Test fun tokenCountPreparationMatchesWireForCommaNormalizationAndTransparency() {
        for (effort in NovelAiEffort.entries) for (transparent in listOf(false, true)) {
            for (source in listOf("scene， transparent background", "scene, transparent background",
                "scene， other", "scene， transparent background, text: literal|later， chunk")) {
                val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
                    effort = effort, transparentBackground = transparent)
                val prompt = NovelAiPromptPlan(source, emptyList(), negativePrompt = "custom， negative")
                val prepared = NovelAiV5TextPromptPolicy.prepareForRequest(prompt, settings)
                val wire = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(prompt,
                    settings.imageSize(), settings)).jsonObject
                assertEquals(prepared.baseCaption, wire.getValue("input").jsonPrimitive.content)
                assertEquals(prepared.baseCaption, wire.getValue("parameters").jsonObject.getValue("v4_prompt")
                    .jsonObject.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content)
                assertEquals(prepared.effectiveNegativePrompt.trim(), wire.getValue("parameters").jsonObject
                    .getValue("negative_prompt").jsonPrimitive.content)
                if (source.endsWith("transparent background")) {
                    assertEquals(1, Regex("transparent background").findAll(prepared.baseCaption).count())
                }
                assertEquals(source, prompt.baseCaption)
                assertEquals(prepared, NovelAiV5TextPromptPolicy.prepareForRequest(prepared, settings))
            }
        }
    }
}
