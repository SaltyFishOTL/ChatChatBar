package com.example.chatbar.domain.backup

import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import java.io.Closeable
import java.io.File
import java.io.Writer
import kotlinx.serialization.encodeToString

/** Spools one message, never its text in heap; freezes implicit identities before ID remapping. */
internal class BackupMessageVersionIdentity(
    type: String,
    private val output: Writer,
    private val scratch: File,
    private val checkActive: () -> Unit
) : Closeable {
    private val messagePath: List<String>? = when (type) {
        "chat_messages" -> emptyList()
        "save_slots" -> listOf("messages")
        else -> null
    }
    private var spool: File? = null
    private var destination = output
    private var messageId: String? = null
    private var count = 0
    private var currentIndex = 0
    private var currentId: String? = null
    private val explicitIds = mutableListOf<String>()
    val observedFields = setOf("id", "alternativeVersionIds", "currentAlternativeVersionId")
    val writer = object : Writer() {
        override fun write(chars: CharArray, offset: Int, length: Int) = destination.write(chars, offset, length)
        override fun flush() = destination.flush()
        override fun close() = this@BackupMessageVersionIdentity.close()
    }

    fun objectEvent(path: List<String>, start: Boolean) {
        if (path != messagePath) return
        if (start) {
            check(spool == null)
            checkActive()
            require(scratch.isDirectory || scratch.mkdirs()) { "无法创建消息迁移暂存目录" }
            spool = File.createTempFile("message-", ".json", scratch)
            destination = requireNotNull(spool).bufferedWriter()
            messageId = null
            count = 0
            currentIndex = 0
            currentId = null
            explicitIds.clear()
        } else {
            destination.close()
            destination = output
            val file = requireNotNull(spool)
            try {
                checkActive()
                val identity = MessageAlternativeVersionPolicy.identity(
                    requireNotNull(messageId) { "消息缺少 ID" }, count, explicitIds, currentIndex, currentId)
                val fields = mapOf(
                    "alternativeVersionIds" to backupJson.encodeToString(identity.alternativeVersionIds),
                    "currentAlternativeVersionId" to backupJson.encodeToString(identity.currentAlternativeVersionId))
                file.bufferedReader().use { reader ->
                    BackupJsonStream(reader, output, checkActive = checkActive,
                        replaceValue = { fields[it.singleOrNull()] }, extraRootFields = fields
                    ) { value, _ -> value }.copy()
                }
            } finally {
                check(file.delete() || !file.exists()) { "无法删除消息迁移暂存文件" }
                spool = null
            }
        }
    }

    private fun field(path: List<String>): String? = messagePath?.let { prefix ->
        path.lastOrNull()?.takeIf { path.size == prefix.size + 1 && path.dropLast(1) == prefix }
    }

    fun fieldEvent(path: List<String>, value: String) {
        if (spool == null) return
        when (field(path)) {
            "id" -> messageId = value
            "alternativeVersionIds" -> explicitIds.add(value)
            "currentAlternativeVersionId" -> currentId = value
        }
    }

    fun stringEvent(path: List<String>): Writer? {
        if (spool != null && field(path) == "alternatives") count = Math.addExact(count, 1)
        return null
    }

    fun valueEvent(path: List<String>): Writer? {
        if (spool == null || field(path) != "currentAlternativeIndex") return null
        return object : Writer() {
            private val text = StringBuilder()
            override fun write(chars: CharArray, offset: Int, length: Int) {
                for (index in offset until offset + length) if (!chars[index].isWhitespace()) {
                    require(text.length < 16) { "消息备选索引无效" }
                    text.append(chars[index])
                }
            }
            override fun flush() { currentIndex = requireNotNull(text.toString().toIntOrNull()) { "消息备选索引无效" } }
            override fun close() = flush()
        }
    }

    override fun close() {
        if (destination !== output) destination.close()
        destination = output
        spool?.let { check(it.delete() || !it.exists()) { "无法删除消息迁移暂存文件" } }
        spool = null
    }
}
