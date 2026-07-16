package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemorySourceTurnRef
import com.example.chatbar.data.local.entity.MemoryTurnSource
import java.security.MessageDigest

object MemoryHashes {
    fun text(value: String): String = sha256(value)

    fun source(turns: List<MemoryTurnSource>): String = sha256(
        turns.joinToString("\n") { turn ->
            "${turn.timelineTurn}:${turn.messageIds.joinToString(",")}:${turn.sourceHash}"
        }
    )

    fun sourceRefs(turns: List<MemorySourceTurnRef>): String = sha256(
        turns.joinToString("\n") { turn ->
            "${turn.sourceTurnId}:${turn.sourceOrder}:${turn.messageIds.joinToString(",")}:${turn.sourceHash}"
        }
    )

    fun sourceIds(sourceTurnIds: List<String>, sourceHashes: List<String>): String = sha256(
        sourceTurnIds.zip(sourceHashes).joinToString("\n") { (id, hash) -> "$id:$hash" }
    )

    fun coverage(children: List<MemoryNode>): String = sha256(
        children.joinToString("\n") { child ->
            "${child.id}:${child.sourceTurnIds.joinToString(",")}:${child.sourceHash}:${child.coverageHash}"
        }
    )

    fun coverageUnits(units: List<MemoryCoverageUnit>): String = sha256(
        units.joinToString("\n") { "${it.sourceId}:${it.text}" }
    )

    /** New Episode proof: program-owned ordered sources plus the single formal summary. */
    fun episodeCoverage(
        sourceTurnIds: List<String>,
        sourceHashes: Map<String, String>,
        content: String
    ): String = sha256(
        buildString {
            sourceTurnIds.forEach { sourceId ->
                appendLine("$sourceId:${sourceHashes[sourceId].orEmpty()}")
            }
            append("content=")
            append(content.trim())
        }
    )

    fun parentCoverage(children: List<MemoryNode>, units: List<MemoryCoverageUnit>): String = sha256(
        buildString {
            children.forEach { child ->
                appendLine(
                    "${child.id}:${child.sourceTurnIds.joinToString(",")}:" +
                        "${child.sourceHash}:${child.coverageHash}"
                )
            }
            append("units=")
            append(units.joinToString("\n") { "${it.sourceId}:${it.text}" })
        }
    )

    fun leafCoverage(turns: List<MemoryTurnSource>): String = sha256(
        turns.joinToString("\n") { "${it.timelineTurn}:${it.sourceHash}" }
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
