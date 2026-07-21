package com.example.chatbar.domain.image

const val NOVEL_AI_MAX_BATCH_SIZE = 4

fun parseNovelAiBatchSize(value: String): Int? =
    value.toIntOrNull()?.takeIf { it in 1..NOVEL_AI_MAX_BATCH_SIZE }

