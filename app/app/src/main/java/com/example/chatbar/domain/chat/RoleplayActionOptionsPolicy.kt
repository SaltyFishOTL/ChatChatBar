package com.example.chatbar.domain.chat

/** Derived projection only; original replies, versions, status and user text are unchanged. */
data class RoleplayActionOption(val ordinal: Int, val text: String)
data class RoleplayActionOptionsBlock(val range: IntRange, val options: List<RoleplayActionOption>)

object RoleplayActionOptionsPolicy {
    private val numbered = Regex("^([1-9][0-9]?)[.、)）][ \\t]*(.+)$")
    private fun rule(line: String): Boolean = line.trim().let {
        it.length >= 3 && it.all { char -> char in "-─━—" }
    }
    private fun heading(line: String): Boolean = line.trim()
        .trim('#', '*', ' ', '[', ']', '【', '】', ':', '：').lowercase() in
        setOf("行动选项", "可选行动", "行动选择", "action choices", "action options")

    fun blocks(content: String): List<RoleplayActionOptionsBlock> {
        val codeRanges = parseRoleplayTextSegments(content).filter {
            it.kind == RoleplaySegmentKind.STATUS && it.rawText.trimStart().startsWith("```")
        }.map { it.start until it.endExclusive }
        return roleplayLongDashRawRanges(content).mapNotNull { range ->
            if (codeRanges.any { it.first <= range.last && range.first <= it.last }) return@mapNotNull null
            val lines = content.substring(range).lines().filter(String::isNotBlank)
            // Fail closed: incomplete fences and generic numbered narration are not action blocks.
            if (lines.size < 5 || !rule(lines.first()) || !rule(lines.last()) || !heading(lines[1]))
                return@mapNotNull null
            val options = mutableListOf<RoleplayActionOption>()
            for (line in lines.drop(2).dropLast(1)) {
                val match = numbered.matchEntire(line.trim())
                if (match != null) {
                    val ordinal = match.groupValues[1].toInt()
                    if (ordinal != options.size + 1 || ordinal > 20) return@mapNotNull null
                    options += RoleplayActionOption(ordinal, match.groupValues[2])
                } else if (options.isNotEmpty() && (line.startsWith("  ") || line.startsWith("\t"))) {
                    val previous = options.removeAt(options.lastIndex)
                    options += previous.copy(text = previous.text + "\n" + line.trim())
                } else return@mapNotNull null
            }
            if (options.size < 2) null else RoleplayActionOptionsBlock(range, options.toList())
        }
    }

    fun withoutExplicitOptions(content: String): String = blocks(content)
        .sortedByDescending { it.range.first }.fold(content) { text, block -> text.removeRange(block.range) }
}
