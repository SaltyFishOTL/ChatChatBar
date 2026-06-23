package com.example.chatbar.domain.rag

data class RagSourcePlan(
    val includeDocuments: Boolean,
    val includeMemory: Boolean
) {
    val shouldRetrieve: Boolean
        get() = includeDocuments || includeMemory

    companion object {
        fun create(
            documentCount: Int,
            indexedDocumentCount: Int,
            messageCount: Int,
            contextWindowSize: Int
        ): RagSourcePlan = RagSourcePlan(
            includeDocuments = documentCount > 0 && indexedDocumentCount > 0,
            includeMemory = messageCount > contextWindowSize.coerceAtLeast(1)
        )
    }
}
