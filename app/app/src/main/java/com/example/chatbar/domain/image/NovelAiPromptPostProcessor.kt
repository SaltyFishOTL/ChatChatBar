package com.example.chatbar.domain.image

data class NovelAiPromptRewrite(
    val location: String,
    val before: String,
    val after: String
)

data class NovelAiPromptLintIssue(
    val location: String,
    val message: String
)

data class NovelAiPromptPostProcessResult(
    val prompt: DesignedImagePrompt,
    val rewrites: List<NovelAiPromptRewrite> = emptyList(),
    val issues: List<NovelAiPromptLintIssue> = emptyList()
)

class NovelAiPromptPostProcessor private constructor(
    rules: List<NovelAiTagRewriteRule>,
    private val enabled: Boolean
) {
    constructor(rules: List<NovelAiTagRewriteRule>) : this(rules, true)

    private data class CaptionResult(
        val caption: String,
        val rewrites: List<NovelAiPromptRewrite>,
        val issues: List<NovelAiPromptLintIssue>
    )

    private val tagCanonicalizer = TagCanonicalizer(rules)
    private val syntaxNormalizer = SyntaxNormalizer()
    private val promptLinter = PromptLinter()

    fun process(prompt: DesignedImagePrompt): NovelAiPromptPostProcessResult {
        if (!enabled) return NovelAiPromptPostProcessResult(prompt)
        val rewrites = mutableListOf<NovelAiPromptRewrite>()
        val issues = mutableListOf<NovelAiPromptLintIssue>()

        val baseLocation = if (prompt.baseCaption.isNotBlank()) "baseCaption" else "scenePrompt"
        val base = processCaption(prompt.effectiveBaseCaption, baseLocation)
        rewrites += base.rewrites
        issues += base.issues

        val characters = prompt.characters.mapIndexed { index, character ->
            val location = if (character.caption.isNotBlank()) {
                "characters[$index].caption"
            } else {
                "characters[$index].adjustment"
            }
            val result = processCaption(character.effectiveCaption, location)
            rewrites += result.rewrites
            issues += result.issues
            if (character.caption.isNotBlank()) {
                character.copy(caption = result.caption)
            } else {
                character.copy(adjustment = result.caption)
            }
        }
        val processed = if (prompt.baseCaption.isNotBlank()) {
            prompt.copy(baseCaption = base.caption, characters = characters)
        } else {
            prompt.copy(scenePrompt = base.caption, characters = characters)
        }
        return NovelAiPromptPostProcessResult(processed, rewrites, issues)
    }

    private fun processCaption(caption: String, location: String): CaptionResult {
        if (caption.isBlank()) return CaptionResult(caption, emptyList(), emptyList())
        val canonicalized = tagCanonicalizer.canonicalize(caption, location)
        val syntaxNormalized = syntaxNormalizer.normalize(canonicalized.atoms, location)
        val result = syntaxNormalized.atoms.joinToString(", ") +
            if (canonicalized.trailingComma && syntaxNormalized.atoms.isNotEmpty()) "," else ""
        return CaptionResult(
            caption = result,
            rewrites = canonicalized.rewrites + syntaxNormalized.rewrites,
            issues = canonicalized.issues + promptLinter.lint(result, location)
        )
    }

    companion object {
        fun disabled(): NovelAiPromptPostProcessor = NovelAiPromptPostProcessor(emptyList(), false)
    }
}

private data class CompiledTagRewriteRule(
    val replacements: List<String>,
    val mode: NovelAiTagRewriteMode
)

private data class CanonicalizedCaption(
    val atoms: List<String>,
    val trailingComma: Boolean,
    val rewrites: List<NovelAiPromptRewrite>,
    val issues: List<NovelAiPromptLintIssue>
)

private data class SyntaxNormalizedCaption(
    val atoms: List<String>,
    val rewrites: List<NovelAiPromptRewrite>
)

private class TagCanonicalizer(rules: List<NovelAiTagRewriteRule>) {
    private val rulesByAlias = buildMap {
        rules.forEach { rule ->
            rule.aliases.forEach { alias ->
                val key = alias.normalizeTagLookupKey()
                if (key.isNotBlank() && key !in this) {
                    put(key, CompiledTagRewriteRule(rule.replacements, rule.mode))
                }
            }
        }
    }

    fun canonicalize(caption: String, location: String): CanonicalizedCaption {
        val rewrites = mutableListOf<NovelAiPromptRewrite>()
        val issues = mutableListOf<NovelAiPromptLintIssue>()
        val atoms = caption.split(',').mapNotNull { raw ->
            val atom = raw.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rewritten = canonicalizeAtom(atom, location, issues)
            if (rewritten != atom) {
                rewrites += NovelAiPromptRewrite(location, atom, rewritten)
            }
            rewritten
        }
        return CanonicalizedCaption(
            atoms = atoms,
            trailingComma = caption.trimEnd().endsWith(','),
            rewrites = rewrites,
            issues = issues
        )
    }

    private fun canonicalizeAtom(
        atom: String,
        location: String,
        issues: MutableList<NovelAiPromptLintIssue>
    ): String {
        STABLE_DIFFUSION_WEIGHT.matchEntire(atom)?.let { match ->
            val core = match.groupValues[1].trim()
            val rewrittenCore = canonicalizeCore(core, location, issues)
            return "($rewrittenCore:${match.groupValues[2]})"
        }

        var start = 0
        val numericPrefix = NUMERIC_WEIGHT_PREFIX.find(atom)?.value.orEmpty()
        start += numericPrefix.length
        while (start < atom.length && atom[start] in OPENING_WEIGHT_CHARS) start++

        var end = atom.length
        if (end >= 2 && atom.substring(end - 2) == "::") end -= 2
        while (end > start && atom[end - 1] in CLOSING_WEIGHT_CHARS) end--
        val prefix = atom.substring(0, start)
        val suffix = atom.substring(end)
        val decoratedCore = atom.substring(start, end).trim()
        if (decoratedCore.isBlank()) return atom

        return "$prefix${canonicalizeCore(decoratedCore, location, issues)}$suffix"
    }

    private fun canonicalizeCore(
        decoratedCore: String,
        location: String,
        issues: MutableList<NovelAiPromptLintIssue>
    ): String {
        val relationMatch = RELATION_PREFIX.matchEntire(decoratedCore)
        val relationPrefix = relationMatch?.groupValues?.get(1).orEmpty()
        val core = relationMatch?.groupValues?.get(2) ?: decoratedCore
        val rule = rulesByAlias[core.normalizeTagLookupKey()] ?: return decoratedCore
        if (rule.mode == NovelAiTagRewriteMode.AMBIGUOUS) {
            issues += NovelAiPromptLintIssue(
                location,
                "歧义 tag 未自动替换：$core → ${rule.replacements.joinToString(" / ")}"
            )
            return decoratedCore
        }
        return rule.replacements.joinToString(", ") { "$relationPrefix$it" }
    }

    companion object {
        private val NUMERIC_WEIGHT_PREFIX = Regex("^[+-]?\\d+(?:\\.\\d+)?::\\s*")
        private val STABLE_DIFFUSION_WEIGHT = Regex("^\\((.+):([+-]?\\d+(?:\\.\\d+)?)\\)$")
        private val RELATION_PREFIX = Regex(
            "^((?:source|target|mutual)#(?:\\d+:)?)(.+)$",
            RegexOption.IGNORE_CASE
        )
        private val OPENING_WEIGHT_CHARS = setOf('{', '[')
        private val CLOSING_WEIGHT_CHARS = setOf('}', ']')
    }
}

private class SyntaxNormalizer {
    fun normalize(atoms: List<String>, location: String): SyntaxNormalizedCaption {
        val rewrites = mutableListOf<NovelAiPromptRewrite>()
        val normalized = atoms.map { atom ->
            val match = STABLE_DIFFUSION_WEIGHT.matchEntire(atom) ?: return@map atom
            val tag = match.groupValues[1].trim()
            if (tag.isBlank()) return@map atom
            val rewritten = "${match.groupValues[2]}::$tag::"
            rewrites += NovelAiPromptRewrite(location, atom, rewritten)
            rewritten
        }
        return SyntaxNormalizedCaption(normalized, rewrites)
    }

    companion object {
        private val STABLE_DIFFUSION_WEIGHT = Regex("^\\((.+):([+-]?\\d+(?:\\.\\d+)?)\\)$")
    }
}

private class PromptLinter {
    fun lint(caption: String, location: String): List<NovelAiPromptLintIssue> = buildList {
        if (caption.count { it == '{' } != caption.count { it == '}' }) {
            add(NovelAiPromptLintIssue(location, "花括号权重未配平"))
        }
        if (caption.count { it == '[' } != caption.count { it == ']' }) {
            add(NovelAiPromptLintIssue(location, "方括号权重未配平"))
        }
        if (DOUBLE_COLON.findAll(caption).count() % 2 != 0) {
            add(NovelAiPromptLintIssue(location, "数值权重 :: 未配平"))
        }
        if (BOT_DIRECTIVE.containsMatchIn(caption)) {
            add(NovelAiPromptLintIssue(location, "检测到法典 Bot/参数指令；已保留但不应发送给 NovelAI"))
        }
        if (STABLE_DIFFUSION_WEIGHT.containsMatchIn(caption)) {
            add(NovelAiPromptLintIssue(location, "检测到未标准化的 Stable Diffusion 权重语法"))
        }
    }

    companion object {
        private val DOUBLE_COLON = Regex("::")
        private val STABLE_DIFFUSION_WEIGHT = Regex("\\([^,()]+:[+-]?\\d+(?:\\.\\d+)?\\)")
        private val BOT_DIRECTIVE = Regex(
            "(?:#绘画|--steps|prompt_guidance|ntags\\s*=)",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}
