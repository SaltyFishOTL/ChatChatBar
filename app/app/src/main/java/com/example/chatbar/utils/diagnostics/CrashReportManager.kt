package com.example.chatbar.utils.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.FileProvider
import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.diagnostics.CrashDiagnosticReport
import com.example.chatbar.domain.diagnostics.CrashDiagnosticReportFormatter
import com.example.chatbar.domain.diagnostics.DiagnosticAppInfo
import com.example.chatbar.domain.diagnostics.DiagnosticDeviceInfo
import com.example.chatbar.domain.diagnostics.DiagnosticSystemExitInfo
import com.example.chatbar.domain.diagnostics.DiagnosticThrowableCause
import com.example.chatbar.domain.diagnostics.DiagnosticThrowableInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingCrashReport(
    val createdAt: Long,
    val trigger: String
)

object CrashReportManager {
    private const val TAG = "CrashReportManager"
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val SHARE_DIR = "share"
    private const val PENDING_REPORT_FILE = "pending-crash-report.txt"
    private const val BREADCRUMB_FILE = "breadcrumbs.txt"
    private const val PREFS_NAME = "crash_diagnostics"
    private const val PREF_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_BREADCRUMBS = 30
    private const val MAX_CAUSES = 8
    private const val MAX_STACK_FRAMES_PER_CAUSE = 100
    private const val EXIT_LOOKBACK_MS = 7L * 24L * 60L * 60L * 1000L
    private const val REPORT_EXIT_MATCH_WINDOW_MS = 5L * 60L * 1000L

    private val stateLock = Any()
    private val breadcrumbs = ArrayDeque<String>()
    private val _pendingReport = MutableStateFlow<PendingCrashReport?>(null)
    val pendingReport: StateFlow<PendingCrashReport?> = _pendingReport.asStateFlow()

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    fun initialize(context: Context) {
        synchronized(stateLock) {
            if (initialized) return
            appContext = context.applicationContext
            installExceptionHandler()
            runCatching {
                diagnosticsDir().mkdirs()
                shareDir().mkdirs()
                loadBreadcrumbs().takeLast(MAX_BREADCRUMBS).forEach(breadcrumbs::addLast)
            }.onFailure { Log.w(TAG, "Unable to initialize diagnostic storage", it) }
            initialized = true
        }

        runCatching { recoverLatestSystemExit() }
            .onFailure { Log.w(TAG, "Unable to recover latest system exit", it) }
        beginNewSession()
        refreshPendingState()
    }

    fun recordBreadcrumb(category: String, message: String) {
        if (!initialized) return
        val entry = "${System.currentTimeMillis()} | " +
            CrashDiagnosticReportFormatter.sanitizeBreadcrumb(category, message)
        val snapshot = synchronized(stateLock) {
            breadcrumbs.addLast(entry)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            breadcrumbs.toList()
        }
        runCatching { writeAtomic(breadcrumbFile(), snapshot.joinToString("\n")) }
            .onFailure { Log.w(TAG, "Unable to persist diagnostic breadcrumb", it) }
    }

    fun sharePendingReport(context: Context): Result<Unit> = runCatching {
        check(initialized) { "崩溃诊断尚未初始化" }
        val summary = _pendingReport.value ?: error("没有待发送的崩溃报告")
        val content = readAtomic(pendingReportFile()) ?: error("崩溃报告文件不存在")
        val targetDir = shareDir().apply { mkdirs() }
        targetDir.listFiles()?.forEach(File::delete)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(summary.createdAt))
        val target = File(targetDir, "ChatBar-crash-report-$stamp.txt")
        target.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ChatBar 崩溃诊断报告")
            clipData = ClipData.newUri(context.contentResolver, "ChatBar crash report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "发送崩溃诊断报告")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        recordBreadcrumb("diagnostics", "share_crash_report")
    }

    fun deletePendingReport() {
        if (!initialized) return
        AtomicFile(pendingReportFile()).delete()
        shareDir().listFiles()?.forEach(File::delete)
        _pendingReport.value = null
        recordBreadcrumb("diagnostics", "delete_crash_report")
    }

    private fun installExceptionHandler() {
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeThrowableReport(thread, throwable)
            } catch (_: Throwable) {
                writeMinimalCrashReport(thread, throwable)
            } finally {
                val previous = previousExceptionHandler
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private fun writeThrowableReport(thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val report = CrashDiagnosticReport(
            generatedAt = now,
            trigger = "未捕获 JVM/Kotlin 异常",
            app = appInfo(),
            device = deviceInfo(),
            breadcrumbs = breadcrumbSnapshot(),
            throwable = throwableInfo(thread, throwable)
        )
        writeAtomic(pendingReportFile(), CrashDiagnosticReportFormatter.format(report))
    }

    private fun writeMinimalCrashReport(thread: Thread, throwable: Throwable) {
        runCatching {
            val now = System.currentTimeMillis()
            val minimal = buildString {
                appendLine("ChatBar 崩溃诊断报告")
                appendLine("report_timestamp_ms: $now")
                appendLine("触发来源: 未捕获异常（最小报告）")
                appendLine("线程: ${thread.name.take(100)}")
                appendLine("异常: ${throwable.javaClass.name.take(300)}")
                CrashDiagnosticReportFormatter.sanitizeThrowableMessage(throwable.message)?.let {
                    appendLine("消息: $it")
                }
            }
            writeAtomic(pendingReportFile(), minimal)
        }
    }

    private fun throwableInfo(thread: Thread, throwable: Throwable): DiagnosticThrowableInfo {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = mutableListOf<DiagnosticThrowableCause>()
        var current: Throwable? = throwable
        while (current != null && causes.size < MAX_CAUSES && seen.add(current)) {
            causes += DiagnosticThrowableCause(
                type = current.javaClass.name,
                message = CrashDiagnosticReportFormatter.sanitizeThrowableMessage(current.message),
                stackFrames = current.stackTrace
                    .take(MAX_STACK_FRAMES_PER_CAUSE)
                    .map(StackTraceElement::toString)
            )
            current = current.cause
        }
        return DiagnosticThrowableInfo(threadName = thread.name.take(200), causes = causes)
    }

    private fun recoverLatestSystemExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastHandled = prefs.getLong(PREF_LAST_EXIT_TIMESTAMP, 0L)
        val cutoff = System.currentTimeMillis() - EXIT_LOOKBACK_MS
        val result = SystemExitInfoReader.read(appContext, lastHandled, cutoff) ?: return
        val exit = result.abnormalExit
        if (exit == null) {
            prefs.edit().putLong(PREF_LAST_EXIT_TIMESTAMP, result.latestSeenTimestamp).commit()
            return
        }

        val existing = readAtomic(pendingReportFile())
        val existingTimestamp = existing?.let(::extractReportTimestamp)
        if (existing != null && existingTimestamp != null &&
            abs(existingTimestamp - exit.timestamp) <= REPORT_EXIT_MATCH_WINDOW_MS
        ) {
            writeAtomic(pendingReportFile(), mergeSystemExit(existing, exit))
        } else {
            val report = CrashDiagnosticReport(
                generatedAt = exit.timestamp,
                trigger = "Android 系统记录的异常退出",
                app = appInfo(),
                device = deviceInfo(),
                breadcrumbs = breadcrumbSnapshot(),
                systemExit = exit
            )
            writeAtomic(pendingReportFile(), CrashDiagnosticReportFormatter.format(report))
        }
        prefs.edit().putLong(PREF_LAST_EXIT_TIMESTAMP, result.latestSeenTimestamp).commit()
    }

    private fun mergeSystemExit(existing: String, exit: DiagnosticSystemExitInfo): String {
        val section = CrashDiagnosticReportFormatter.formatSystemExitSection(exit)
        val breadcrumbMarker = "\n[最近脱敏操作，最多 30 条]"
        val markerIndex = existing.indexOf(breadcrumbMarker)
        return if (markerIndex >= 0) {
            existing.substring(0, markerIndex) + section + existing.substring(markerIndex)
        } else {
            existing.trimEnd() + section
        }
    }

    private fun beginNewSession() {
        synchronized(stateLock) { breadcrumbs.clear() }
        runCatching { writeAtomic(breadcrumbFile(), "") }
        recordBreadcrumb("lifecycle", "app_started")
    }

    private fun breadcrumbSnapshot(): List<String> = synchronized(stateLock) { breadcrumbs.toList() }

    private fun loadBreadcrumbs(): List<String> = readAtomic(breadcrumbFile())
        ?.lineSequence()
        ?.filter(String::isNotBlank)
        ?.toList()
        .orEmpty()

    private fun refreshPendingState() {
        val content = readAtomic(pendingReportFile())
        _pendingReport.value = content?.let {
            PendingCrashReport(
                createdAt = extractReportTimestamp(it) ?: pendingReportFile().lastModified(),
                trigger = it.lineSequence()
                    .firstOrNull { line -> line.startsWith("触发来源:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "异常退出"
            )
        }
    }

    private fun extractReportTimestamp(content: String): Long? = content.lineSequence()
        .firstOrNull { it.startsWith("report_timestamp_ms:") }
        ?.substringAfter(':')
        ?.trim()
        ?.toLongOrNull()

    private fun appInfo(): DiagnosticAppInfo = DiagnosticAppInfo(
        packageName = appContext.packageName,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE
    )

    private fun deviceInfo(): DiagnosticDeviceInfo = DiagnosticDeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        locale = Locale.getDefault().toLanguageTag()
    )

    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun readAtomic(file: File): String? {
        val atomicFile = AtomicFile(file)
        return runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun diagnosticsDir(): File = File(appContext.filesDir, DIAGNOSTICS_DIR)
    private fun shareDir(): File = File(diagnosticsDir(), SHARE_DIR)
    private fun pendingReportFile(): File = File(diagnosticsDir(), PENDING_REPORT_FILE)
    private fun breadcrumbFile(): File = File(diagnosticsDir(), BREADCRUMB_FILE)

}
