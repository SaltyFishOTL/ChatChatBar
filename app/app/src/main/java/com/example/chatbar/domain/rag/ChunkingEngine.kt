package com.example.chatbar.domain.rag

/**
 * 语义分块引擎 — 将长文本自动切分为适合向量化的文本块
 *
 * 分块策略（从大到小依次降级）：
 * 1. 按段落（双换行）拆分
 * 2. 段落超长则按句子（。！？\n）拆分
 * 3. 句子仍超长则按固定字符数硬切
 * 相邻块之间保留 overlap 字符的重叠以保持语义连贯。
 */
class ChunkingEngine {

    // ========================= 通用文本分块 =========================

    /**
     * 将文本切分为固定大小的块，相邻块保留 [overlap] 字符重叠
     *
     * @param text      待切分文本
     * @param chunkSize 每块最大字符数
     * @param overlap   相邻块重叠字符数
     * @return 切分后的文本块列表
     */
    fun chunkText(
        text: String,
        chunkSize: Int = 700,
        overlap: Int = 70
    ): List<String> {
        if (text.isBlank()) return emptyList()
        if (text.length <= chunkSize) return listOf(text.trim())

        // 第一步：按段落拆分（双换行）
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val rawChunks = mutableListOf<String>()

        for (paragraph in paragraphs) {
            if (paragraph.length <= chunkSize) {
                rawChunks.add(paragraph)
            } else {
                // 段落过长 → 按句子拆分
                rawChunks.addAll(splitBySentences(paragraph, chunkSize))
            }
        }

        // 第二步：合并小块 + 添加重叠
        return mergeWithOverlap(rawChunks, chunkSize, overlap)
    }

    // ========================= 文档分块 =========================

    /**
     * 对文档内容进行分块，并为每个块附加元数据
     *
     * @param content    文档原始文本
     * @param documentId 文档ID
     * @return (块文本, 元数据) 列表
     */
    fun chunkDocument(
        content: String,
        documentId: String,
        fileName: String = ""
    ): List<Pair<String, Map<String, String>>> {
        val chunksWithLabels = chunkDocumentWithLabels(content, fileName)
        val chunks = chunksWithLabels.map { it.first }
        return chunks.mapIndexed { index, chunk ->
            val sourceLabel = chunksWithLabels[index].second
            chunk to mapOf(
                "documentId" to documentId,
                "sourceLabel" to sourceLabel
            )
        }
    }

    private fun chunkDocumentWithLabels(
        content: String,
        fileName: String,
        chunkSize: Int = 700,
        overlap: Int = 70
    ): List<Pair<String, String>> {
        val sections = splitDocumentSections(content)
        val result = mutableListOf<Pair<String, String>>()
        val fileLabel = fileName.ifBlank { "未命名文档" }

        for ((path, body) in sections) {
            val label = if (path.isBlank()) fileLabel else "$fileLabel > $path"
            val chunks = chunkText(body, chunkSize, overlap)
            for (chunk in chunks) {
                val taggedChunk = "【来源】$label\n$chunk"
                result.add(taggedChunk to label)
            }
        }

        return result.ifEmpty {
            chunkText(content, chunkSize, overlap).map { chunk ->
                "【来源】$fileLabel\n$chunk" to fileLabel
            }
        }
    }

    private fun splitDocumentSections(content: String): List<Pair<String, String>> {
        val lines = content.lines()
        val sections = mutableListOf<Pair<String, String>>()
        val headings = mutableListOf<String>()
        val buffer = StringBuilder()
        var currentPath = ""

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotBlank()) {
                sections.add(currentPath to text)
            }
            buffer.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            val markdown = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            val plainHeading = markdown == null && isPlainHeading(trimmed)

            if (markdown != null || plainHeading) {
                flush()
                val level = markdown?.groupValues?.get(1)?.length ?: 1
                val title = markdown?.groupValues?.get(2)?.trim() ?: trimmed.trimEnd(':', '：')
                while (headings.size >= level) headings.removeAt(headings.lastIndex)
                headings.add(title)
                currentPath = headings.joinToString(" > ")
            } else {
                buffer.appendLine(line)
            }
        }

        flush()
        return sections
    }

    private fun isPlainHeading(line: String): Boolean {
        if (line.isBlank() || line.length > 40) return false
        if (line.endsWith(":") || line.endsWith("：")) return true
        return Regex("^([一二三四五六七八九十]+[、.．]|\\d+[、.．])\\s*.+$").matches(line)
    }

    // ========================= 内部方法 =========================

    /**
     * 按句子边界拆分过长段落
     * 识别的句子终结符：。！？\n
     */
    private fun splitBySentences(text: String, chunkSize: Int): List<String> {
        val sentencePattern = Regex("[。！？!?\\n]+")
        val sentences = sentencePattern.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<String>()
        val buffer = StringBuilder()

        for (sentence in sentences) {
            if (sentence.length > chunkSize) {
                // 句子本身超长 → 硬切
                if (buffer.isNotEmpty()) {
                    result.add(buffer.toString())
                    buffer.clear()
                }
                result.addAll(hardSplit(sentence, chunkSize))
            } else if (buffer.length + sentence.length + 1 > chunkSize) {
                // 加上当前句子会超长 → 先保存 buffer
                if (buffer.isNotEmpty()) {
                    result.add(buffer.toString())
                    buffer.clear()
                }
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) buffer.append(" ")
                buffer.append(sentence)
            }
        }

        if (buffer.isNotEmpty()) {
            result.add(buffer.toString())
        }

        return result
    }

    /** 按固定字符数硬切 */
    private fun hardSplit(text: String, chunkSize: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            result.add(text.substring(start, end).trim())
            start = end
        }
        return result
    }

    /**
     * 合并小块并在相邻块之间添加重叠字符
     * 策略：先把能合并的小块合到 chunkSize 以内，再对最终块添加 overlap
     */
    private fun mergeWithOverlap(
        rawChunks: List<String>,
        chunkSize: Int,
        overlap: Int
    ): List<String> {
        if (rawChunks.isEmpty()) return emptyList()

        // 合并相邻的小块
        val merged = mutableListOf<String>()
        val buffer = StringBuilder()

        for (chunk in rawChunks) {
            if (buffer.isEmpty()) {
                buffer.append(chunk)
            } else if (buffer.length + chunk.length + 1 <= chunkSize) {
                buffer.append("\n")
                buffer.append(chunk)
            } else {
                merged.add(buffer.toString())
                buffer.clear()
                buffer.append(chunk)
            }
        }
        if (buffer.isNotEmpty()) {
            merged.add(buffer.toString())
        }

        // 块数 <= 1 无需 overlap
        if (merged.size <= 1) return merged

        // 添加 overlap：将上一个块末尾的 overlap 字符拼到下一个块的前面
        val result = mutableListOf(merged.first())
        for (i in 1 until merged.size) {
            val prevChunk = merged[i - 1]
            val overlapText = if (prevChunk.length > overlap) {
                prevChunk.substring(prevChunk.length - overlap)
            } else {
                prevChunk
            }
            result.add(overlapText + merged[i])
        }

        return result
    }
}
