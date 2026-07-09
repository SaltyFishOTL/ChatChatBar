package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import kotlinx.serialization.Serializable

@Serializable
data class WorldBookEntryModalState(
    val editingIndex: Int? = null,
    val originalEntryId: String? = null,
    val name: String = "",
    val keys: String = "",
    val secondary: String = "",
    val content: String = "",
    val order: String = "100",
    val position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWords: Boolean = false,
    val caseSensitive: Boolean = false,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Boolean = false,
    val logic: Int = WorldBookSelectiveLogic.AND_ANY.value,
    val probability: String = "100",
    val group: String = "",
    val groupWeight: String = "100",
    val scanDepth: String = "",
    val sticky: String = "0",
    val cooldown: String = "0",
    val delay: String = "0",
    val outlet: String = ""
) {
    companion object {
        fun from(index: Int?, entry: WorldBookEntry?): WorldBookEntryModalState =
            WorldBookEntryModalState(
                editingIndex = index,
                originalEntryId = entry?.id,
                name = entry?.name ?: "",
                keys = entry?.keys?.joinToString(", ") ?: "",
                secondary = entry?.secondaryKeys?.joinToString(", ") ?: "",
                content = entry?.content ?: "",
                order = (entry?.insertionOrder ?: 100).toString(),
                position = entry?.position ?: WorldBookPosition.BEFORE_CHAR,
                enabled = entry?.enabled ?: true,
                constant = entry?.constant ?: false,
                useRegex = entry?.useRegex ?: false,
                wholeWords = entry?.matchWholeWords ?: false,
                caseSensitive = entry?.caseSensitive ?: false,
                ignoreBudget = entry?.ignoreBudget ?: false,
                excludeRecursion = entry?.excludeRecursion ?: false,
                preventRecursion = entry?.preventRecursion ?: false,
                delayUntilRecursion = entry?.delayUntilRecursion ?: false,
                logic = entry?.selectiveLogic ?: WorldBookSelectiveLogic.AND_ANY.value,
                probability = (entry?.probability ?: 100).toString(),
                group = entry?.group ?: "",
                groupWeight = (entry?.groupWeight ?: 100).toString(),
                scanDepth = entry?.scanDepth?.toString() ?: "",
                sticky = (entry?.sticky ?: 0).toString(),
                cooldown = (entry?.cooldown ?: 0).toString(),
                delay = (entry?.delay ?: 0).toString(),
                outlet = entry?.outletName ?: ""
            )
    }
}
