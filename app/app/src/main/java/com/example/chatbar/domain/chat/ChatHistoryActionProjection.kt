package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** Describes the current request's owner; a no-current-USER control may have older USER history. */
enum class ChatHistoryActionRequestSource { PERSISTED_USER, UI_CONTINUATION, NO_USER_REGENERATION, UNKNOWN }

/** Request-only projection. A numeric/deictic choice remains unresolved, never expanded to text.
 * The query owner supplies a complete recent suffix; separately pinned old turns are not proof
 * that all intervening user references were loaded. Existing status projection runs independently. */
class ChatHistoryActionProjection private constructor(private val omittable: Map<String, ChatMessage>) {
    fun project(message: ChatMessage, source: String): String =
        if (omittable[message.id] == message) RoleplayActionOptionsPolicy.withoutExplicitOptions(source) else source

    companion object {
        fun prepare(candidates: List<ChatMessage>, completeRecentMessageIds: Set<String>,
            requestSource: ChatHistoryActionRequestSource = ChatHistoryActionRequestSource.UNKNOWN): ChatHistoryActionProjection {
            if (requestSource == ChatHistoryActionRequestSource.UNKNOWN ||
                (requestSource == ChatHistoryActionRequestSource.PERSISTED_USER && candidates.lastOrNull()?.role != MessageRole.USER) ||
                candidates.map { it.id }.distinct().size != candidates.size ||
                candidates.map { it.sessionId }.distinct().size > 1 ||
                candidates.zipWithNext().any { (a, b) -> ChatMessage.TimelineComparator.compare(a, b) > 0 })
                return ChatHistoryActionProjection(emptyMap())
            // Protect every earlier action source, not only the immediately preceding assistant:
            // an old pure-number reply may still depend on an older block in the same request.
            val lastUnresolvedReference = candidates.indexOfLast {
                it.role == MessageRole.USER && mayReferToEarlierChoices(it.displayContent)
            }
            return ChatHistoryActionProjection(candidates.withIndex().filter { (index, message) ->
                index > lastUnresolvedReference && message.id in completeRecentMessageIds && message.role == MessageRole.ASSISTANT
            }.associate { it.value.id to it.value })
        }

        /** Deliberately conservative lexical admission, not natural-language intent inference. */
        fun mayReferToEarlierChoices(text: String): Boolean {
            val normalized = text.trim().lowercase()
            if (normalized.isBlank() || numeric.containsMatchIn(normalized) ||
                normalized.contains("```") || reference.containsMatchIn(normalized) || acknowledgement.matches(normalized)) return true
            if (normalized.any { it.isLetter() && it !in 'a'..'z' && it !in '\u3400'..'\u9fff' }) return true
            // Bare numbers, labels, emoji and short/unknown responses do not prove independence.
            return normalized.count(Char::isLetter) < 8
        }

        private val numeric = Regex("\\p{N}")
        private val reference = Regex(
            "第[\\s一二三四五六七八九十百两〇零壹贰叁肆伍陆柒捌玖拾0-9０-９]+(?:个|项|条|种|步|段|选|方案)|" +
                "(?:选|选择|采用|执行|按照|按|走|用|要)[\\s:：\"'“‘（(]*[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾0-9０-９①-⑳]|" +
                "^[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾甲乙丙丁]+(?:[,，.、。)）：\\s]|号|然后|再|并)|" +
                "^[a-t][,，.、。)）：\\s]|" +
                "(?:前|后)[一二三四五六七八九十两]*(?:个|项|条)|" +
                "(?:选项|行动选项|可选行动|上面|上述|上边|刚才|之前|前面|前者|后者|这个|那个|这条|那条|这些|那些|此项|此条|上个|下个|这样|那样|照着|照做|照办|最后一个|中间那个|照旧|照常|如上|所说的|说的做|说的办|照她|照他|照你|照我)|" +
                "\\b(?:option|options|choice|choices|above|previous|former|latter|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|this|that|those|these|it|them|same|aforementioned)\\b|" +
                "\\b(?:choose|select|pick|take)\\s+(?:the\\s+)?(?:one|two|three|four|five|six|seven|eight|nine|ten)\\b"
        )
        private val acknowledgement = Regex("(?:(?:请|再|那就|那么)?(?:继续|接着|接下来).*|好[的吧啊呀]?|行|可以|是|不|随便|都行|嗯+|快点|ok|yes|no|(?:please\\s+)?(?:continue|go on|keep going).*|do it)[。.!！?？\\s]*")
    }
}
