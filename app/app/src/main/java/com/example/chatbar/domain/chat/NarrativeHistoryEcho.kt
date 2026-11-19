package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)

    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }

    fun replyBody(text: String, streaming: Boolean = false): String {
        val start = text.trimStart()
        val wrapper = wrappers.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith("<历史回复 模式=") || start.startsWith("<聚焦模式=")
            if ((streaming || identifiable) && start.isNotEmpty() &&
                wrappers.any { it.opening.startsWith(start) }
            ) return ""
            return text
        }

        val body = start.removePrefix(wrapper.opening).trimStart('\r', '\n')
        val end = body.trimEnd()
        val closing = wrapper.closings.firstOrNull(end::endsWith)
        if (closing != null) {
            val beforeClosing = end.removeSuffix(closing)
            return if (canCloseOuterWrapper(end, beforeClosing.length, wrapper)) beforeClosing.trimEnd() else body
        }

        val lastTag = end.lastIndexOf('<')
        if (lastTag >= 0) {
            val suffix = end.substring(lastTag)
            // While receiving deltas, withhold a potential closing tag until it can be classified.
            // At completion, only discard a truncated tag if its name already identifies it.
            val identifiable = suffix.startsWith("</历史回复") || suffix.startsWith("</聚焦模式")
            if ((streaming || identifiable) && wrapper.closings.any { it.startsWith(suffix) } &&
                canCloseOuterWrapper(end, lastTag, wrapper)
            ) {
                return end.substring(0, lastTag).trimEnd()
            }
        }
        return body
    }

    private fun canCloseOuterWrapper(body: String, closingStart: Int, wrapper: Wrapper): Boolean {
        val literalRanges = MarkdownCodeFences.ranges(body)
        if (literalRanges.any { closingStart in it }) return false
        var innerDepth = 0
        for (match in wrapperFamilyTokens.getValue(wrapper.name).findAll(body.substring(0, closingStart))) {
            if (literalRanges.any { match.range.first in it }) continue
            if (match.value.startsWith("</")) {
                if (innerDepth == 0) return false
                innerDepth--
            } else if (!match.value.dropLast(1).trimEnd().endsWith('/')) {
                innerDepth++
            }
        }
        return innerDepth == 0
    }
}
