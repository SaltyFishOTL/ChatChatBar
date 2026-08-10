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
            messageGroupCount: Int,
            contextWindowSize: Int,
            documentRecallCount: Int,
            memoryRecallCount: Int
        ): RagSourcePlan = RagSourcePlan(
            includeDocuments = documentRecallCount > 0 && documentCount > 0 && indexedDocumentCount > 0,
            includeMemory = memoryRecallCount > 0 &&
                messageGroupCount > contextWindowSize.coerceAtLeast(0)
        )
    }
}
