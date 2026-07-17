package com.example.chatbar.domain.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticReportTest {
    @Test
    fun reportRedactsCredentialsAndResponseBodies() {
        val report = sampleReport(
            throwable = DiagnosticThrowableInfo(
                threadName = "DefaultDispatcher-worker-1",
                causes = listOf(
                    DiagnosticThrowableCause(
                        type = "java.lang.IllegalStateException",
                        message = "API 错误 (500): token=secret-value Raw body: private response",
                        stackFrames = listOf("com.example.chatbar.Test.run(Test.kt:1)")
                    )
                )
            ),
            breadcrumbs = listOf("network: Authorization=Bearer abc123")
        )

        val text = CrashDiagnosticReportFormatter.format(report)

        assertFalse(text.contains("secret-value"))
        assertFalse(text.contains("private response"))
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains("<redacted>"))
        assertTrue(text.contains("<omitted>"))
    }

    @Test
    fun reportKeepsSingleFileSectionsAndLimitsBreadcrumbs() {
        val report = sampleReport(
            breadcrumbs = (1..35).map { "navigation: Screen$it" },
            systemExit = DiagnosticSystemExitInfo(
                timestamp = 2L,
                reasonCode = 6,
                reasonLabel = "ANR",
                status = 0,
                importance = 100,
                pssKb = 10,
                rssKb = 20,
                description = "Input dispatch timeout",
                trace = "main thread trace",
                traceEncoding = "utf-8",
                traceTruncated = false
            )
        )

        val text = CrashDiagnosticReportFormatter.format(report)

        assertTrue(text.contains("[Android 系统退出信息]"))
        assertTrue(text.contains("[最近脱敏操作，最多 30 条]"))
        assertFalse(text.contains("Screen1\n"))
        assertTrue(text.contains("Screen35"))
    }

    @Test
    fun urlQueryAndUserInfoAreRedacted() {
        val redacted = CrashDiagnosticReportFormatter.redact(
            "http://user:pass@127.0.0.1:8080/v1?token=abc"
        )

        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("abc"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun jsonAndOpenAiStyleCredentialsAreRedacted() {
        val redacted = CrashDiagnosticReportFormatter.redact(
            "{\"api_key\":\"private-json-key\",\"access_token\":\"private-token\"} sk-abcdefghijklmnop"
        )

        assertFalse(redacted.contains("private-json-key"))
        assertFalse(redacted.contains("private-token"))
        assertFalse(redacted.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun serializationInputSnippetIsOmitted() {
        val message = CrashDiagnosticReportFormatter.sanitizeThrowableMessage(
            "Unexpected JSON token at offset 1. JSON input: private model response"
        )

        assertFalse(message.orEmpty().contains("private model response"))
        assertTrue(message.orEmpty().contains("<omitted>"))
    }

    private fun sampleReport(
        throwable: DiagnosticThrowableInfo? = null,
        systemExit: DiagnosticSystemExitInfo? = null,
        breadcrumbs: List<String> = emptyList()
    ) = CrashDiagnosticReport(
        generatedAt = 1L,
        trigger = "未捕获 JVM/Kotlin 异常",
        app = DiagnosticAppInfo("com.example.chatbar", "1.0", 1L, "debug"),
        device = DiagnosticDeviceInfo("Google", "Pixel", "16", 36, listOf("arm64-v8a"), "zh-CN"),
        breadcrumbs = breadcrumbs,
        throwable = throwable,
        systemExit = systemExit
    )
}
