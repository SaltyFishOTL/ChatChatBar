package com.example.chatbar.domain.backup

import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.encodeToString

/** Rebind immutable trees and histories without changing source-turn identities or AI text. */
internal class BackupMergeMemory(
    private val records: List<BackupMergeRecord>, private val identity: BackupMergeIdentity,
    private val checkActive: () -> Unit, private val scratch: File, private val reserveNode: (String) -> Unit = {}
) {
    internal data class UnitInfo(var sourceId: String = "", var shortText: String? = null)
    internal data class NodeInfo(
        var id: String = "", var sourceHash: String = "", var coverageHash: String = "",
        val children: MutableList<String> = mutableListOf(), val sources: MutableList<String> = mutableListOf(),
        val units: MutableList<UnitInfo> = mutableListOf(), var spool: File? = null
    )
    internal data class Proof(val sourceHash: String, val coverageHash: String)
    private val nodes = mutableMapOf<String, NodeInfo>()
    private val proofs = mutableMapOf<String, Proof>()
    private val order = mutableMapOf<String, MutableList<NodeInfo>>()
    private val visiting = mutableSetOf<String>()

    fun prepare() {
        scratch.mkdirs()
        records.filter { it.type.startsWith("memory_") || it.type == "save_slots" }.forEach { record ->
            scan(record) { record.file.strictUtf8Reader() }
            if (record.type == "save_slots") record.text("packageRef.fileName")?.let { name ->
                val root = record.file.parentFile!!.parentFile!!.parentFile!!
                val file = File(root, "save_slot_packages/$name")
                ZipFile(file).use { zip ->
                    val entry = requireNotNull(zip.getEntry("manifest.json")) { "会话存档包缺少 manifest.json" }
                    scan(record.copy(file = file, relative = record.relative + "!manifest.json")) {
                        zip.getInputStream(entry).strictUtf8Reader()
                    }
                }
            }
        }
        nodes.keys.forEach(::resolve)
    }

    private fun location(record: BackupMergeRecord) = record.file.path + if (record.relative.endsWith("!manifest.json")) "!manifest.json" else ""

    private fun scan(record: BackupMergeRecord, reader: () -> java.io.Reader) {
        val sequence = order.getOrPut(location(record)) { mutableListOf() }
        var destination: Writer = BackupMergeCatalog.discardWriter
        val router = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) = destination.write(chars, offset, length)
            override fun flush() = destination.flush()
            override fun close() = destination.close()
        }
        val walker = Walker(record, onStart = {
            val info = NodeInfo(spool = File(scratch, UUID.randomUUID().toString() + ".json"))
            sequence.add(info)
            destination = requireNotNull(info.spool).bufferedWriter()
            info
        }, onEnd = { info ->
            destination.close()
            destination = BackupMergeCatalog.discardWriter
            require(info.id.isNotBlank()) { "记忆节点缺少 ID" }
            reserveNode(info.id)
            val before = nodes[info.id]
            require(before == null || before.sourceHash == info.sourceHash && before.coverageHash == info.coverageHash &&
                before.children == info.children && before.sources == info.sources && before.units == info.units) {
                "同一记忆节点包含冲突版本：${info.id}"
            }
            if (before == null || record.type == "memory_nodes") nodes[info.id] = info
        })
        try {
            reader().use { input ->
                BackupJsonStream(input, router, checkActive = checkActive, capturedFields = observedFields,
                    observeObject = walker::objectEvent, observeField = walker::fieldEvent,
                    observeString = { path -> walker.unitAt(path)?.takeIf { path.lastOrNull() == "text" }?.let { unit ->
                        ShortTextWriter { unit.shortText = it }
                    } }
                ) { path, _ -> path }.copy()
            }
        } finally { destination.close() }
    }

    private fun resolve(id: String): Proof {
        proofs[id]?.let { return it }
        checkActive()
        require(visiting.add(id)) { "记忆树存在循环引用：$id" }
        try {
            val node = requireNotNull(nodes[id]) { "记忆树缺少节点：$id" }
            val unchanged = identity.value("memory_nodes", id) == id && node.children.all { identity.value("memory_nodes", it) == it }
            if (node.children.isEmpty() || unchanged) return Proof(node.sourceHash, node.coverageHash).also { proofs[id] = it }
            val children = node.children.map { child -> requireNotNull(nodes[child]) { "记忆树缺少 child：$child" } to resolve(child) }
            val sourceHash = sha256(children.joinToString("\n") { (child, proof) ->
                "${identity.value("memory_nodes", child.id)}:${proof.sourceHash}"
            })
            val digest = MessageDigest.getInstance("SHA-256")
            val writer = OutputStreamWriter(DigestOutputStream(NullOutputStream, digest), Charsets.UTF_8)
            children.forEach { (child, proof) ->
                writer.write("${identity.value("memory_nodes", child.id)}:${child.sources.joinToString(",")}:${proof.sourceHash}:${proof.coverageHash}\n")
            }
            writer.write("units=")
            var index = 0
            val spool = requireNotNull(node.spool)
            val fixture = BackupMergeRecord(spool, "entities/memory_nodes/$id.json", "memory_nodes", emptyMap())
            val walker = Walker(fixture, onStart = { node }, onEnd = {})
            spool.strictUtf8Reader().use { input ->
                BackupJsonStream(input, BackupMergeCatalog.discardWriter, checkActive = checkActive,
                    observeObject = walker::objectEvent, observeString = { path ->
                        if (walker.unitAt(path) != null && path.lastOrNull() == "text") {
                            val unit = node.units[index]
                            if (index++ > 0) writer.write("\n")
                            writer.write(identity.value("memory_nodes", unit.sourceId) + ":")
                            val replacement = unitReplacement(node, unit)
                            if (replacement != null) { writer.write(replacement); BackupMergeCatalog.discardWriter } else writer
                        } else null
                    }
                ) { path, _ -> path }.copy()
            }
            require(index == node.units.size) { "记忆覆盖证明不完整" }
            writer.close()
            return Proof(sourceHash, hex(digest.digest())).also { proofs[id] = it }
        } finally { visiting.remove(id) }
    }

    private fun unitReplacement(node: NodeInfo, unit: UnitInfo): String? {
        if (node.children.isEmpty()) return null
        val child = nodes[unit.sourceId] ?: return null
        return if (unit.shortText == child.coverageHash && child.coverageHash.isNotBlank()) resolve(child.id).coverageHash else null
    }

    fun rewriter(record: BackupMergeRecord): Rewriter = Rewriter(record, order[location(record)].orEmpty())
    fun close() { check(!scratch.exists() || scratch.deleteRecursively()) { "记忆迁移暂存清理失败" } }

    internal inner class Rewriter(record: BackupMergeRecord, sequence: List<NodeInfo>) {
        val observedFields = setOf("id")
        private var index = 0
        private val walker = Walker(record, onStart = {
            require(index < sequence.size) { "记忆节点清单发生变化" }
            sequence[index++]
        }, onEnd = {})
        fun objectEvent(path: List<String>, start: Boolean) = walker.objectEvent(path, start)
        fun fieldEvent(path: List<String>, text: String) {} // Node order is captured before any field is rewritten.
        fun string(value: String, path: List<String>): String? {
            val frame = walker.nodeAt(path) ?: return null
            if (path.lastOrNull() != "sourceId" || walker.unitAt(path) == null) return null
            return if (frame.info.children.isEmpty()) value else identity.value("memory_nodes", value)
        }
        fun replacement(path: List<String>): String? {
            val frame = walker.nodeAt(path) ?: return null
            if (path == frame.path + "sourceHash") return backupJson.encodeToString(resolve(frame.info.id).sourceHash)
            if (path == frame.path + "coverageHash") return backupJson.encodeToString(resolve(frame.info.id).coverageHash)
            val unit = walker.unitAt(path)
            return if (unit != null && path.lastOrNull() == "text") unitReplacement(frame.info, unit)?.let { backupJson.encodeToString(it) } else null
        }
    }

    private data class Frame(val path: List<String>, val info: NodeInfo, var unitIndex: Int = 0)
    private inner class Walker(
        private val record: BackupMergeRecord, private val onStart: () -> NodeInfo, private val onEnd: (NodeInfo) -> Unit
    ) {
        private val frames = mutableListOf<Frame>()
        private val unitFrames = mutableListOf<Pair<List<String>, UnitInfo>>()
        fun nodeAt(path: List<String>) = frames.lastOrNull()?.takeIf { path.take(it.path.size) == it.path }
        fun unitAt(path: List<String>) = unitFrames.lastOrNull()?.takeIf { path.take(it.first.size) == it.first }?.second
        fun objectEvent(path: List<String>, start: Boolean) {
            val node = path.isEmpty() && record.type == "memory_nodes" || path.lastOrNull() == "nodes" &&
                (record.type.startsWith("memory_") || record.type == "save_slots")
            if (start && node) frames.add(Frame(path, onStart()))
            val owner = frames.lastOrNull()
            if (start && owner != null && path == owner.path + "coverageUnits") {
                val index = owner.unitIndex++
                val unit = if (index < owner.info.units.size) owner.info.units[index] else UnitInfo().also(owner.info.units::add)
                unitFrames.add(path to unit)
            }
            if (!start && unitFrames.lastOrNull()?.first == path) unitFrames.removeAt(unitFrames.lastIndex)
            if (!start && node) onEnd(frames.removeAt(frames.lastIndex).info)
        }
        fun fieldEvent(path: List<String>, text: String) {
            val frame = nodeAt(path) ?: return
            when (path) {
                frame.path + "id" -> frame.info.id = text
                frame.path + "sourceHash" -> frame.info.sourceHash = text
                frame.path + "coverageHash" -> frame.info.coverageHash = text
                frame.path + "childIds" -> frame.info.children.add(text)
                frame.path + "sourceTurnIds" -> frame.info.sources.add(text)
                else -> if (path.lastOrNull() == "sourceId") unitAt(path)?.sourceId = text
            }
        }
    }

    private class ShortTextWriter(private val finished: (String?) -> Unit) : Writer() {
        private val text = StringBuilder()
        private var long = false
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (long) return
            if (length > 65 - text.length) { long = true; text.setLength(0) } else text.append(chars, offset, length)
        }
        override fun flush() { finished(if (long) null else text.toString()) }
        override fun close() = flush()
    }
    companion object {
        private val observedFields = setOf("id", "childIds", "sourceTurnIds", "sourceHash", "coverageHash", "sourceId")
        private val NullOutputStream = object : OutputStream() { override fun write(value: Int) {} }
        private fun sha256(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
