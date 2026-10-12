package com.example.chatbar.domain.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.chatbar.BuildConfig
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString

data class AppBackupImportRequest(val path: String, val consumed: () -> Unit)

class AppBackupService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val flushDrafts: suspend () -> Unit
) {
    private val _state = MutableStateFlow(readLastOperation())
    val state = _state.asStateFlow()
    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen = _dialogOpen.asStateFlow()
    private val _importRequest = MutableStateFlow<AppBackupImportRequest?>(null)
    val importRequest = _importRequest.asStateFlow()
    private val _catalog = MutableStateFlow(BackupCatalogState())
    val catalog = _catalog.asStateFlow()
    private var catalogJob: Job? = null
    private val lock = Any()
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var restoreAdmission: Closeable? = null
    private var cancellationReason: String? = null
    private var selectedExportPassword: CharArray? = null
    private var selectedExportContents: AppBackupSelection? = null
    private var exportSelectionConfigured = false
    private val workspace get() = AppBackupBootstrap.workspace(context)

    init {
        scope.launch(Dispatchers.IO) {
            var lastPhase: AppBackupPhase? = null
            var savedAt = 0L
            state.collect { value ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (value.phase != lastPhase || now - savedAt >= 1000) {
                    val file = File(workspace, "last-operation.json")
                    val temporary = File(workspace, "last-operation.tmp")
                    // Progress contains no password, credential payload, or local data contents.
                    runCatching {
                        temporary.writeText(backupJson.encodeToString(value))
                        java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    lastPhase = value.phase
                    savedAt = now
                }
            }
        }
    }

    private fun readLastOperation(): AppBackupState {
        val file = File(AppBackupBootstrap.workspace(context), "last-operation.json")
        if (!file.isFile) return AppBackupState()
        return runCatching {
            require(file.length() <= 1024 * 1024)
            val saved = backupJson.decodeFromString<AppBackupState>(file.readText())
            if (saved.busy || saved.phase == AppBackupPhase.AWAITING_CONFIRMATION || saved.phase == AppBackupPhase.READY_TO_RESTART) {
                saved.copy(phase = AppBackupPhase.FAILED, error = "上次迁移已中断。原数据已保留，请重新选择文件。")
            } else saved
        }.getOrDefault(AppBackupState(AppBackupPhase.FAILED, error = "上次迁移进度损坏，请重新选择文件。"))
    }

    fun open() { _dialogOpen.value = true; refreshCatalog() }

    fun refreshCatalog() {
        catalogJob?.cancel()
        _catalog.value = _catalog.value.copy(loading = true, error = null)
        catalogJob = scope.launch(Dispatchers.IO) {
            try {
                val coroutine = currentCoroutineContext()
                val catalog = BackupExportCatalog.read(context.filesDir) { coroutine.ensureActive() }
                _catalog.value = BackupCatalogState(catalog = catalog)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _catalog.value = BackupCatalogState(error = error.message ?: "无法读取导出清单") }
        }
    }

    fun configureExport(password: CharArray?, selection: AppBackupSelection? = null) {
        synchronized(lock) {
            selectedExportPassword?.fill('\u0000')
            selectedExportPassword = password
            selectedExportContents = selection
            exportSelectionConfigured = true
        }
    }

    fun exportSelected(uri: Uri) {
        val (password, selection) = synchronized(lock) {
            check(exportSelectionConfigured) { "导出配置已失效，请重新选择导出" }
            exportSelectionConfigured = false
            (selectedExportPassword to selectedExportContents).also {
                selectedExportPassword = null; selectedExportContents = null
            }
        }
        export(uri, password, selection)
    }

    fun cancelExportSelection() {
        synchronized(lock) {
            selectedExportPassword?.fill('\u0000')
            selectedExportPassword = null
            selectedExportContents = null
            exportSelectionConfigured = false
        }
    }

    fun requestImport(path: String, consumed: () -> Unit) {
        check(!state.value.busy && state.value.phase != AppBackupPhase.READY_TO_RESTART &&
            state.value.phase != AppBackupPhase.AWAITING_CONFIRMATION) { "请先处理当前迁移存档" }
        check(_importRequest.value == null) { "已有待处理的全量存档" }
        _importRequest.value = AppBackupImportRequest(path, consumed)
        _dialogOpen.value = true
    }

    fun export(uri: Uri, password: CharArray?, selection: AppBackupSelection? = null) = start(password, failure = { message ->
        val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        if (removed) message else message + "；所选位置可能留有不完整文件，请删除后重试"
    }) {
        flushDrafts()
        LocalDataMaintenance.acquire().use {
            val work = File(workspace, "export").also { it.deleteRecursively(); it.mkdirs() }
            try {
                val activeCoroutine = currentCoroutineContext()
                val catalog = BackupExportCatalog.read(context.filesDir) { activeCoroutine.ensureActive() }
                val plan = catalog.plan(selection ?: catalog.allSelection())
                val total = plan.estimate().bytes
                BackupArchive.ensureSpace(work, Math.multiplyExact(total, 2L))
                val preferences = BackupPreferenceTransfer.capture(context, plan.selection)
                val validation = BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true, selection = plan) {
                    activeCoroutine.ensureActive()
                }
                val archive = File(work, "complete.cbbackup")
                _state.value = AppBackupState(AppBackupPhase.EXPORTING, "正在打包本地数据", totalBytes = total)
                archive.outputStream().buffered().use { output ->
                    engine().create(context.filesDir, work, output, password,
                        BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, preferences, plan)
                }
                _state.value = _state.value.copy(phase = AppBackupPhase.VALIDATING, label = "正在验证存档", doneBytes = 0)
                val verify = File(work, "verify")
                val summary = archive.inputStream().buffered().use { source ->
                    engine().extract(source, password, verify, context.filesDir, BuildConfig.VERSION_CODE, validateOnly = true)
                }
                verify.deleteRecursively()
                _state.value = AppBackupState(AppBackupPhase.EXPORTING, "正在保存文件", totalBytes = archive.length())
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    archive.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            _state.value = _state.value.copy(doneBytes = done)
                        }
                    }
                    output.flush()
                } ?: error("无法写入所选位置")
                _state.value = AppBackupState(AppBackupPhase.COMPLETE, "所选存档已导出", summary = summary,
                    warnings = validation.warnings)
            } finally { work.deleteRecursively() }
        }
    }

    fun inspect(uri: Uri, password: CharArray?) = start(password) {
        prepare(password) {
            context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        }
    }

    fun inspectShared(password: CharArray?) {
        val request = requireNotNull(importRequest.value)
        start(password) {
            prepare(password) { File(request.path).inputStream().buffered() }
            _importRequest.value = null
            request.consumed()
        }
    }

    private suspend fun prepare(password: CharArray?, open: () -> java.io.InputStream) {
        val work = File(workspace, "incoming")
        check(!AppBackupBootstrap.transaction(context).pending()) { "已有待恢复事务，请先重新打开 APP" }
        work.deleteRecursively()
        work.mkdirs()
        var ready = false
        try {
            _state.value = AppBackupState(AppBackupPhase.VALIDATING, "正在校验全量存档")
            val summary = open().use {
                engine().extract(it, password, work, context.filesDir, BuildConfig.VERSION_CODE)
            }
            val activeCoroutine = currentCoroutineContext()
            val validation = BackupEntityValidator.validateTree(context, File(work, "files")) { activeCoroutine.ensureActive() }
            resetDeviceConfirmations(File(work, "files"))
            BackupPreparedSeal.write(work, { activeCoroutine.ensureActive() }, AppBackupBootstrap::syncDirectory)
            ready = true
            _state.value = AppBackupState(
                AppBackupPhase.AWAITING_CONFIRMATION, "存档校验完成，等待确认恢复", summary = summary,
                warnings = validation.warnings
            )
        } finally { if (!ready) work.deleteRecursively() }
    }

    fun discardRestorePreview() {
        check(state.value.phase == AppBackupPhase.AWAITING_CONFIRMATION)
        restoreAdmission?.close()
        restoreAdmission = null
        _state.value = _state.value.copy(restorePreview = null, error = null)
        cleanup = scope.launch(Dispatchers.IO) { File(workspace, "prepared").deleteRecursively() }
    }

    fun previewRestore(mode: AppBackupRestoreMode) {
        check(state.value.phase == AppBackupPhase.AWAITING_CONFIRMATION)
        check(restoreAdmission == null && state.value.restorePreview == null) { "请先修改导入模式或取消已有预览" }
        val inspected = state.value.copy(restorePreview = null, error = null)
        require(requireNotNull(inspected.summary).requiresRestorePreview(mode)) { "旧版全量存档不需要选择范围预览" }
        start(null, recoverState = inspected) {
            flushDrafts()
            val lease = LocalDataMaintenance.acquire()
            val work = File(workspace, "prepared")
            var ready = false
            try {
                BackupPreferenceTransfer.flush(context)
                check(!AppBackupBootstrap.transaction(context).pending()) { "已有待恢复事务" }
                work.deleteRecursively()
                work.mkdirs()
                val coroutine = currentCoroutineContext()
                _state.value = _state.value.copy(phase = AppBackupPhase.PREPARING, label = "正在比较并准备恢复数据")
                val preview = BackupMergeEngine(context.filesDir, File(workspace, "incoming"), work,
                    requireNotNull(inspected.summary), mode, { coroutine.ensureActive() }, { bytes, files, label ->
                        _state.value = _state.value.copy(doneBytes = bytes, doneFiles = files, label = label)
                    }).build()
                val validation = BackupEntityValidator.validateTree(context, File(work, "files")) { coroutine.ensureActive() }
                BackupPreparedSeal.write(work, { coroutine.ensureActive() }, AppBackupBootstrap::syncDirectory)
                restoreAdmission = lease
                ready = true
                _state.value = inspected.copy(label = "恢复预览完成，确认后导入", restorePreview = preview,
                    warnings = (inspected.warnings + validation.warnings + preview.warnings).distinct())
            } finally {
                if (!ready) { work.deleteRecursively(); lease.close() }
            }
        }
    }

    fun prepareRestore(mode: AppBackupRestoreMode = AppBackupRestoreMode.REPLACE_ALL) {
        check(state.value.phase == AppBackupPhase.AWAITING_CONFIRMATION)
        val needsPreview = requireNotNull(state.value.summary).requiresRestorePreview(mode)
        if (needsPreview) check(state.value.restorePreview?.mode == mode && restoreAdmission != null) {
            "请先预览恢复结果"
        }
        val inspected = state.value
        synchronized(lock) {
            if (operation?.isActive == true) return
            _state.value = inspected.copy(phase = AppBackupPhase.PREPARING, label = "正在记录恢复事务")
            operation = scope.launch(Dispatchers.IO) {
                try {
                    val lease = restoreAdmission ?: LocalDataMaintenance.acquire()
                    try {
                        currentCoroutineContext().ensureActive()
                        BackupPreferenceTransfer.flush(context)
                        currentCoroutineContext().ensureActive()
                        AppBackupBootstrap.transaction(context).schedule(if (needsPreview) "prepared" else "incoming")
                        restoreAdmission = lease
                        _state.value = _state.value.copy(
                            phase = AppBackupPhase.READY_TO_RESTART, label = "已准备恢复，请关闭并重新打开 APP", error = null
                        )
                    } catch (error: Throwable) {
                        if (AppBackupBootstrap.transaction(context).pending()) {
                            // A durable intent may exist even when the final directory fsync reports failure.
                            restoreAdmission = lease
                            _state.value = inspected.copy(phase = AppBackupPhase.READY_TO_RESTART,
                                label = "恢复事务已记录，请关闭并重新打开 APP",
                                error = "事务记录尚需启动时核对：${error.message ?: "写入失败"}")
                        } else {
                            if (restoreAdmission == null) lease.close()
                            throw error
                        }
                    }
                } catch (error: Exception) {
                    _state.value = inspected.copy(error = if (error is CancellationException) cancellationReason ?: "恢复准备已取消" else error.message ?: "无法准备恢复")
                }
            }
        }
    }

    fun cancel(reason: String = "迁移已取消") {
        if (state.value.phase == AppBackupPhase.READY_TO_RESTART) return
        if (cancellationReason == null) cancellationReason = reason
        operation?.cancel()
    }

    fun dismiss() {
        if (state.value.busy || state.value.phase == AppBackupPhase.READY_TO_RESTART) return
        val request = _importRequest.value
        _importRequest.value = null
        _state.value = AppBackupState()
        _dialogOpen.value = false
        cancelExportSelection()
        catalogJob?.cancel()
        _catalog.value = BackupCatalogState()
        restoreAdmission?.close()
        restoreAdmission = null
        cleanup = scope.launch(Dispatchers.IO) {
            operation?.join()
            File(workspace, "prepared").deleteRecursively()
            File(workspace, "incoming").deleteRecursively()
            request?.consumed?.invoke()
        }
    }

    private fun start(password: CharArray?, failure: ((String) -> String)? = null,
        recoverState: AppBackupState? = null, action: suspend () -> Unit) {
        synchronized(lock) {
            check(operation?.isCompleted != false) { "数据迁移或取消清理尚未完成，请稍后重试" }
            cancellationReason = null
            _state.value = AppBackupState(AppBackupPhase.PREPARING, "正在准备迁移")
            _dialogOpen.value = true
            operation = scope.launch(Dispatchers.IO) {
                try {
                    cleanup?.join()
                    LocalDataTransferForegroundService.runProtected(context, ::cancel, action)
                } catch (error: CancellationException) {
                    val reason = cancellationReason ?: "迁移已取消"
                    _state.value = recoverState?.copy(error = reason, label = "恢复准备已取消，原数据保留")
                        ?: AppBackupState(AppBackupPhase.FAILED, error = failure?.invoke(reason) ?: reason)
                } catch (error: Exception) {
                    val reason = if (error is kotlinx.serialization.SerializationException) {
                        "存档或本地记录格式损坏，请检查数据后重试"
                    } else error.message ?: "迁移失败，请重试"
                    _state.value = recoverState?.copy(error = reason, label = "恢复准备失败，原数据保留")
                        ?: AppBackupState(AppBackupPhase.FAILED, error = failure?.invoke(reason) ?: reason)
                } finally {
                    password?.fill('\u0000')
                }
            }
        }
    }

    private fun resetDeviceConfirmations(files: File) {
        val file = File(files, "entities/app_settings.json")
        if (!file.isFile) return
        val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(kotlinx.serialization.json.JsonObject(
            root + ("momentsAutoStartConfirmed" to kotlinx.serialization.json.JsonPrimitive(false))
        ).toString())
    }

    private suspend fun engine(): BackupArchive {
        val coroutine = currentCoroutineContext()
        return BackupArchive(
            checkActive = { coroutine.ensureActive() },
            progress = { bytes, files, label ->
                val title = when {
                    label.startsWith("entities/chat_") -> "正在处理聊天记录"
                    label.startsWith("entities/novelai_") -> "正在处理生图记录与工作室"
                    label.startsWith("images/") -> "正在处理图片"
                    label.startsWith("audio/") -> "正在处理音频"
                    label.contains('/') -> "正在处理本地数据"
                    else -> label
                }
                _state.value = _state.value.copy(doneBytes = bytes, doneFiles = files, label = title)
            },
            openExternal = { path ->
                if (path.startsWith("content:")) {
                    context.contentResolver.openInputStream(Uri.parse(path)) ?: error("外部资源读取失败")
                } else if (path.startsWith("file:")) File(java.net.URI(path)).inputStream().buffered()
                else File(path).inputStream().buffered()
            }
        )
    }
}
