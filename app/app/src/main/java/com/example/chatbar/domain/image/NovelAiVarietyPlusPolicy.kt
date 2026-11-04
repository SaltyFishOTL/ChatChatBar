package com.example.chatbar.domain.image

import kotlin.math.sqrt

/** NovelAI web scales CFG-delay sigma by the actual latent canvas area. */
internal object NovelAiVarietyPlusPolicy {
    const val DEFAULT_SIGMA = 58.0

    fun resolutionScale(width: Int, height: Int): Double {
        require(width >= 8 && height >= 8) { "Variety+ 请求尺寸无效" }
        return sqrt((width / 8).toDouble() * (height / 8) / (104 * 152))
    }
}
