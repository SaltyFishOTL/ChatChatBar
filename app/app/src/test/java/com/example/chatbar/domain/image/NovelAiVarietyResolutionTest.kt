package com.example.chatbar.domain.image

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class NovelAiVarietyResolutionTest {
    @Test fun varietyPlusScalesWithTheActualRequestCanvas() {
        for ((width, height) in listOf(832 to 1216, 512 to 768, 1024 to 1024, 1536 to 1024)) {
            val settings = NovelAiGenerationSettings(varietyPlus = true, customWidth = width, customHeight = height)
            val parameters = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                NovelAiPromptPlan("synthetic scene", emptyList()), settings.imageSize(), settings
            )).jsonObject.getValue("parameters").jsonObject
            val expected = 58 * sqrt((width / 8).toDouble() * (height / 8) / (104 * 152))
            assertEquals(expected, parameters.getValue("skip_cfg_above_sigma").jsonPrimitive.double, 0.00001)
        }
    }
}
