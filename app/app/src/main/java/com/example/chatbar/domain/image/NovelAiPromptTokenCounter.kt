package com.example.chatbar.domain.image

import android.content.Context
import java.io.DataInputStream
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

data class NovelAiPromptTokenUsage(
    val positive: Int,
    val negative: Int,
    val limit: Int
)

class NovelAiPromptTokenCounter(context: Context) {
    private val assets = context.applicationContext.assets
    private val t5Tokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        assets.open(T5_ASSET).use { source ->
            DataInputStream(GZIPInputStream(source)).use(T5Tokenizer::read)
        }
    }
    private val qwenTokenizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        assets.open(QWEN_ASSET).use { source ->
            DataInputStream(GZIPInputStream(source)).use(QwenTokenizer::read)
        }
    }

    fun count(prompt: NovelAiPromptPlan, model: NovelAiImageModel): NovelAiPromptTokenUsage {
        val normalizedPrompt = NovelAiPromptDelimiterPolicy.normalizeForRequest(prompt)
        val effectivePrompt = NovelAiV5TextPromptPolicy.apply(normalizedPrompt, model)
        val tokenizer = when (model.tokenizerKind) {
            NovelAiTokenizerKind.T5 -> t5Tokenizer
            NovelAiTokenizerKind.QWEN -> qwenTokenizer
        }
        val positive = tokenizer.count(effectivePrompt.baseCaption) +
            effectivePrompt.characterCaptions.sumOf { tokenizer.count(it.prompt) }
        val negative = tokenizer.count(effectivePrompt.effectiveNegativePrompt.trim()) +
            effectivePrompt.characterCaptions.sumOf { tokenizer.count(it.negativePrompt) }
        return NovelAiPromptTokenUsage(
            positive = positive,
            negative = negative,
            limit = model.promptTokenLimit
        )
    }

    private fun interface Tokenizer {
        fun count(text: String): Int
    }

    private class T5Tokenizer(
        private val unknownScore: Float,
        private val tokenIds: IntArray,
        private val scores: FloatArray,
        private val firstEdges: IntArray,
        private val childCounts: IntArray,
        private val edgeUnits: IntArray,
        private val edgeNodes: IntArray
    ) : Tokenizer {
        override fun count(text: String): Int {
            if (text.isEmpty()) return 1
            val cleaned = text
                .replace(BRACE_PATTERN, "")
                .replace(WEIGHT_PREFIX_PATTERN, "")
            var result = 1 // T5 appends one EOS token per encode call.
            WHITESPACE_PATTERN.split(cleaned, -1).forEach { fragment ->
                val metaspace = if (fragment.startsWith(METASPACE)) fragment else METASPACE + fragment
                result += countPiece(metaspace)
            }
            return result
        }

        private fun countPiece(text: String): Int {
            if (text.isEmpty()) return 0
            val bestScores = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
            val bestCounts = IntArray(text.length + 1)
            bestScores[0] = 0f
            for (start in text.indices) {
                if (bestScores[start] == Float.NEGATIVE_INFINITY) continue
                var node = 0
                var end = start
                var hasSingleUnitToken = false
                while (end < text.length) {
                    node = child(node, text[end].code)
                    if (node < 0) break
                    end += 1
                    if (tokenIds[node] >= 0) {
                        if (end == start + 1) hasSingleUnitToken = true
                        updatePath(bestScores, bestCounts, start, end, scores[node])
                    }
                }
                if (!hasSingleUnitToken) {
                    updatePath(bestScores, bestCounts, start, start + 1, unknownScore)
                }
            }
            return bestCounts[text.length]
        }

        private fun child(node: Int, unit: Int): Int {
            var low = firstEdges[node]
            var high = low + childCounts[node] - 1
            while (low <= high) {
                val middle = (low + high).ushr(1)
                when {
                    edgeUnits[middle] < unit -> low = middle + 1
                    edgeUnits[middle] > unit -> high = middle - 1
                    else -> return edgeNodes[middle]
                }
            }
            return -1
        }

        private fun updatePath(
            bestScores: FloatArray,
            bestCounts: IntArray,
            start: Int,
            end: Int,
            score: Float
        ) {
            val candidate = bestScores[start] + score
            if (bestScores[end] == Float.NEGATIVE_INFINITY || candidate > bestScores[end]) {
                bestScores[end] = candidate
                bestCounts[end] = bestCounts[start] + 1
            }
        }

        companion object {
            private const val METASPACE = "▁"
            private val BRACE_PATTERN = Regex("[\\[\\]{}]")
            private val WEIGHT_PREFIX_PATTERN = Regex("-?\\d*\\.?\\d*::")
            private val WHITESPACE_PATTERN = Pattern.compile(
                "[\\t-\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"
            )

            fun read(input: DataInputStream): T5Tokenizer {
                require(input.readAscii(4) == "NT51") { "NovelAI T5 tokenizer asset header invalid" }
                require(input.readInt() == 3) { "NovelAI T5 tokenizer asset version unsupported" }
                input.readInt() // Unknown token ID is not needed when only token count is returned.
                input.readInt() // EOS token ID is represented by the explicit +1 count.
                val nodeCount = input.readInt()
                val unknownScore = input.readFloat()
                val edgeCount = input.readInt()
                val tokenIds = IntArray(nodeCount)
                val scores = FloatArray(nodeCount)
                val firstEdges = IntArray(nodeCount)
                val childCounts = IntArray(nodeCount)
                repeat(nodeCount) { index ->
                    tokenIds[index] = input.readInt()
                    scores[index] = input.readFloat()
                    firstEdges[index] = input.readInt()
                    childCounts[index] = input.readInt()
                }
                val edgeUnits = IntArray(edgeCount)
                val edgeNodes = IntArray(edgeCount)
                repeat(edgeCount) { index ->
                    edgeUnits[index] = input.readInt()
                    edgeNodes[index] = input.readInt()
                }
                return T5Tokenizer(
                    unknownScore,
                    tokenIds,
                    scores,
                    firstEdges,
                    childCounts,
                    edgeUnits,
                    edgeNodes
                )
            }
        }
    }

    private class QwenTokenizer(
        splitRegex: String,
        private val specials: List<String>,
        private val byteTokenIds: IntArray,
        private val merges: MergeLookup
    ) : Tokenizer {
        // Android's regex engine supports explicit Unicode properties such as \p{L},
        // but not Java's UNICODE_CHARACTER_CLASS flag on all supported API levels.
        private val splitPattern = Pattern.compile(splitRegex)
        private val cache = object : LinkedHashMap<String, Int>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
                size > CACHE_SIZE
        }

        override fun count(text: String): Int {
            if (text.isEmpty()) return 0
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            var result = 0
            var cursor = 0
            while (cursor < normalized.length) {
                val match = nextSpecial(normalized, cursor)
                val specialStart = match?.first ?: normalized.length
                if (specialStart > cursor) {
                    result += countOrdinary(normalized.substring(cursor, specialStart))
                }
                if (match == null) break
                result += 1
                cursor = specialStart + match.second.length
            }
            return result
        }

        private fun countOrdinary(text: String): Int {
            var result = 0
            val matcher = splitPattern.matcher(text)
            while (matcher.find()) {
                val word = matcher.group()
                result += synchronized(cache) { cache[word] } ?: countWord(word).also { count ->
                    synchronized(cache) { cache[word] = count }
                }
            }
            return result
        }

        private fun countWord(word: String): Int {
            val bytes = word.toByteArray(Charsets.UTF_8)
            var symbols = IntArray(bytes.size) { index -> byteTokenIds[bytes[index].toInt() and 0xff] }
            var size = symbols.size
            while (size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestLeft = -1
                var bestRight = -1
                for (index in 0 until size - 1) {
                    val rank = merges.rank(symbols[index], symbols[index + 1])
                    if (rank < bestRank) {
                        bestRank = rank
                        bestLeft = symbols[index]
                        bestRight = symbols[index + 1]
                    }
                }
                if (bestRank == Int.MAX_VALUE) break
                val merged = merges.result(bestLeft, bestRight)
                val next = IntArray(size)
                var source = 0
                var target = 0
                while (source < size) {
                    if (
                        source + 1 < size &&
                        symbols[source] == bestLeft &&
                        symbols[source + 1] == bestRight
                    ) {
                        next[target++] = merged
                        source += 2
                    } else {
                        next[target++] = symbols[source++]
                    }
                }
                symbols = next
                size = target
            }
            return size
        }

        private fun nextSpecial(text: String, fromIndex: Int): Pair<Int, String>? {
            var bestIndex = Int.MAX_VALUE
            var bestToken: String? = null
            specials.forEach { token ->
                val index = text.indexOf(token, fromIndex)
                if (index >= 0 && (index < bestIndex || index == bestIndex && token.length > bestToken.orEmpty().length)) {
                    bestIndex = index
                    bestToken = token
                }
            }
            return bestToken?.let { bestIndex to it }
        }

        companion object {
            private const val CACHE_SIZE = 2048

            fun read(input: DataInputStream): QwenTokenizer {
                require(input.readAscii(4) == "NQ51") { "NovelAI Qwen tokenizer asset header invalid" }
                require(input.readInt() == 1) { "NovelAI Qwen tokenizer asset version unsupported" }
                val splitRegex = input.readSizedText()
                val specialCount = input.readInt()
                val specials = List(specialCount) { input.readSizedText() }.sortedByDescending(String::length)
                val byteTokenIds = IntArray(256) { input.readInt() }
                val mergeCount = input.readInt()
                val merges = MergeLookup(mergeCount)
                repeat(mergeCount) { rank ->
                    merges.put(input.readInt(), input.readInt(), rank, input.readInt())
                }
                return QwenTokenizer(splitRegex, specials, byteTokenIds, merges)
            }
        }
    }

    private class MergeLookup(size: Int) {
        private val capacity = nextPowerOfTwo((size * 2).coerceAtLeast(16))
        private val mask = capacity - 1
        private val keys = LongArray(capacity)
        private val ranks = IntArray(capacity)
        private val results = IntArray(capacity)

        fun put(left: Int, right: Int, rank: Int, result: Int) {
            val key = key(left, right)
            var index = index(key)
            while (keys[index] != 0L) index = (index + 1) and mask
            keys[index] = key
            ranks[index] = rank
            results[index] = result
        }

        fun rank(left: Int, right: Int): Int {
            val index = find(key(left, right))
            return if (index < 0) Int.MAX_VALUE else ranks[index]
        }

        fun result(left: Int, right: Int): Int {
            val index = find(key(left, right))
            check(index >= 0) { "Missing NovelAI Qwen merge result" }
            return results[index]
        }

        private fun find(key: Long): Int {
            var index = index(key)
            while (keys[index] != 0L) {
                if (keys[index] == key) return index
                index = (index + 1) and mask
            }
            return -1
        }

        private fun index(key: Long): Int {
            var mixed = key
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return (mixed xor (mixed ushr 31)).toInt() and mask
        }

        private fun key(left: Int, right: Int): Long =
            ((left.toLong() + 1L) shl 32) or ((right.toLong() + 1L) and 0xffffffffL)

        companion object {
            private fun nextPowerOfTwo(value: Int): Int {
                var result = 1
                while (result < value) result = result shl 1
                return result
            }
        }
    }

    companion object {
        private const val T5_ASSET = "tokenizers/nai_t5_v2.binz"
        private const val QWEN_ASSET = "tokenizers/nai_qwen35_v2.binz"
    }
}

private fun DataInputStream.readAscii(length: Int): String =
    ByteArray(length).also(::readFully).toString(Charsets.US_ASCII)

private fun DataInputStream.readSizedText(): String {
    val bytes = ByteArray(readInt())
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}
