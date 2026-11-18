package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import org.junit.Assert.*
import org.junit.Test

class CharacterGreetingPolicyTest {
    @Test fun choicesRetainOriginalPositionsAndExactTextWithoutChangingCard() {
        val card = CharacterCard.create("synthetic", greeting = " main \n").copy(
            alternateGreetings = listOf("", "  ", " first \n", "same", "same"))
        val choices = CharacterGreetingPolicy.choices(card)
        assertEquals(listOf(0, 3, 4, 5), choices.map { it.position })
        assertEquals(listOf(" main \n", " first \n", "same", "same"), choices.map { it.text })
        assertEquals(" main \n", CharacterGreetingPolicy.resolve(card, null))
        assertEquals(" first \n", CharacterGreetingPolicy.resolve(card, choices[1]))
        assertEquals(5, card.alternateGreetings.size)
    }

    @Test fun staleReorderedOrForeignSelectionIsRejectedInsteadOfSelectingAnotherGreeting() {
        val card = CharacterCard.create("synthetic", greeting = "main").copy(alternateGreetings = listOf("first", "second"))
        val chosen = CharacterGreetingPolicy.choices(card)[1]
        for (changed in listOf(card.copy(alternateGreetings = listOf("second", "first")),
            card.copy(alternateGreetings = listOf("changed")), card.copy(id = "another-card"))) {
            try { CharacterGreetingPolicy.resolve(changed, chosen); fail("stale choice accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val main = CharacterGreetingPolicy.choices(card).first()
        try { CharacterGreetingPolicy.resolve(card.copy(greeting = "new main"), main); fail("stale main accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
