package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiAccountUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiAccountUiStateTest {
    @Test
    fun localV5UsageChangesDisplayBeforeRoundedServerPercentChanges() {
        val server = usage(100.0)
        val state = NovelAiAccountUiState(usage = server, loading = false)
            .recordV5Generation(1)
            .reconcile(server)

        assertEquals(1729, state.approximateV5Images)
        assertEquals(1, state.localV5AllowanceSpent)
    }

    @Test
    fun serverDecreaseAcknowledgesLocalV5Usage() {
        val state = NovelAiAccountUiState(usage = usage(100.0), loading = false)
            .recordV5Generation(1)
            .reconcile(usage(99.94))

        assertEquals(1729, state.approximateV5Images)
        assertEquals(0, state.localV5AllowanceSpent)
    }

    @Test
    fun localAnlasUsageRemainsVisibleWhileServerValueIsStale() {
        val server = usage(100.0)
        val state = NovelAiAccountUiState(usage = server, loading = false)
            .recordAnlasGeneration(12)
            .reconcile(server)

        assertEquals(9_988L, state.displayAnlas)
        assertEquals(12L, state.localAnlasSpent)
    }

    @Test
    fun serverAnlasDecreaseAcknowledgesLocalUsage() {
        val state = NovelAiAccountUiState(usage = usage(100.0), loading = false)
            .recordAnlasGeneration(12)
            .reconcile(usage(100.0).copy(anlas = 9_988))

        assertEquals(9_988L, state.displayAnlas)
        assertEquals(0L, state.localAnlasSpent)
    }

    private fun usage(percent: Double) = NovelAiAccountUsage(
        anlas = 10_000,
        tier = 3,
        active = true,
        v5AllowancePercent = percent,
        v5AllowanceExhausted = false
    )
}
