package com.example.chatbar.domain.rag

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class RagSourcePlanTest {
    @Test
    fun noIndexedDocumentsAndNoMessagesOutsideContext_skipsRag() {
        val plan = RagSourcePlan.create(0, 0, 10, 20)

        assertFalse(plan.shouldRetrieve)
    }

    @Test
    fun messageGroupsOutsideContext_enablesOnlyMemoryRag() {
        val plan = RagSourcePlan.create(0, 0, 21, 20)

        assertFalse(plan.includeDocuments)
        assertTrue(plan.includeMemory)
    }

    @Test
    fun indexedDocumentsWithinContext_enablesOnlyDocumentRag() {
        val plan = RagSourcePlan.create(2, 2, 10, 20)

        assertTrue(plan.includeDocuments)
        assertFalse(plan.includeMemory)
    }

    @Test
    fun documentsWithoutCompletedIndex_doNotEnableDocumentRag() {
        val plan = RagSourcePlan.create(2, 0, 10, 20)

        assertFalse(plan.shouldRetrieve)
    }
}
