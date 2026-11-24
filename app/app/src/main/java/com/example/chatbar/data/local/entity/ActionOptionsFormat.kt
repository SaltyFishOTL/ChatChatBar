package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
enum class ActionOptionLabels { NUMBERS, LETTERS }

/** Literal lines declared by a format card. A null rule uses the CCB conventions. */
@Serializable
data class ActionOptionsFormat(
    val opening: String = "---",
    val closing: String = "---",
    val heading: String = "【行动选项】",
    val labels: ActionOptionLabels = ActionOptionLabels.NUMBERS
)
