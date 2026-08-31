package com.example.chatbar.domain.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImagePolicy
import com.example.chatbar.data.local.entity.SaveSlotPackageRef
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.voice.FishAudioStorage
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

const val SAVE_SLOT_IMAGE_PREFIX = "chatbar-save-slot-image:"
const val OMITTED_SAVE_SLOT_IMAGE_PREFIX = "chatbar-save-slot-omitted-image:"
const val SAVE_SLOT_AUDIO_PREFIX = "chatbar-save-slot-audio:"
private const val MAX_COMPRESSED_IMAGE_EDGE = 1600

/** v8 存档包：正文 JSONL + 媒体文件。任何时刻最多解码一张压缩图片。 */
class SaveSlotPackageStorage(private val context: Context) {
    companion object {
        const val SCHEMA_VERSION = 8
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MESSAGES_ENTRY = "messages.jsonl"
        private const val RAG_ENTRY = "rag.jsonl"
        private const val VOICES_ENTRY = "voices.jsonl"
        private const val IMAGE_DIR = "media/images/"
        private const val AUDIO_DIR = "media/audio/"
    }

    private val root = File(context.filesDir, "save_slot_packages").also(File::mkdirs)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    data class ImportedPackage(val slot: SaveSlot)

    class Reader internal constructor(
        private val context: Context,
        private val slot: SaveSlot,
        private val zip: ZipFile,
        private val json: Json
    ) : Closeable {
        private val imageEntries = resourceEntries(IMAGE_DIR)
        private val audioEntries = resourceEntries(AUDIO_DIR)
        private val restoredImagePaths = linkedMapOf<String, String>()
        private val createdImages = mutableListOf<String>()
        private val createdAudio = mutableListOf<String>()

        val createdImagePaths: List<String> get() = createdImages.toList()
        val createdAudioPaths: List<String> get() = createdAudio.toList()

        suspend fun validate() {
            val packageRef = requireNotNull(slot.packageRef) { "v8 存档缺少 packageRef" }
            require(slot.schemaVersion == SCHEMA_VERSION) { "不支持的存档包版本：${slot.schemaVersion}" }
            require(slot.name.isNotBlank()) { "存档名称不能为空" }
            var messageCount = 0
            var imageCount = 0
            val messageIds = mutableSetOf<String>()
            forEachJsonLine(MESSAGES_ENTRY, ChatMessage.serializer()) { message ->
                require(message.id.isNotBlank()) { "存档包含空消息 ID" }
                require(messageIds.add(message.id)) { "存档包含重复消息 ID：${message.id}" }
                message.images.forEach { reference ->
                    validateImageRef(reference)
                    imageCount++
                }
                messageCount++
            }
            require(messageCount == packageRef.messageCount) {
                "存档消息数量不完整：$messageCount/${packageRef.messageCount}"
            }

            var ragCount = 0
            forEachJsonLine(RAG_ENTRY, VectorChunk.serializer()) { ragCount++ }
            require(ragCount == packageRef.ragChunkCount) {
                "存档 RAG 数量不完整：$ragCount/${packageRef.ragChunkCount}"
            }

            var audioCount = 0
            val voiceIds = mutableSetOf<String>()
            forEachJsonLine(VOICES_ENTRY, GeneratedVoiceMessage.serializer(), required = false) { voice ->
                require(voice.id.isNotBlank() && voiceIds.add(voice.id)) { "存档包含重复或空语音 ID" }
                val resourceId = voice.audioPath.removePrefix(SAVE_SLOT_AUDIO_PREFIX)
                require(voice.audioPath.startsWith(SAVE_SLOT_AUDIO_PREFIX) && resourceId in audioEntries) {
                    "存档缺少语音资源：${voice.id}"
                }
                audioCount++
            }
            require(audioCount == packageRef.audioCount) {
                "存档语音数量不完整：$audioCount/${packageRef.audioCount}"
            }
            slot.chatBackground?.let { reference ->
                validateImageRef(reference)
                imageCount++
            }
            require(imageCount == packageRef.imageCount) {
                "存档图片数量不完整：$imageCount/${packageRef.imageCount}"
            }
        }

        suspend fun streamMessages(
            targetSessionId: String,
            emit: suspend (ChatMessage) -> Unit
        ) {
            forEachJsonLine(MESSAGES_ENTRY, ChatMessage.serializer()) { message ->
                val images = message.images.map { reference -> materializeImage(reference) }
                val restoredByReference = message.images.zip(images).toMap()
                emit(
                    message.copy(
                        sessionId = targetSessionId,
                        images = images,
                        generatedImageMetadata = message.generatedImageMetadata.mapNotNull { metadata ->
                            restoredByReference[metadata.imagePath]?.let { restoredPath ->
                                metadata.copy(imagePath = restoredPath)
                            }
                        }
                    )
                )
            }
        }

        suspend fun streamRag(
            targetSessionId: String,
            emit: suspend (VectorChunk) -> Unit
        ) {
            forEachJsonLine(RAG_ENTRY, VectorChunk.serializer()) { chunk ->
                val restoredId = UUID.nameUUIDFromBytes(
                    "$targetSessionId\u0000${chunk.id}".toByteArray(Charsets.UTF_8)
                ).toString()
                emit(chunk.copy(id = restoredId, sourceId = targetSessionId))
            }
        }

        suspend fun restoreVoices(
            targetSessionId: String,
            fishAudioStorage: FishAudioStorage
        ): List<GeneratedVoiceMessage> {
            val voices = mutableListOf<GeneratedVoiceMessage>()
            forEachJsonLine(VOICES_ENTRY, GeneratedVoiceMessage.serializer(), required = false) { voice ->
                val resourceId = voice.audioPath.removePrefix(SAVE_SLOT_AUDIO_PREFIX)
                val entry = audioEntries[resourceId] ?: error("存档缺少语音资源：${voice.id}")
                val restored = zip.getInputStream(entry).use { input ->
                    fishAudioStorage.restoreArchivedAudioStream(targetSessionId, resourceId, input)
                }
                createdAudio += restored.path
                voices += voice.copy(
                    id = UUID.randomUUID().toString(),
                    sessionId = targetSessionId,
                    audioPath = restored.path,
                    durationMs = restored.durationMs,
                    byteLength = restored.byteLength,
                    updatedAt = System.currentTimeMillis()
                )
            }
            return voices
        }

        suspend fun materializeBackground(): String? {
            val reference = slot.chatBackground ?: return null
            return materializeImage(reference)
        }

        fun cleanupCreatedFiles(fishAudioStorage: FishAudioStorage) {
            createdImages.forEach { path -> runCatching { File(path).delete() } }
            createdAudio.forEach(fishAudioStorage::deleteIfOwned)
            createdImages.clear()
            createdAudio.clear()
            restoredImagePaths.clear()
        }

        private fun validateImageRef(reference: String) {
            if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return
            val resourceId = reference.removePrefix(SAVE_SLOT_IMAGE_PREFIX)
            require(reference.startsWith(SAVE_SLOT_IMAGE_PREFIX) && resourceId in imageEntries) {
                "存档缺少图片资源"
            }
        }

        private suspend fun materializeImage(reference: String): String {
            if (reference.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return reference
            val resourceId = reference.removePrefix(SAVE_SLOT_IMAGE_PREFIX)
            restoredImagePaths[resourceId]?.let { return it }
            val entry = imageEntries[resourceId] ?: error("存档缺少图片资源")
            val extension = File(entry.name).extension.safeExtension("jpg")
            val directory = File(
                context.filesDir,
                "images/save_slots/${slot.id.safeSegment()}"
            ).also(File::mkdirs).canonicalFile
            val finalFile = File(directory, "${resourceId.safeSegment()}-${UUID.randomUUID()}.$extension")
                .canonicalFile
            require(finalFile.parentFile == directory)
            val tempFile = File(directory, ".${finalFile.name}.${UUID.randomUUID()}.part")
            try {
                zip.getInputStream(entry).use { input ->
                    tempFile.outputStream().buffered().use { output -> copyCancellable(input, output) }
                }
                require(tempFile.length() > 0L) { "存档图片资源为空" }
                moveReplacing(tempFile, finalFile)
                createdImages += finalFile.absolutePath
                restoredImagePaths[resourceId] = finalFile.absolutePath
                return finalFile.absolutePath
            } catch (error: Throwable) {
                tempFile.delete()
                finalFile.delete()
                throw error
            }
        }

        private suspend fun <T> forEachJsonLine(
            entryName: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            required: Boolean = true,
            action: suspend (T) -> Unit
        ) {
            val entry = zip.getEntry(entryName)
            if (entry == null) {
                if (required) error("存档包缺少 $entryName")
                return
            }
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    action(json.decodeFromString(serializer, line))
                }
            }
        }

        private fun resourceEntries(directory: String): Map<String, ZipEntry> =
            zip.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.startsWith(directory) }
                .associateBy { entry -> File(entry.name).nameWithoutExtension }

        override fun close() {
            zip.close()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createPackage(
        baseSlot: SaveSlot,
        imagePolicy: SaveSlotImagePolicy,
        includeAudio: Boolean,
        messageSource: suspend (emit: suspend (ChatMessage) -> Unit) -> Unit,
        ragSource: suspend (emit: suspend (VectorChunk) -> Unit) -> Unit,
        voices: List<GeneratedVoiceMessage>,
        onProgress: (String) -> Unit = {}
    ): SaveSlot = withContext(Dispatchers.IO) {
        root.mkdirs()
        val finalFile = packageFile(baseSlot.id)
        val tempFile = File(root, ".${finalFile.name}.${UUID.randomUUID()}.part")
        val imagePlans = mutableListOf<MediaPlan>()
        val audioPlans = mutableListOf<MediaPlan>()
        val imageResourceByPath = linkedMapOf<String, String>()
        var messageCount = 0
        var imageCount = 0
        var ragCount = 0
        var audioCount = 0

        fun packageImage(path: String): String {
            imageCount++
            if (path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)) return path
            imageResourceByPath[path]?.let { resourceId ->
                return when (imagePolicy) {
                    SaveSlotImagePolicy.NONE -> OMITTED_SAVE_SLOT_IMAGE_PREFIX + resourceId
                    else -> SAVE_SLOT_IMAGE_PREFIX + resourceId
                }
            }
            val resourceId = "img-${UUID.randomUUID()}"
            imageResourceByPath[path] = resourceId
            if (imagePolicy == SaveSlotImagePolicy.NONE) {
                return OMITTED_SAVE_SLOT_IMAGE_PREFIX + resourceId
            }
            val source = File(path)
            require(source.isFile) { "图片文件不存在，无法写入存档：${source.name.ifBlank { path }}" }
            val copyOriginal = imagePolicy == SaveSlotImagePolicy.ORIGINAL ||
                source.extension.equals("gif", ignoreCase = true)
            val extension = if (copyOriginal) source.extension.safeExtension("jpg") else "jpg"
            imagePlans += MediaPlan(
                source = source,
                resourceId = resourceId,
                entryName = "$IMAGE_DIR$resourceId.$extension",
                compressImage = !copyOriginal
            )
            return SAVE_SLOT_IMAGE_PREFIX + resourceId
        }

        val packagedBackground = baseSlot.chatBackground
            ?.takeIf(String::isNotBlank)
            ?.let { path ->
                if (imagePolicy == SaveSlotImagePolicy.NONE) {
                    null
                } else {
                    packageImage(path)
                }
            }

        try {
            ZipOutputStream(tempFile.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(MESSAGES_ENTRY))
                messageSource { message ->
                    currentCoroutineContext().ensureActive()
                    val images = message.images.map(::packageImage)
                    val packagedByPath = message.images.zip(images).toMap()
                    val packaged = message.copy(
                        images = images,
                        generatedImageMetadata = message.generatedImageMetadata.mapNotNull { metadata ->
                            packagedByPath[metadata.imagePath]?.let { packagedPath ->
                                metadata.copy(imagePath = packagedPath)
                            }
                        }
                    )
                    zip.write(json.encodeToString(ChatMessage.serializer(), packaged).toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                    messageCount++
                    if (messageCount % 25 == 0) onProgress("正在写入消息：$messageCount 条")
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(RAG_ENTRY))
                ragSource { chunk ->
                    currentCoroutineContext().ensureActive()
                    zip.write(json.encodeToString(VectorChunk.serializer(), chunk).toByteArray(Charsets.UTF_8))
                    zip.write('\n'.code)
                    ragCount++
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(VOICES_ENTRY))
                if (includeAudio) {
                    voices.forEach { voice ->
                        currentCoroutineContext().ensureActive()
                        val source = File(voice.audioPath)
                        require(source.isFile) { "语音文件不存在，无法写入存档：${voice.id}" }
                        val resourceId = "audio-${UUID.randomUUID()}"
                        audioPlans += MediaPlan(
                            source = source,
                            resourceId = resourceId,
                            entryName = "$AUDIO_DIR$resourceId.${source.extension.safeExtension("mp3")}",
                            compressImage = false
                        )
                        val packaged = voice.copy(audioPath = SAVE_SLOT_AUDIO_PREFIX + resourceId)
                        zip.write(json.encodeToString(GeneratedVoiceMessage.serializer(), packaged).toByteArray(Charsets.UTF_8))
                        zip.write('\n'.code)
                        audioCount++
                    }
                }
                zip.closeEntry()

                imagePlans.forEachIndexed { index, plan ->
                    currentCoroutineContext().ensureActive()
                    onProgress("正在写入图片：${index + 1}/${imagePlans.size}")
                    zip.putNextEntry(ZipEntry(plan.entryName))
                    if (plan.compressImage) writeCompressedImage(plan.source, zip) else copyFile(plan.source, zip)
                    zip.closeEntry()
                }
                audioPlans.forEachIndexed { index, plan ->
                    currentCoroutineContext().ensureActive()
                    onProgress("正在写入语音：${index + 1}/${audioPlans.size}")
                    zip.putNextEntry(ZipEntry(plan.entryName))
                    copyFile(plan.source, zip)
                    zip.closeEntry()
                }

                val packageRef = SaveSlotPackageRef(
                    fileName = finalFile.name,
                    messageCount = messageCount,
                    imageCount = imageCount,
                    audioCount = audioCount,
                    ragChunkCount = ragCount
                )
                val manifest = baseSlot.copy(
                    schemaVersion = SCHEMA_VERSION,
                    chatBackground = packagedBackground,
                    messages = emptyList(),
                    imageResources = emptyMap(),
                    voiceMessages = emptyList(),
                    audioResources = emptyMap(),
                    vectorChunks = emptyList(),
                    imagePolicy = imagePolicy,
                    includeAudio = includeAudio,
                    packageRef = packageRef
                )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                json.encodeToStream(SaveSlot.serializer(), manifest, zip)
                zip.closeEntry()
            }
            require(tempFile.length() > 0L) { "存档包为空" }
            moveReplacing(tempFile, finalFile)
            val manifest = readManifest(finalFile)
            manifest.copy(packageRef = manifest.packageRef?.copy(byteLength = finalFile.length()))
        } catch (error: Throwable) {
            tempFile.delete()
            finalFile.delete()
            throw error
        }
    }

    suspend fun export(slot: SaveSlot, output: OutputStream) = withContext(Dispatchers.IO) {
        val file = resolvePackageFile(slot)
        file.inputStream().buffered().use { input -> copyCancellable(input, output) }
        output.flush()
    }

    suspend fun importPackage(
        input: InputStream,
        targetSessionId: String,
        targetName: (String) -> String
    ): ImportedPackage = withContext(Dispatchers.IO) {
        root.mkdirs()
        val staged = File(root, ".import-${UUID.randomUUID()}.part")
        try {
            staged.outputStream().buffered().use { output -> copyCancellable(input, output) }
            require(staged.length() > 0L) { "存档包为空" }
            val decoded = readManifest(staged)
            require(decoded.schemaVersion == SCHEMA_VERSION && decoded.packageRef != null) {
                "不支持的存档包格式"
            }
            val newId = UUID.randomUUID().toString()
            val finalFile = packageFile(newId)
            val imported = decoded.copy(
                id = newId,
                sessionId = targetSessionId,
                name = targetName(decoded.name),
                createdAt = System.currentTimeMillis(),
                packageRef = decoded.packageRef.copy(
                    fileName = finalFile.name,
                    byteLength = staged.length()
                )
            )
            openReader(staged, imported).use { reader -> reader.validate() }
            moveReplacing(staged, finalFile)
            ImportedPackage(imported)
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    fun openReader(slot: SaveSlot): Reader = openReader(resolvePackageFile(slot), slot)

    fun isPackageStream(input: PushbackInputStream): Boolean {
        val header = ByteArray(4)
        val count = input.read(header)
        if (count > 0) input.unread(header, 0, count)
        return count >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
    }

    fun delete(slot: SaveSlot): Boolean {
        val reference = slot.packageRef ?: return true
        return runCatching {
            val file = File(root, reference.fileName).canonicalFile
            val ownedRoot = root.canonicalFile
            file.parentFile == ownedRoot && (!file.exists() || file.delete())
        }.getOrDefault(false)
    }

    fun deleteBySlotId(slotId: String): Boolean = runCatching {
        val file = packageFile(slotId)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    fun cleanupPartialFiles() {
        root.listFiles { file -> file.isFile && (file.extension == "part" || file.name.startsWith(".")) }
            .orEmpty()
            .forEach(File::delete)
    }

    private fun openReader(file: File, slot: SaveSlot): Reader =
        Reader(context, slot, ZipFile(file), json)

    @OptIn(ExperimentalSerializationApi::class)
    private fun readManifest(file: File): SaveSlot = ZipFile(file).use { zip ->
        val entry = zip.getEntry(MANIFEST_ENTRY) ?: error("存档包缺少 $MANIFEST_ENTRY")
        zip.getInputStream(entry).buffered().use { input ->
            json.decodeFromStream(SaveSlot.serializer(), input)
        }
    }

    private fun resolvePackageFile(slot: SaveSlot): File {
        val reference = requireNotNull(slot.packageRef) { "此存档不是 v8 流式包" }
        val file = File(root, reference.fileName).canonicalFile
        require(file.parentFile == root.canonicalFile && file.isFile) { "存档包文件不存在" }
        return file
    }

    private fun packageFile(slotId: String): File {
        val file = File(root, "${slotId.safeSegment()}.cbsave").canonicalFile
        require(file.parentFile == root.canonicalFile)
        return file
    }

    private data class MediaPlan(
        val source: File,
        val resourceId: String,
        val entryName: String,
        val compressImage: Boolean
    )
}

private suspend fun copyFile(file: File, output: OutputStream) {
    file.inputStream().buffered().use { input -> copyCancellable(input, output) }
}

private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
}

private fun writeCompressedImage(source: File, output: OutputStream) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码：${source.name}" }
    var sample = 1
    while (
        bounds.outWidth / sample > MAX_COMPRESSED_IMAGE_EDGE ||
        bounds.outHeight / sample > MAX_COMPRESSED_IMAGE_EDGE
    ) {
        sample *= 2
    }
    val bitmap = BitmapFactory.decodeFile(
        source.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    ) ?: error("图片无法解码：${source.name}")
    try {
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
            "图片压缩失败：${source.name}"
        }
    } finally {
        bitmap.recycle()
    }
}

private fun moveReplacing(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun String.safeSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "resource" }

private fun String.safeExtension(fallback: String): String =
    lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: fallback
