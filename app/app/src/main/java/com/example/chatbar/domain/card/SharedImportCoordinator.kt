package com.example.chatbar.domain.card

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SharedImportSource {
    data class FileUri(val uri: Uri) : SharedImportSource
    data class TextJson(val text: String) : SharedImportSource
}

data class SharedImportStagedFile(
    val path: String,
    val displayName: String,
    val declaredMimeType: String?,
    val sizeBytes: Long
)

enum class SharedImageDestination {
    GUIDANCE,
    TOOLS
}

enum class SharedImportSection {
    CHARACTER,
    FORMAT,
    WORLD_BOOK,
    MODEL
}

data class SharedImportFocus(
    val queueId: Long,
    val section: SharedImportSection,
    val itemId: String,
    val message: String
)

data class SharedImportConflict(
    val existingId: String,
    val existingName: String
)

sealed interface SharedImportQueueItemState {
    data object Preparing : SharedImportQueueItemState

    data class Ready(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection
    ) : SharedImportQueueItemState

    data class Processing(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection
    ) : SharedImportQueueItemState

    data class AwaitingUnknown(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection.Unknown,
        val manualError: String? = null
    ) : SharedImportQueueItemState

    data class AwaitingConflict(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection,
        val conflict: SharedImportConflict
    ) : SharedImportQueueItemState

    data class AwaitingImageChoice(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection.Image
    ) : SharedImportQueueItemState

    data class ImageHandoff(
        val staged: SharedImportStagedFile,
        val inspection: SharedImportInspection.Image,
        val destination: SharedImageDestination,
        val attempt: Int
    ) : SharedImportQueueItemState

    data class Error(
        val staged: SharedImportStagedFile?,
        val inspection: SharedImportInspection?,
        val message: String
    ) : SharedImportQueueItemState

    data class Completed(
        val staged: SharedImportStagedFile,
        val focus: SharedImportFocus
    ) : SharedImportQueueItemState
}

data class SharedImportQueueItem(
    val id: Long,
    val displayName: String,
    val state: SharedImportQueueItemState
)

data class SharedImportQueueState(
    val active: SharedImportQueueItem? = null,
    val pendingCount: Int = 0
)

data class SharedImageImportRequest(
    val queueId: Long,
    val path: String,
    val displayName: String,
    val destination: SharedImageDestination,
    val attempt: Int
)

class SharedImportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private data class Record(
        val id: Long,
        val source: SharedImportSource,
        var item: SharedImportQueueItem
    )

    private val lock = Any()
    private val stagingDirectory = File(context.filesDir, "shared-import")
    private val records = SharedImportFifoQueue<Record>()
    private val _queueState = MutableStateFlow(SharedImportQueueState())
    val queueState: StateFlow<SharedImportQueueState> = _queueState.asStateFlow()
    private var nextId = 0L

    init {
        stagingDirectory.listFiles().orEmpty().forEach(File::delete)
    }

    fun enqueue(source: SharedImportSource): Long {
        val id: Long
        synchronized(lock) {
            id = ++nextId
            val label = when (source) {
                is SharedImportSource.FileUri -> source.uri.lastPathSegment ?: "共享文件"
                is SharedImportSource.TextJson -> "共享文本.json"
            }
            records.add(Record(
                id = id,
                source = source,
                item = SharedImportQueueItem(id, label, SharedImportQueueItemState.Preparing)
            ))
            publishLocked()
        }
        scope.launch { prepare(id) }
        return id
    }

    fun claimReady(id: Long): Boolean = updateActive(id) { current ->
        val ready = current.state as? SharedImportQueueItemState.Ready ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.Processing(ready.staged, ready.inspection))
    }

    fun awaitUnknown(id: Long): Boolean = updateActive(id) { current ->
        val processing = current.state as? SharedImportQueueItemState.Processing ?: return@updateActive null
        val unknown = processing.inspection as? SharedImportInspection.Unknown ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.AwaitingUnknown(processing.staged, unknown))
    }

    fun awaitConflict(id: Long, conflict: SharedImportConflict): Boolean = updateActive(id) { current ->
        val processing = current.state as? SharedImportQueueItemState.Processing ?: return@updateActive null
        current.copy(
            state = SharedImportQueueItemState.AwaitingConflict(
                processing.staged,
                processing.inspection,
                conflict
            )
        )
    }

    fun claimConflict(id: Long): Boolean = updateActive(id) { current ->
        val conflict = current.state as? SharedImportQueueItemState.AwaitingConflict ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.Processing(conflict.staged, conflict.inspection))
    }

    fun awaitImageChoice(id: Long): Boolean = updateActive(id) { current ->
        val processing = current.state as? SharedImportQueueItemState.Processing ?: return@updateActive null
        val image = processing.inspection as? SharedImportInspection.Image ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.AwaitingImageChoice(processing.staged, image))
    }

    fun chooseImageDestination(id: Long, destination: SharedImageDestination): Boolean = updateActive(id) { current ->
        val choice = current.state as? SharedImportQueueItemState.AwaitingImageChoice ?: return@updateActive null
        if (choice.inspection.info.animatedGif && destination == SharedImageDestination.GUIDANCE) {
            return@updateActive null
        }
        current.copy(
            state = SharedImportQueueItemState.ImageHandoff(
                staged = choice.staged,
                inspection = choice.inspection,
                destination = destination,
                attempt = 1
            )
        )
    }

    fun currentImageRequest(): SharedImageImportRequest? {
        val active = queueState.value.active ?: return null
        val handoff = active.state as? SharedImportQueueItemState.ImageHandoff ?: return null
        return SharedImageImportRequest(
            queueId = active.id,
            path = handoff.staged.path,
            displayName = handoff.staged.displayName,
            destination = handoff.destination,
            attempt = handoff.attempt
        )
    }

    fun markCompleted(id: Long, focus: SharedImportFocus): Boolean = updateActive(id) { current ->
        val processing = current.state as? SharedImportQueueItemState.Processing ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.Completed(processing.staged, focus))
    }

    fun acknowledgeCompleted(id: Long): Boolean = removeActive(id)

    fun completeImageHandoff(id: Long): Boolean = removeActive(id)

    fun fail(id: Long, message: String): Boolean = updateActive(id) { current ->
        val (staged, inspection) = current.state.payload()
        current.copy(state = SharedImportQueueItemState.Error(staged, inspection, message))
    }

    fun retry(id: Long): Boolean {
        var restage = false
        val updated = updateActive(id) { current ->
            val error = current.state as? SharedImportQueueItemState.Error ?: return@updateActive null
            if (error.staged != null && error.inspection != null) {
                current.copy(state = SharedImportQueueItemState.Ready(error.staged, error.inspection))
            } else {
                restage = true
                current.copy(state = SharedImportQueueItemState.Preparing)
            }
        }
        if (updated && restage) scope.launch { prepare(id) }
        return updated
    }

    fun retryImageHandoff(id: Long): Boolean = updateActive(id) { current ->
        val error = current.state as? SharedImportQueueItemState.Error ?: return@updateActive null
        val staged = error.staged ?: return@updateActive null
        val image = error.inspection as? SharedImportInspection.Image ?: return@updateActive null
        current.copy(state = SharedImportQueueItemState.AwaitingImageChoice(staged, image))
    }

    fun tryManual(id: Long, kind: SharedImportKind) {
        val state = queueState.value.active?.takeIf { it.id == id }?.state
            as? SharedImportQueueItemState.AwaitingUnknown ?: return
        if (!updateActive(id) { current ->
                current.copy(state = SharedImportQueueItemState.Processing(state.staged, state.inspection))
            }
        ) return
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { File(state.staged.path).readBytes() }
                SharedImportClassifier.decodeAs(bytes, kind, state.staged.displayName)
            }.fold(
                onSuccess = { inspection ->
                    updateActive(id) { current ->
                        current.copy(state = SharedImportQueueItemState.Ready(state.staged, inspection))
                    }
                },
                onFailure = { error ->
                    updateActive(id) { current ->
                        current.copy(
                            state = SharedImportQueueItemState.AwaitingUnknown(
                                state.staged,
                                state.inspection,
                                error.message ?: "无法按所选类型解析"
                            )
                        )
                    }
                }
            )
        }
    }

    fun cancel(id: Long): Boolean = removeActive(id)

    private suspend fun prepare(id: Long) {
        val record = synchronized(lock) { records.find { it.id == id } } ?: return
        runCatching { stage(record) }.fold(
            onSuccess = { staged ->
                val inspection = runCatching {
                    val bytes = File(staged.path).readBytes()
                    SharedImportClassifier.inspect(bytes, staged.displayName, probeImage(staged.path))
                }.getOrElse { error ->
                    val retained = updateActiveOrQueued(id) { current ->
                        current.copy(
                            displayName = staged.displayName,
                            state = SharedImportQueueItemState.Error(staged, null, error.message ?: "无法读取共享文件")
                        )
                    }
                    if (!retained) File(staged.path).delete()
                    return@fold
                }
                val retained = updateActiveOrQueued(id) { current ->
                    current.copy(
                        displayName = staged.displayName,
                        state = SharedImportQueueItemState.Ready(staged, inspection)
                    )
                }
                if (!retained) File(staged.path).delete()
            },
            onFailure = { error ->
                updateActiveOrQueued(id) { current ->
                    current.copy(
                        state = SharedImportQueueItemState.Error(
                            staged = null,
                            inspection = null,
                            message = error.message ?: "无法暂存共享文件"
                        )
                    )
                }
            }
        )
    }

    private fun stage(record: Record): SharedImportStagedFile {
        stagingDirectory.mkdirs()
        val target = File(stagingDirectory, "shared-${record.id}-${UUID.randomUUID()}.bin")
        return try {
            val (displayName, declaredMimeType) = when (val source = record.source) {
                is SharedImportSource.FileUri -> {
                    val resolver = context.contentResolver
                    val name = queryDisplayName(source.uri) ?: source.uri.lastPathSegment ?: "共享文件"
                    resolver.openInputStream(source.uri)?.use { input ->
                        target.outputStream().buffered().use { output -> copyBounded(input, output) }
                    } ?: error("无法打开共享文件")
                    name to resolver.getType(source.uri)
                }
                is SharedImportSource.TextJson -> {
                    val bytes = source.text.toByteArray(Charsets.UTF_8)
                    require(bytes.size.toLong() in 1..MAX_INPUT_BYTES) { "共享文本为空或超过 100 MB" }
                    target.writeBytes(bytes)
                    "共享文本.json" to "application/json"
                }
            }
            require(target.length() in 1..MAX_INPUT_BYTES) { "共享文件为空或超过 100 MB" }
            SharedImportStagedFile(
                path = target.absolutePath,
                displayName = displayName,
                declaredMimeType = declaredMimeType,
                sizeBytes = target.length()
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun probeImage(path: String): SharedImportImageInfo? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val file = File(path)
        val signature = file.inputStream().buffered().use { input -> ByteArray(6).also { input.read(it) } }
        val animatedGif = signature.toString(Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }
        return SharedImportImageInfo(
            mimeType = options.outMimeType ?: if (animatedGif) "image/gif" else "image/*",
            width = options.outWidth,
            height = options.outHeight,
            animatedGif = animatedGif
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_INPUT_BYTES) { "共享文件超过 100 MB" }
            output.write(buffer, 0, count)
        }
    }

    private fun updateActive(id: Long, transform: (SharedImportQueueItem) -> SharedImportQueueItem?): Boolean =
        synchronized(lock) {
            val record = records.firstOrNull() ?: return@synchronized false
            if (record.id != id) return@synchronized false
            val updated = transform(record.item) ?: return@synchronized false
            record.item = updated
            publishLocked()
            true
        }

    private fun updateActiveOrQueued(
        id: Long,
        transform: (SharedImportQueueItem) -> SharedImportQueueItem
    ): Boolean = synchronized(lock) {
            val record = records.find { it.id == id } ?: return@synchronized false
            record.item = transform(record.item)
            publishLocked()
            true
        }

    private fun removeActive(id: Long): Boolean {
        val stagedPath: String?
        synchronized(lock) {
            val record = records.firstOrNull() ?: return false
            if (record.id != id) return false
            stagedPath = record.item.state.payload().first?.path
            records.removeFirst(record)
            publishLocked()
        }
        stagedPath?.let { File(it).delete() }
        return true
    }

    private fun publishLocked() {
        _queueState.value = SharedImportQueueState(
            active = records.firstOrNull()?.item,
            pendingCount = (records.size - 1).coerceAtLeast(0)
        )
    }

    private fun SharedImportQueueItemState.payload(): Pair<SharedImportStagedFile?, SharedImportInspection?> = when (this) {
        SharedImportQueueItemState.Preparing -> null to null
        is SharedImportQueueItemState.Ready -> staged to inspection
        is SharedImportQueueItemState.Processing -> staged to inspection
        is SharedImportQueueItemState.AwaitingUnknown -> staged to inspection
        is SharedImportQueueItemState.AwaitingConflict -> staged to inspection
        is SharedImportQueueItemState.AwaitingImageChoice -> staged to inspection
        is SharedImportQueueItemState.ImageHandoff -> staged to inspection
        is SharedImportQueueItemState.Error -> staged to inspection
        is SharedImportQueueItemState.Completed -> staged to null
    }

    private companion object {
        const val MAX_INPUT_BYTES = 100L * 1024 * 1024
    }
}
