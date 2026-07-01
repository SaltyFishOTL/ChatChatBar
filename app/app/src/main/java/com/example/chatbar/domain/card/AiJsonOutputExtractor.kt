package com.example.chatbar.domain.card

private val fencedCodeBlockRegex = Regex(
    pattern = "```(?:json)?\\s*([\\s\\S]*?)```",
    options = setOf(RegexOption.IGNORE_CASE)
)

internal fun String.extractJsonObjectCandidates(): List<String> {
    val source = trim()
    val fencedCandidates = fencedCodeBlockRegex.findAll(source)
        .flatMap { match -> match.groupValues.getOrNull(1).orEmpty().extractBalancedJsonObjects() }
        .toList()
    val inlineCandidates = source.extractBalancedJsonObjects().toList()
    return (fencedCandidates + inlineCandidates)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
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
