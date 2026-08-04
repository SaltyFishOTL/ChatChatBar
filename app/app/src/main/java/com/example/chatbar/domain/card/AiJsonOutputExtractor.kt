package com.example.chatbar.domain.card

private val fencedCodeBlockRegex = Regex(
    pattern = "```(?:json)?\\s*([\\s\\S]*?)```",
    options = setOf(RegexOption.IGNORE_CASE)
)

internal fun String.extractJsonObjectCandidates(): List<String> {
    val source = trim()
    val repaired = source.repairJsonQuotesAndCommas()
    val fencedCandidates = fencedCodeBlockRegex.findAll(repaired)
        .flatMap { match -> match.groupValues.getOrNull(1).orEmpty().extractBalancedJsonObjects() }
        .toList()
    val inlineCandidates = repaired.extractBalancedJsonObjects().toList()
    val balanced = (fencedCandidates + inlineCandidates)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (balanced.isNotEmpty()) return balanced
    return repaired.indices.asSequence()
        .filter { repaired[it] == '{' }
        .mapNotNull(::unclosedJsonObjectStartingAt)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}

internal fun String.repairJsonQuotesAndCommas(): String {
    val sb = StringBuilder(length + 32)
    var inString = false
    var escaped = false
    var previousSignificant: Char? = null
    var i = 0
    while (i < length) {
        val ch = this[i]
        when {
            escaped -> {
                sb.append(ch)
                escaped = false
            }
            inString && ch == '\\' -> {
                sb.append(ch)
                escaped = true
            }
            ch == '"' -> {
                if (inString) {
                    var j = i + 1
                    while (j < length && this[j].isWhitespace()) j++
                    val next = if (j < length) this[j] else '\u0000'
                    when {
                        next == ',' || next == '}' || next == ']' || next == ':' || next == '\u0000' -> {
                            inString = false
                            sb.append(ch)
                        }
                        next == '"' -> {
                            var k = j + 1
                            while (k < length && this[k].isWhitespace()) k++
                            val after = if (k < length) this[k] else '\u0000'
                            if (after == ',' || after == '}' || after == ']' || after == ':' || after == '\u0000') {
                                sb.append('\\').append(ch)
                            } else {
                                inString = false
                                sb.append(ch).append(',')
                            }
                        }
                        next == '{' || next == '[' -> {
                            inString = false
                            sb.append(ch).append(',')
                        }
                        else -> sb.append('\\').append(ch)
                    }
                } else {
                    if (previousSignificant == '}' || previousSignificant == ']' || previousSignificant == '"') {
                        sb.append(',')
                    }
                    inString = true
                    sb.append(ch)
                }
            }
            !inString && (ch == '{' || ch == '[') -> {
                if (previousSignificant == '}' || previousSignificant == ']' || previousSignificant == '"') {
                    sb.append(',')
                }
                previousSignificant = ch
                sb.append(ch)
            }
            !inString && !ch.isWhitespace() -> {
                previousSignificant = ch
                sb.append(ch)
            }
            else -> sb.append(ch)
        }
        i++
    }
    return sb.toString()
}

private fun String.extractBalancedJsonObjects(): Sequence<String> =
    indices.asSequence()
        .filter { this[it] == '{' }
        .mapNotNull(::balancedJsonObjectStartingAt)

private fun String.balancedJsonObjectStartingAt(start: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until length) {
        val ch = this[index]
        when {
            escaped -> escaped = false
            ch == '\\' && inString -> escaped = true
            ch == '"' -> inString = !inString
            !inString && ch == '{' -> depth++
            !inString && ch == '}' -> {
                depth--
                if (depth == 0) return substring(start, index + 1)
            }
        }
    }
    return null
}

private fun String.unclosedJsonObjectStartingAt(start: Int): String? {
    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    for (index in start until length) {
        val ch = this[index]
        when {
            escaped -> escaped = false
            inString && ch == '\\' -> escaped = true
            ch == '"' -> inString = !inString
            !inString && (ch == '{' || ch == '[') -> stack.addLast(ch)
            !inString && ch == '}' -> {
                if (stack.isEmpty() || stack.removeLast() != '{') return null
            }
            !inString && ch == ']' -> {
                if (stack.isEmpty() || stack.removeLast() != '[') return null
            }
        }
    }
    if (stack.isEmpty()) return null
    val sb = StringBuilder(substring(start))
    if (inString) sb.append('"')
    while (stack.isNotEmpty()) {
        sb.append(if (stack.removeLast() == '{') '}' else ']')
    }
    return sb.toString()
}
