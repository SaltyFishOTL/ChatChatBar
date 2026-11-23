package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

internal const val VISION_LINK_TRANSACTION_TYPE = "model_vision_links_pending"

@Serializable
internal data class PendingVisionModelLink(val visionId: String, val visionCreatedAt: Long)

internal fun uncommittedVisionModels(
    models: List<ModelConfig>,
    transactions: List<PendingVisionModelLink>
): Set<String> {
    val byId = models.associateBy(ModelConfig::id)
    val linked = models.mapNotNullTo(mutableSetOf()) { it.visionModelId }
    return transactions.mapNotNullTo(mutableSetOf()) { transaction ->
        val child = byId[transaction.visionId]
        child?.id?.takeIf { child.createdAt == transaction.visionCreatedAt && it !in linked }
    }
}
