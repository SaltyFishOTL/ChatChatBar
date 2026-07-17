package com.example.chatbar.domain.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticAppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String
)

data class DiagnosticDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val locale: String
)

data class DiagnosticThrowableInfo(
    val threadName: String,
    val causes: List<DiagnosticThrowableCause>
)

data class DiagnosticThrowableCause(
    val type: String,
    val message: String?,
    val stackFrames: List<String>
)

data class DiagnosticSystemExitInfo(
    val timestamp: Long,
    val reasonCode: Int,
    val reasonLabel: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    val trace: String?,
    val traceEncoding: String?,
    val traceTruncated: Boolean
)

data class CrashDiagnosticReport(
    val generatedAt: Long,
    val trigger: String,
    val app: DiagnosticAppInfo,
    val device: DiagnosticDeviceInfo,
    val breadcrumbs: List<String>,
    val throwable: DiagnosticThrowableInfo? = null,
    val systemExit: DiagnosticSystemExitInfo? = null
)

object CrashDiagnosticReportFormatter {
    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_FIELD_LENGTH = 2_000

    private val bearerSecret = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
    private val assignedSecret = Regex(
        "(?i)\\b(api[_ -]?key|authorization|access[_ -]?token|refresh[_ -]?token|token|" +
            "password|client[_ -]?secret|secret)\\b([\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,;}]+"
    )
    private val openAiStyleSecret = Regex("(?i)\\bsk-[a-z0-9_-]{12,}\\b")
    private val urlUserInfo = Regex("(?i)(https?://)[^/@\\s]+@")
    private val urlQuery = Regex("(?i)(https?://[^\\s?#]+)[?#][^\\s]*")

    fun format(report: CrashDiagnosticReport): String = buildString {
        appendLine("ChatBar 崩溃诊断报告")
        appendLine("请将此单个文件发送给开发者。")
        appendLine("已排除 API Key、Token、聊天正文、Prompt 与完整接口响应。")
        appendLine()
        appendLine("[报告]")
        appendLine("report_timestamp_ms: ${report.generatedAt}")
        appendLine("生成时间: ${formatTimestamp(report.generatedAt)}")
        appendLine("触发来源: ${redact(report.trigger)}")
        appendLine()
        appendLine("[App]")
        appendLine("包名: ${redact(report.app.packageName)}")
        appendLine("版本: ${redact(report.app.versionName)} (${report.app.versionCode})")
        appendLine("构建类型: ${redact(report.app.buildType)}")
        appendLine()
        appendLine("[设备]")
        appendLine("厂商/型号: ${redact(report.device.manufacturer)} / ${redact(report.device.model)}")
        appendLine("Android: ${redact(report.device.androidRelease)} (API ${report.device.sdkInt})")
        appendLine("ABI: ${report.device.abis.joinToString().ifBlank { "unknown" }}")
        appendLine("语言区域: ${redact(report.device.locale)}")
        report.throwable?.let { appendThrowableSection(it) }
        report.systemExit?.let { append(formatSystemExitSection(it)) }
        appendBreadcrumbSection(report.breadcrumbs)
    }.let(::redact)

    fun formatSystemExitSection(exit: DiagnosticSystemExitInfo): String = buildString {
        appendLine()
        appendLine("[Android 系统退出信息]")
        appendLine("退出时间: ${formatTimestamp(exit.timestamp)}")
        appendLine("原因: ${redact(exit.reasonLabel)} (${exit.reasonCode})")
        appendLine("状态码: ${exit.status}")
        appendLine("进程重要级: ${exit.importance}")
        appendLine("PSS/RSS: ${exit.pssKb} KB / ${exit.rssKb} KB")
        exit.description?.takeIf(String::isNotBlank)?.let {
            appendLine("系统说明: ${redact(it.take(MAX_FIELD_LENGTH))}")
        }
        exit.trace?.takeIf(String::isNotBlank)?.let { trace ->
            appendLine("系统 Trace 编码: ${exit.traceEncoding ?: "text"}")
            appendLine("系统 Trace 截断: ${exit.traceTruncated}")
            appendLine("--- system trace ---")
            appendLine(redact(trace))
            appendLine("--- end system trace ---")
        }
    }

    fun redact(value: String): String = value
        .replace('\u0000', ' ')
        .let { bearerSecret.replace(it, "Bearer <redacted>") }
        .let { assignedSecret.replace(it) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" } }
        .let { openAiStyleSecret.replace(it, "<redacted>") }
        .let { urlUserInfo.replace(it, "$1<redacted>@") }
        .let { urlQuery.replace(it, "$1?<redacted>") }

    fun sanitizeThrowableMessage(message: String?): String? {
        val raw = message?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val withoutRawBody = raw.substringBefore("Raw body:").let { prefix ->
            if (prefix.length != raw.length) "$prefix Raw body: <omitted>" else raw
        }
        val withoutResponseBody = RESPONSE_BODY_MARKERS.fold(withoutRawBody) { current, marker ->
            val index = current.indexOf(marker)
            if (index < 0) current else current.take(index + marker.length) + " <omitted>"
        }
        return redact(withoutResponseBody.take(MAX_MESSAGE_LENGTH))
    }

    fun sanitizeBreadcrumb(category: String, message: String): String {
        val cleanCategory = redact(category).replace('\t', ' ').replace('\n', ' ').take(40)
        val cleanMessage = redact(message).replace('\t', ' ').replace('\n', ' ').take(160)
        return "$cleanCategory: $cleanMessage"
    }

    private fun StringBuilder.appendThrowableSection(throwable: DiagnosticThrowableInfo) {
        appendLine()
        appendLine("[未捕获异常]")
        appendLine("线程: ${redact(throwable.threadName)}")
        throwable.causes.forEachIndexed { index, cause ->
            appendLine(if (index == 0) "异常: ${redact(cause.type)}" else "Caused by: ${redact(cause.type)}")
            cause.message?.let { appendLine("消息: ${sanitizeThrowableMessage(it)}") }
            cause.stackFrames.forEach { frame -> appendLine("  at ${redact(frame)}") }
        }
    }

    private fun StringBuilder.appendBreadcrumbSection(breadcrumbs: List<String>) {
        appendLine()
        appendLine("[最近脱敏操作，最多 30 条]")
        if (breadcrumbs.isEmpty()) {
            appendLine("无")
        } else {
            breadcrumbs.takeLast(30).forEach { appendLine(redact(it)) }
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))

    private val RESPONSE_BODY_MARKERS = listOf(
        "文本补全失败",
        "API 错误",
        "图片描述失败",
        "Embedding 请求失败",
        "JSON input:"
    )
}
