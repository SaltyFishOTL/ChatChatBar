package com.example.chatbar.domain.chat

import java.lang.Character.UnicodeScript
import com.example.chatbar.data.local.entity.ActionOptionLabels
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** Describes the current request's owner; a no-current-USER control may have older USER history. */
enum class ChatHistoryActionRequestSource { PERSISTED_USER, UI_CONTINUATION, NO_USER_REGENERATION, UNKNOWN }

/** Request-only projection. An unresolved choice reference retains its source options.
 * The query owner supplies a complete recent suffix; separately pinned old turns are not proof
 * that all intervening user references were loaded. Existing status projection runs independently. */
class ChatHistoryActionProjection private constructor(private val omittable: Map<String, ChatMessage>) {
    fun project(message: ChatMessage, source: String): String =
        if (omittable[message.id] == message) {
            RoleplayActionOptionsPolicy.withoutExplicitOptions(source, message.displayActionOptionsFormat)
        } else source

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
            // an old pure-number or image-bearing reply may depend on an older block.
            // A generated visual description is not proof of independent typed intent.
            val lastUnresolvedReference = lastPossibleChoiceReference(candidates)
            return ChatHistoryActionProjection(candidates.withIndex().filter { (index, message) ->
                index > lastUnresolvedReference && message.id in completeRecentMessageIds && message.role == MessageRole.ASSISTANT
            }.associate { it.value.id to it.value })
        }

        private fun lastPossibleChoiceReference(candidates: List<ChatMessage>): Int {
            var last = -1
            val menuLabelsSeen = mutableSetOf<Char>()
            for ((index, message) in candidates.withIndex()) {
                if (message.role == MessageRole.USER) {
                    val content = message.displayContent
                    if (message.images.isNotEmpty() || mayReferToEarlierChoices(content) ||
                        hasMenuLetterReference(content, menuLabelsSeen)
                    ) last = index
                } else if (message.role == MessageRole.ASSISTANT) {
                    val format = message.displayActionOptionsFormat
                    if (format?.labels != ActionOptionLabels.LETTERS) continue
                    for (block in RoleplayActionOptionsPolicy.blocks(message.displayContent, format)) {
                        block.options.mapNotNullTo(menuLabelsSeen) { it.label.singleOrNull() }
                    }
                }
            }
            return last
        }

        private fun hasMenuLetterReference(text: String, seen: Set<Char>): Boolean {
            if (seen.isEmpty()) return false
            if (('A' in seen || 'a' in seen) && hasAWithHanContinuation(text)) return true
            if (hanMenuLetter.findAll(text).any { match ->
                    val label = match.groupValues[1].ifEmpty { match.groupValues[2] }.single()
                    label.uppercaseChar() in seen || label.lowercaseChar() in seen
                }
            ) return true
            return ambiguousLetterLabel.findAll(text).any { match ->
                val label = match.value.single()
                if (label == 'a') label in seen
                else seen.any { it.equals(label, ignoreCase = true) }
            }
        }

        // Check code points directly so Android's ICU and the desktop JVM use the same rule.
        private fun hasAWithHanContinuation(text: String): Boolean {
            for (index in text.indices) {
                if (text[index] != 'a' && text[index] != 'A') continue
                if (index > 0) {
                    val previous = Character.codePointBefore(text, index)
                    if (previous == '_'.code || Character.isDigit(previous) ||
                        UnicodeScript.of(previous) == UnicodeScript.LATIN
                    ) continue
                }
                var next = index + 1
                while (next < text.length &&
                    (text[next] == '\t' || Character.getType(text[next]) == Character.SPACE_SEPARATOR.toInt())
                ) next++
                if (next < text.length && UnicodeScript.of(Character.codePointAt(text, next)) == UnicodeScript.HAN) {
                    return true
                }
            }
            return false
        }

        /** Deliberately conservative lexical admission, not natural-language intent inference. */
        fun mayReferToEarlierChoices(text: String): Boolean {
            val normalized = text.trim().lowercase()
            if (normalized.isBlank() || numeric.containsMatchIn(normalized) ||
                normalized.contains("```") || reference.containsMatchIn(normalized) ||
                acknowledgement.matches(normalized)) return true
            if (standaloneLetterLabel.findAll(normalized).any { !possessiveMenuSuffix.matchesAt(normalized, it.range.last + 1) }) {
                return true
            }
            if (normalized.any { it.isLetter() && it !in 'a'..'z' && it !in '\u3400'..'\u9fff' }) return true
            // Bare numbers, labels, emoji and short/unknown responses do not prove independence.
            return normalized.count(Char::isLetter) < 8
        }

        private val numeric = Regex("\\p{N}")
        // Avoid treating the English article, pronoun and parts of contractions as labels.
        private fun standaloneLetter(characters: String) =
            Regex("(?<![\\p{L}\\p{N}_])(?<!\\p{L}['’])[$characters](?![\\p{L}\\p{N}_]|['’]\\p{L})")
        private val standaloneLetterLabel = standaloneLetter("b-hj-z")
        // A and I can be ordinary English. Lowercase a needs a menu that actually uses that case.
        private const val ambiguousLetters = "AaIi"
        private val ambiguousLetterLabel = standaloneLetter(ambiguousLetters)
        private const val menuNoun = "(?:方案|计划|路线)"
        private val possessiveMenuSuffix = Regex("[ \\t　]*的[ \\t　]*$menuNoun")
        private val hanMenuLetter = Regex(
            "(?<![A-Za-z0-9_])([A-Ta-t])[ \\t　]*(?:的[ \\t　]*)?$menuNoun|" +
                "(?:同意|赞成|接受)[ \\t　]*([A-Ta-t])[ \\t　]*(?=$|[,，、.。:：;；!！?？]|并|然后|再)"
        )
        private val reference = Regex(
            "第[\\s一二三四五六七八九十百两〇零壹贰叁肆伍陆柒捌玖拾0-9０-９]+(?:个|项|条|种|步|段|选|方案)|" +
                "(?:选|选择|采用|执行|按照|按|走|用|要)[\\s:：\"'“‘（(]*[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾0-9０-９①-⑳甲乙丙丁戊己庚辛壬癸a-z]|" +
                "^[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾甲乙丙丁]+(?:[,，.、。)）：\\s]|号|然后|再|并)|" +
                "^[a-t][,，.、。)）：]|" +
                "(?:前|后)[一二三四五六七八九十两]*(?:个|项|条)|" +
                "(?:选项|行动选项|可选行动|上面|上述|上边|刚才|之前|前面|前者|后者|这个|那个|这条|那条|这些|那些|此项|此条|上个|下个|这样|那样|照着|照做|照办|最后一个|中间那个|照旧|照常|如上|所说的|说的做|说的办|照她|照他|照你|照我)|" +
                "\\b(?:option|options|choice|choices|above|previous|former|latter|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|(?:ante)?penultimate|last|this|that|those|these|it|them|same|aforementioned)\\b|" +
                "\\b(?:choose|select|pick|take|do|go\\s+with|go\\s+for|opt\\s+for|settle\\s+on)\\s+" +
                "(?:the\\s+)?['’\"“‘]?(?:one|two|three|four|five|six|seven|eight|nine|ten|[a-z])\\b(?!['’]\\p{L})|" +
                "['\"“‘][ai]['\"”’]"
        )
        private val acknowledgement = Regex("(?:(?:请|再|那就|那么)?(?:继续|接着|接下来).*|好[的吧啊呀]?|行|可以|是|不|随便|都行|嗯+|快点|ok|yes|no|(?:please\\s+)?(?:continue|go on|keep going).*|do it)[。.!！?？\\s]*")
    }
}
