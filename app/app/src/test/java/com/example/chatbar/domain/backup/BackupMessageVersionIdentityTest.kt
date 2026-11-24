package com.example.chatbar.domain.backup

import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupMessageVersionIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun copy(text: String, scratch: File, check: () -> Unit = {}): String {
        val out = StringWriter()
        BackupMessageVersionIdentity("chat_messages", out, scratch, check).use { versions ->
            BackupJsonStream(StringReader(text), versions.writer, checkActive = check,
                capturedFields = versions.observedFields, observeField = versions::fieldEvent,
                observeString = versions::stringEvent, observeValue = versions::valueEvent,
                observeObject = versions::objectEvent) { value, _ -> value }.copySequence()
        }
        return out.toString()
    }

    @Test fun largeJsonlMessagesUseOneSpoolAndKeepTextAndIndependentOriginalIds() {
        val scratch = temporary.newFolder()
        val body = "synthetic large body ".repeat(65000)
        val first = "{\"content\":\"$body\",\"alternatives\":[\"$body\",\"second\"],\"currentAlternativeIndex\":1,\"id\":\"old-one\"}"
        val second = "{\"currentAlternativeIndex\":0,\"id\":\"old-two\",\"content\":\"unchanged\"}"
        var largest = 0L
        val result = copy(first + "\n" + second, scratch) {
            val files = scratch.listFiles().orEmpty()
            assertTrue(files.size <= 1)
            largest = maxOf(largest, files.firstOrNull()?.length() ?: 0L)
        }
        assertTrue(largest > 1_000_000)
        val parts = result.lineSequence().filter(String::isNotBlank).map { backupJson.parseToJsonElement(it).jsonObject }.toList()
        assertEquals(2, parts.size)
        assertEquals(body, parts[0].getValue("content").jsonPrimitive.content)
        assertEquals(MessageAlternativeVersionPolicy.identity("old-one", 2, emptyList(), 1, null).currentAlternativeVersionId,
            parts[0].getValue("currentAlternativeVersionId").jsonPrimitive.content)
        assertEquals("old-two", parts[1].getValue("currentAlternativeVersionId").jsonPrimitive.content)
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationDuringLargeMessageDeletesOnlyItsPrivateSpool() {
        val scratch = temporary.newFolder()
        val sibling = temporary.newFile().also { it.writeText("untouched") }
        var checks = 0
        try {
            copy("{\"id\":\"cancelled\",\"content\":\"" + "x".repeat(1_100_000) + "\"}", scratch) {
                if (++checks == 4) throw CancellationException("synthetic cancellation")
            }
            fail("cancellation expected")
        } catch (_: CancellationException) { }
        assertEquals("untouched", sibling.readText())
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }
}
