package com.example.chatbar.domain.backup

import java.io.File
import java.io.Writer

/** Spools only unselected media fields. Neither message text nor media payloads enter memory. */
internal class BackupRetainedMedia(
    source: File,
    private val directory: File,
    fields: Set<String>,
    private val checkActive: () -> Unit
) : AutoCloseable {
    private val values = fields.associateWith { File(directory, "$it.json") }
    val streams: Map<String, (Writer) -> Unit> = fields.associateWith { field -> { output ->
        val file = values.getValue(field)
        if (file.isFile) file.strictUtf8Reader().use { reader ->
            val buffer = CharArray(16 * 1024)
            while (true) {
                checkActive()
                val count = reader.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } else output.write(if (field == "imagePath" || field == "generatedImageMetadata" && "imagePath" in fields) "null" else "[]")
    } }

    init {
        check(directory.mkdirs()) { "无法准备未选媒体字段" }
        val writers = mutableMapOf<String, Writer>()
        try {
            source.strictUtf8Reader().use { reader ->
                BackupJsonStream(reader, BackupMergeCatalog.discardWriter, checkActive = checkActive,
                    observeValue = { path -> path.singleOrNull()?.takeIf { it in fields }?.let { field ->
                        writers.getOrPut(field) { values.getValue(field).bufferedWriter() }
                    } }
                ) { path, _ -> path }.copy()
            }
        } finally { writers.values.forEach { it.close() } }
    }

    override fun close() { directory.deleteRecursively() }
}
