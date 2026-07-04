package com.example.chatbar.domain.search

internal fun String.extractJsonObjectCandidates(): List<String> {
    val source = trim()
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        .findAll(source)
        .flatMap { it.groupValues.getOrNull(1).orEmpty().extractBalancedJsonObjects() }
        .toList()
    val inline = source.extractBalancedJsonObjects().toList()
    return (fenced + inline)
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
