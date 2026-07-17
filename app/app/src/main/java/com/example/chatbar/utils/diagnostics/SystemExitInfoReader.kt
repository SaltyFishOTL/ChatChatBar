package com.example.chatbar.utils.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import com.example.chatbar.domain.diagnostics.DiagnosticSystemExitInfo
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

internal data class SystemExitReadResult(
    val latestSeenTimestamp: Long,
    val abnormalExit: DiagnosticSystemExitInfo?
)

@RequiresApi(Build.VERSION_CODES.R)
internal object SystemExitInfoReader {
    private const val MAX_TRACE_BYTES = 256 * 1024

    fun read(
        context: Context,
        lastHandledTimestamp: Long,
        cutoffTimestamp: Long
    ): SystemExitReadResult? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val unseen = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp > lastHandledTimestamp && it.timestamp >= cutoffTimestamp }
            .sortedByDescending(ApplicationExitInfo::getTimestamp)
        if (unseen.isEmpty()) return null
        return SystemExitReadResult(
            latestSeenTimestamp = unseen.maxOf(ApplicationExitInfo::getTimestamp),
            abnormalExit = unseen
                .firstOrNull { it.reason.isDiagnosticAbnormalExit() }
                ?.toDiagnosticSystemExitInfo()
        )
    }

    private fun ApplicationExitInfo.toDiagnosticSystemExitInfo(): DiagnosticSystemExitInfo {
        val trace = readSystemTrace(this)
        return DiagnosticSystemExitInfo(
            timestamp = timestamp,
            reasonCode = reason,
            reasonLabel = reason.diagnosticReasonLabel(),
            status = status,
            importance = importance,
            pssKb = pss,
            rssKb = rss,
            description = description,
            trace = trace?.content,
            traceEncoding = trace?.encoding,
            traceTruncated = trace?.truncated == true
        )
    }

    private fun readSystemTrace(exitInfo: ApplicationExitInfo): CapturedTrace? {
        val raw = runCatching { exitInfo.traceInputStream }.getOrNull() ?: return null
        raw.use { input ->
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val first = buffered.read()
            val second = buffered.read()
            buffered.reset()
            val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
            decoded.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var truncated = false
                while (output.size() < MAX_TRACE_BYTES) {
                    val remaining = MAX_TRACE_BYTES - output.size()
                    val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                if (stream.read() >= 0) truncated = true
                val bytes = output.toByteArray()
                if (bytes.isEmpty()) return null
                val printable = bytes.count { byte ->
                    val value = byte.toInt() and 0xff
                    value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 0xC2
                }
                return if (printable * 100 / bytes.size >= 80) {
                    CapturedTrace(
                        content = String(bytes, StandardCharsets.UTF_8),
                        encoding = "utf-8",
                        truncated = truncated
                    )
                } else {
                    CapturedTrace(
                        content = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        encoding = "base64",
                        truncated = truncated
                    )
                }
            }
        }
    }

    private fun Int.isDiagnosticAbnormalExit(): Boolean = this in setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED
    )

    private fun Int.diagnosticReasonLabel(): String = when (this) {
        ApplicationExitInfo.REASON_SIGNALED -> "系统信号终止"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存终止"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过量"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程终止"
        else -> "未知异常退出"
    }

    private data class CapturedTrace(
        val content: String,
        val encoding: String,
        val truncated: Boolean
    )
}
