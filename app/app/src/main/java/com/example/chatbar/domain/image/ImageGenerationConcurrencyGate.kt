package com.example.chatbar.domain.image

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ImageGenerationConcurrencyGate(maxParallel: Int) {
    private val semaphore = Semaphore(maxParallel)

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit {
        block()
    }
}

object GlobalImageGenerationConcurrencyGate {
    val instance = ImageGenerationConcurrencyGate(maxParallel = 2)
}
