package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition

class WorldBookEngine {

    data class ActivatedEntry(
        val entry: WorldBookEntry,
        val sourceBookId: String,
        val sourceBookName: String
    )

    data class TimedState(
        val entryId: String,
        val stickyUntil: Int = 0,
        val cooldownUntil: Int = 0
    )

    data class ActivateResult(
        val activated: List<ActivatedEntry>,
        val outlets: Map<String, String>
    )

    fun evaluate(
        book: WorldBook,
        messages: List<ChatMessage>,
        timedStates: Map<String, TimedState> = emptyMap(),
        messageCount: Int = messages.size,
        filteredEntries: List<WorldBookEntry> = book.entries
    ): List<ActivatedEntry> {
        return evaluateInternal(book, filteredEntries, messages, book.id, book.name, timedStates, messageCount,
            allowRecursion = { book.recursiveScanning })
    }

    fun evaluateAll(
        books: List<WorldBook>,
        messages: List<ChatMessage>,
        timedStates: Map<String, Map<String, TimedState>> = emptyMap(),
        messageCount: Int = messages.size,
        characterTokens: Set<String> = emptySet()
    ): List<ActivatedEntry> {
        val results = mutableListOf<ActivatedEntry>()

        for (book in books) {
            val bookTimed = timedStates[book.id] ?: emptyMap()
            val filtered = filterEntriesByCharacter(book.entries, characterTokens)
            results += evaluate(book, messages, bookTimed, messageCount, filtered)
        }

        return applyBudgetAndSort(results, books)
    }

    fun filterEntriesByCharacter(entries: List<WorldBookEntry>, tokens: Set<String>): List<WorldBookEntry> {
        if (tokens.isEmpty()) return entries
        return entries.filter { entry ->
            if (entry.characterFilter.isEmpty()) return@filter true
            val inFilter = entry.characterFilter.any { filter ->
                tokens.any { token -> token == filter.lowercase() }
            }
            if (entry.characterFilterExclude) !inFilter else inFilter
        }
    }

    private fun evaluateInternal(
        book: WorldBook,
        entries: List<WorldBookEntry>,
        messages: List<ChatMessage>,
        bookId: String,
        bookName: String,
        timedStates: Map<String, TimedState>,
        messageCount: Int,
        allowRecursion: () -> Boolean,
        depth: Int = 0,
        maxDepth: Int = 5,
        alreadyActivated: MutableSet<String> = mutableSetOf()
    ): List<ActivatedEntry> {
        val activated = mutableListOf<ActivatedEntry>()

        for (entry in entries) {
            if (!entry.enabled) continue
            if (entry.id in alreadyActivated) continue

            val timed = timedStates[entry.id]
            val currentMsg = messageCount

            // Delay check
            if (entry.delay > 0 && currentMsg < entry.delay) continue

            // Cooldown check
            if (timed != null && timed.cooldownUntil > 0 && currentMsg < timed.cooldownUntil) continue

            // Sticky check - auto-activate if sticky
            val isSticky = timed != null && timed.stickyUntil > 0 && currentMsg <= timed.stickyUntil

            val shouldActivate = isSticky || entry.constant || matchesKeys(entry, messages)

            if (shouldActivate) {
                // Probability check (only for non-constant, non-sticky)
                if (!isSticky && !entry.constant && entry.probability < 100) {
                    if (Math.random() * 100 > entry.probability) continue
                }

                activated += ActivatedEntry(entry, bookId, bookName)
                alreadyActivated += entry.id
            }
        }

        // Group competition: keep only one per group
        val groupWinners = mutableMapOf<String, ActivatedEntry>()
        for (act in activated) {
            val g = act.entry.group
            if (g.isBlank()) continue
            val existing = groupWinners[g]
            if (existing == null || act.entry.groupWeight > existing.entry.groupWeight) {
                groupWinners[g] = act
            }
        }
        val toRemove = activated.filter { it.entry.group.isNotBlank() && groupWinners[it.entry.group] != it }
        activated.removeAll(toRemove)

        // Recursive scanning
        if (allowRecursion() && depth < maxDepth) {
            val triggeredContent = activated.joinToString("\n") { it.entry.content }
            if (triggeredContent.isNotBlank()) {
                val now = System.currentTimeMillis()
                val recursiveMsg = listOf(
                    ChatMessage(id = "", sessionId = "", role = MessageRole.USER, content = triggeredContent, createdAt = now, updatedAt = now)
                )
                val recurse = evaluateInternal(
                    book, entries, recursiveMsg, bookId, bookName,
                    timedStates, messageCount, allowRecursion, depth + 1, maxDepth, alreadyActivated
                )
                activated += recurse
            }
        }

        return activated
    }

    fun applyBudgetAndSort(
        activated: List<ActivatedEntry>,
        books: List<WorldBook>
    ): List<ActivatedEntry> {
        // Sort by insertionOrder descending (higher = closer to bottom = more impact)
        val sorted = activated.sortedByDescending { it.entry.insertionOrder }

        // Apply budget per book
        val budgetMap = books.associate { it.id to (it.tokenBudget ?: Int.MAX_VALUE) }
        val budgetUsed = mutableMapOf<String, Int>()
        val result = mutableListOf<ActivatedEntry>()

        for (act in sorted) {
            val budget = budgetMap[act.sourceBookId] ?: Int.MAX_VALUE
            val used = budgetUsed[act.sourceBookId] ?: 0
            val tokenEstimate = estimateTokens(act.entry.content)

            if (used + tokenEstimate <= budget) {
                result += act
                budgetUsed[act.sourceBookId] = used + tokenEstimate
            }
            // else: pruned due to budget
        }

        // Sort back to insertionOrder ascending for final prompt insertion
        return result.sortedBy { it.entry.insertionOrder }
    }

    private fun matchesKeys(entry: WorldBookEntry, messages: List<ChatMessage>): Boolean {
        if (entry.keys.isEmpty()) return false
        val buffer = messages.joinToString("\n") { it.content }
        val effectiveBuffer = if (entry.caseSensitive) buffer else buffer.lowercase()

        for (key in entry.keys) {
            val matched = matchKey(key, buffer, effectiveBuffer, entry)
            if (entry.selective && matched) {
                val secondaryMatch = entry.secondaryKeys.any { sk ->
                    matchKey(sk, buffer, effectiveBuffer, entry)
                }
                if (!secondaryMatch) continue
            }
            if (matched) return true
        }
        return false
    }

    private fun matchKey(
        key: String,
        buffer: String,
        effectiveBuffer: String,
        entry: WorldBookEntry
    ): Boolean {
        if (entry.useRegex) {
            // Check literal match first
            val literalKey = if (entry.caseSensitive) key else key.lowercase()
            if (effectiveBuffer.contains(literalKey)) return true
            // Then try regex match
            return tryRegexMatch(key, buffer, entry.caseSensitive)
        }
        val effectiveKey = if (entry.caseSensitive) key else key.lowercase()
        return effectiveBuffer.contains(effectiveKey)
    }

    private fun tryRegexMatch(key: String, buffer: String, caseSensitive: Boolean): Boolean {
        return try {
            val (pattern, flags) = if (isRegexKey(key)) parseRegexKey(key) else key to ""
            val options = mutableSetOf<RegexOption>()
            if (!caseSensitive || flags.contains("i")) options += RegexOption.IGNORE_CASE
            Regex(pattern, options).containsMatchIn(buffer)
        } catch (_: Exception) { false }
    }

    private fun isRegexKey(key: String): Boolean =
        key.startsWith("/") && key.length > 2 && key.drop(1).contains("/")

    private fun parseRegexKey(key: String): Pair<String, String> {
        val lastSlash = key.lastIndexOf('/')
        if (lastSlash <= 1) return key.substring(1) to ""
        val pattern = key.substring(1, lastSlash)
        val flags = key.substring(lastSlash + 1)
        return pattern to flags
    }

    fun splitByPosition(activated: List<ActivatedEntry>): Pair<List<ActivatedEntry>, List<ActivatedEntry>> {
        val before = activated.filter { it.entry.position == WorldBookPosition.BEFORE_CHAR }
        val after = activated.filter { it.entry.position == WorldBookPosition.AFTER_CHAR }
        return before to after
    }

    fun collectOutlets(activated: List<ActivatedEntry>): Map<String, String> {
        val outlets = linkedMapOf<String, StringBuilder>()
        val outletEntries = activated.filter { it.entry.position == WorldBookPosition.OUTLET && it.entry.outletName.isNotBlank() }
            .sortedBy { it.entry.insertionOrder }
        for (act in outletEntries) {
            val sb = outlets.getOrPut(act.entry.outletName) { StringBuilder() }
            if (sb.isNotEmpty()) sb.appendLine()
            sb.append(normalizePlaceholders(act.entry.content))
        }
        return outlets.mapValues { it.value.toString() }
    }

    fun expandOutlets(prompt: String, outlets: Map<String, String>): String {
        val regex = Regex("\\{\\{outlet::(\\w+)}}")
        return regex.replace(prompt) { mr ->
            outlets[mr.groupValues[1]] ?: mr.value
        }
    }

    fun isOutletEntry(entry: WorldBookEntry): Boolean =
        entry.position == WorldBookPosition.OUTLET && entry.outletName.isNotBlank()

    fun computeTimedStates(
        previousStates: Map<String, TimedState>,
        activatedIds: Set<String>,
        entryMap: Map<String, WorldBookEntry>,
        currentMessageCount: Int
    ): Map<String, TimedState> {
        val newStates = mutableMapOf<String, TimedState>()

        for ((id, state) in previousStates) {
            // Only keep states still relevant
            if (state.stickyUntil > currentMessageCount || state.cooldownUntil > currentMessageCount) {
                newStates[id] = state
            }
        }

        for (id in activatedIds) {
            val entry = entryMap[id] ?: continue
            var stickyUntil = 0
            var cooldownUntil = 0

            if (entry.sticky > 0) stickyUntil = currentMessageCount + entry.sticky
            if (entry.cooldown > 0) cooldownUntil = currentMessageCount + entry.sticky + entry.cooldown

            if (stickyUntil > 0 || cooldownUntil > 0) {
                newStates[id] = TimedState(id, stickyUntil, cooldownUntil)
            }
        }

        return newStates
    }

    fun buildWorldBookPrompt(
        activated: List<ActivatedEntry>,
        characterName: String,
        playerName: String?
    ): String {
        if (activated.isEmpty()) return ""

        val sb = StringBuilder()
        for (act in activated) {
            if (act.entry.content.isBlank()) continue
            var content = act.entry.content
            content = normalizePlaceholders(content)
            content = content.replace("\$botname", characterName)
            if (playerName != null) content = content.replace("\$username", playerName)
            sb.appendLine(content)
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun estimateTokens(text: String): Int = (text.length * 0.5).toInt().coerceAtLeast(1)

    private fun normalizePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")
}
