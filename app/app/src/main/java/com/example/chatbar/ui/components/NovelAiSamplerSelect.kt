package com.example.chatbar.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiSampler
import com.example.chatbar.ui.kit.CbSelect

/** Shared by studio and character-card settings; Medium must not write High's sampler. */
@Composable
fun NovelAiSamplerSelect(settings: NovelAiGenerationSettings, onValueChange: (NovelAiSampler) -> Unit) {
    val currentSettings by rememberUpdatedState(settings)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    CbSelect(
        value = settings.effectiveSampler,
        options = if (settings.isMediumEffort) listOf(settings.effectiveSampler) else settings.model.samplers,
        optionLabel = { it.displayName },
        onValueChange = { sampler ->
            // Also guard a dialog opened before effort changed to Medium.
            if (!currentSettings.isMediumEffort) currentOnValueChange(sampler)
        },
        enabled = !settings.isMediumEffort
    )
}
