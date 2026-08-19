package com.example.chatbar.domain.webai

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTransport
import com.example.chatbar.data.local.entity.WebAiBinding
import com.example.chatbar.data.local.entity.WebAiSite
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

data class WebAiBrowserState(
    val visible: Boolean = false,
    val sessionId: String? = null,
    val site: WebAiSite = WebAiSite.DEEPSEEK,
    val url: String = "",
    val pageTitle: String = "",
    val pageLoading: Boolean = false,
    val pageProgress: Int = 0,
    val taskRunning: Boolean = false,
    val bindingInProgress: Boolean = false,
    val isBound: Boolean = false,
    val status: String? = null
)

@Serializable
private data class WebAiPromptMessage(
    val role: String,
    val content: String
)

@Serializable
private data class WebAiDomSnapshot(
    val content: String = "",
    val reasoning: String = "",
    val generating: Boolean = false
)

private data class WebAiAdapter(
    val site: WebAiSite,
    val composerSelectors: List<String>,
    val newChatSelectors: List<String>,
    val sendSelectors: List<String>,
    val contentSelectors: List<String>,
    val reasoningSelectors: List<String>
)

class WebAiController(
    private val chatRepository: ChatRepository,
    private val scope: CoroutineScope
) : WebAiGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val taskMutex = Mutex()
    private val appForeground = AtomicBoolean(true)
    private val _state = MutableStateFlow(WebAiBrowserState())
    val state = _state.asStateFlow()

    @Volatile
    private var webView: WebView? = null

    private val adapters = listOf(
        WebAiAdapter(
            site = WebAiSite.DEEPSEEK,
            composerSelectors = listOf("textarea", "[contenteditable='true']"),
            newChatSelectors = listOf("a[href='/']", "button[aria-label*='新对话']", "button[aria-label*='New chat']"),
            sendSelectors = listOf("button[aria-label*='发送']", "button[aria-label*='Send']", "button[type='submit']"),
            contentSelectors = listOf("[data-message-author-role='assistant'] .ds-markdown", ".ds-markdown"),
            reasoningSelectors = listOf("[data-message-author-role='assistant'] [class*='reasoning']", "[class*='thinking-content']")
        ),
        WebAiAdapter(
            site = WebAiSite.KIMI,
            composerSelectors = listOf("textarea", "[contenteditable='true']"),
            newChatSelectors = listOf("a[href='/']", "button[aria-label*='新对话']", "button[aria-label*='New chat']"),
            sendSelectors = listOf("button[aria-label*='发送']", "button[aria-label*='Send']", "button[type='submit']"),
            contentSelectors = listOf("[data-message-author-role='assistant'] [class*='markdown']", "[class*='assistant'] [class*='markdown']", ".markdown"),
            reasoningSelectors = listOf("[class*='assistant'] [class*='thinking']", "[class*='assistant'] [class*='reasoning']")
        ),
        WebAiAdapter(
            site = WebAiSite.DOUBAO,
            composerSelectors = listOf("textarea", "[contenteditable='true']"),
            newChatSelectors = listOf("button[aria-label*='新对话']", "button[aria-label*='New chat']", "a[href*='/chat']"),
            sendSelectors = listOf("button[aria-label*='发送']", "button[aria-label*='Send']", "button[type='submit']"),
            contentSelectors = listOf("[data-message-author-role='assistant'] [data-testid='message_text_content']", "[class*='assistant'] [class*='markdown']", "[data-testid='message_text_content']"),
            reasoningSelectors = listOf("[class*='assistant'] [class*='thinking']", "[class*='assistant'] [class*='reasoning']")
        )
    )

    fun show(sessionId: String) {
        scope.launch {
            val binding = chatRepository.getSession(sessionId)?.webAiBinding
            val site = binding?.site ?: _state.value.site
            _state.value = _state.value.copy(
                visible = true,
                sessionId = sessionId,
                site = site,
                isBound = binding != null,
                status = if (binding == null) "登录完成后点“完成并隐藏”" else "当前会话已绑定 ${site.displayName}"
            )
            withContext(Dispatchers.Main.immediate) {
                val view = webView ?: return@withContext
                view.requestLayout()
                view.invalidate()
                view.post {
                    view.requestLayout()
                    view.invalidate()
                    view.evaluateJavascript(WINDOW_RESIZE_SCRIPT, null)
                }
                if (!site.allowsAutomationAt(view.url.orEmpty())) view.loadUrl(site.entryUrl)
            }
        }
    }

    fun hide() {
        _state.value = _state.value.copy(visible = false, bindingInProgress = false)
    }

    fun selectSite(site: WebAiSite) {
        if (_state.value.taskRunning || _state.value.bindingInProgress) return
        _state.value = _state.value.copy(site = site, status = "登录完成后点“完成并隐藏”")
        webView?.loadUrl(site.entryUrl)
    }

    fun reload() {
        if (!_state.value.taskRunning) webView?.reload()
    }

    fun goBackOrHide() {
        val view = webView
        if (view?.canGoBack() == true && !_state.value.taskRunning) view.goBack() else hide()
    }

    fun bindAndHide() {
        val sessionId = _state.value.sessionId ?: return
        val site = _state.value.site
        if (_state.value.taskRunning || _state.value.bindingInProgress) return
        _state.value = _state.value.copy(bindingInProgress = true, status = "正在检查登录状态…")
        scope.launch {
            val result = runCatching {
                val view = requireWebView()
                val url = withContext(Dispatchers.Main.immediate) { view.url.orEmpty() }
                require(site.allowsAutomationAt(url)) { "请先回到 ${site.displayName} 对话页面" }
                require(evaluateJsonBoolean(view, adapter(site).readyScript())) {
                    "未检测到可用输入框，请完成登录并进入对话页"
                }
                val session = chatRepository.getSession(sessionId) ?: error("会话不存在")
                chatRepository.updateSession(session.copy(webAiBinding = WebAiBinding(site)))
            }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        visible = false,
                        bindingInProgress = false,
                        isBound = true,
                        status = "已绑定 ${site.displayName}"
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        bindingInProgress = false,
                        status = error.message ?: "绑定失败"
                    )
                }
            )
        }
    }

    fun unbind() {
        val sessionId = _state.value.sessionId ?: return
        if (_state.value.taskRunning || _state.value.bindingInProgress) return
        _state.value = _state.value.copy(bindingInProgress = true)
        scope.launch {
            val result = runCatching {
                val session = chatRepository.getSession(sessionId) ?: error("会话不存在")
                val modelId = WebAiModelPolicy.modelId(sessionId)
                chatRepository.updateSession(
                    session.copy(
                        webAiBinding = null,
                        modelId = session.modelId.takeUnless { it == modelId },
                        imageModelId = session.imageModelId.takeUnless { it == modelId }
                    )
                )
            }
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        bindingInProgress = false,
                        isBound = false,
                        status = "已解除绑定；网页版模型选择已清除"
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        bindingInProgress = false,
                        status = error.message ?: "解除绑定失败"
                    )
                }
            )
        }
    }

    fun onAppStarted() {
        appForeground.set(true)
        webView?.onResume()
    }

    fun onAppStopped() {
        appForeground.set(false)
        if (_state.value.taskRunning) {
            _state.value = _state.value.copy(status = "APP 已进入后台，网页版任务将停止")
        }
        webView?.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        webView = view
        Log.i(DIAGNOSTIC_TAG, "webview_attached id=${System.identityHashCode(view)}")
        WebView.setWebContentsDebuggingEnabled(false)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }
        Log.i(
            DIAGNOSTIC_TAG,
            "ua_profile webviewMarker=${view.settings.userAgentString.contains("; wv")} " +
                "version4Marker=${view.settings.userAgentString.contains("Version/4.0")}" 
        )
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            private var diagnosticErrorCount = 0

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val blocked = !uri.scheme.equals("https", ignoreCase = true)
                if (blocked) {
                    _state.value = _state.value.copy(status = "已拦截非 HTTPS 页面")
                }
                return blocked
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                diagnosticErrorCount = 0
                Log.i(DIAGNOSTIC_TAG, "page_started url=${safeUrl(url)}")
                _state.value = _state.value.copy(
                    url = url.orEmpty(),
                    pageLoading = true,
                    pageProgress = 0
                )
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _state.value = _state.value.copy(
                    url = url.orEmpty(),
                    pageLoading = false,
                    pageProgress = 100
                )
                view.evaluateJavascript(DOM_DIAGNOSTIC_SCRIPT) { snapshot ->
                    Log.i(
                        DIAGNOSTIC_TAG,
                        "page_finished url=${safeUrl(url)} " +
                            "native=${view.width}x${view.height} " +
                            "measured=${view.measuredWidth}x${view.measuredHeight} " +
                            "dom=${snapshot.take(4000)}"
                    )
                }
                view.evaluateJavascript(LAYOUT_DIAGNOSTIC_SCRIPT) { result ->
                    Log.i(
                        DIAGNOSTIC_TAG,
                        "layout_detail url=${safeUrl(url)} result=${result.take(8000)}"
                    )
                }
                if (WebAiSite.DEEPSEEK.allowsAutomationAt(url.orEmpty())) {
                    view.evaluateJavascript(DEEPSEEK_LOGIN_CARET_FIX_SCRIPT, null)
                    view.evaluateJavascript(INPUT_DIAGNOSTIC_SCRIPT, null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (diagnosticErrorCount >= MAX_DIAGNOSTIC_RESOURCE_ERRORS) return
                diagnosticErrorCount += 1
                Log.w(
                    DIAGNOSTIC_TAG,
                    "resource_error main=${request.isForMainFrame} code=${error.errorCode} " +
                        "url=${safeUrl(request.url?.toString())}"
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (
                    errorResponse.statusCode < 400 ||
                    diagnosticErrorCount >= MAX_DIAGNOSTIC_RESOURCE_ERRORS
                ) return
                diagnosticErrorCount += 1
                Log.w(
                    DIAGNOSTIC_TAG,
                    "http_error main=${request.isForMainFrame} status=${errorResponse.statusCode} " +
                        "url=${safeUrl(request.url?.toString())}"
                )
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
                Log.e(DIAGNOSTIC_TAG, "ssl_error url=${safeUrl(error?.url)} primary=${error?.primaryError}")
                _state.value = _state.value.copy(status = "SSL 证书校验失败，页面已停止")
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Log.e(
                    DIAGNOSTIC_TAG,
                    "renderer_gone crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}"
                )
                return false
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _state.value = _state.value.copy(pageProgress = newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                _state.value = _state.value.copy(pageTitle = title.orEmpty())
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message().orEmpty()
                if (message.startsWith(INPUT_DIAGNOSTIC_PREFIX)) {
                    Log.i(DIAGNOSTIC_TAG, message.take(500))
                } else if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(
                        DIAGNOSTIC_TAG,
                        "console_error source=${safeUrl(consoleMessage.sourceId())} " +
                            "line=${consoleMessage.lineNumber()} " +
                            "message=${safeConsoleError(message)}"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }
    }

    fun detach(view: WebView) {
        Log.i(DIAGNOSTIC_TAG, "webview_detached id=${System.identityHashCode(view)}")
        if (webView === view) webView = null
    }

    override fun stream(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig
    ): Flow<StreamEvent> = flow {
        if (modelConfig.transport != ModelTransport.WEB_VIEW) {
            emit(StreamEvent.Error("模型不是网页版传输类型"))
            return@flow
        }
        if (!taskMutex.tryLock()) {
            emit(StreamEvent.Error("已有网页版 AI 任务运行中，请等待或先停止当前任务"))
            return@flow
        }
        var view: WebView? = null
        try {
            check(appForeground.get()) { "网页版 AI 仅支持 APP 前台运行" }
            val sessionId = WebAiModelPolicy.sessionId(modelConfig.id)
                ?: error("网页版模型 ID 无效")
            val session = chatRepository.getSession(sessionId) ?: error("会话不存在")
            val binding = session.webAiBinding ?: error("网页版 AI 绑定已失效")
            view = requireWebView()
            val adapter = adapter(binding.site)
            val prompt = buildPrompt(messages)
            _state.value = _state.value.copy(
                visible = false,
                site = binding.site,
                taskRunning = true,
                status = "${binding.site.displayName} 正在生成"
            )
            val baseline = prepareConversation(view, adapter)
            check(evaluateJsonBoolean(view, adapter.submitScript(prompt))) {
                "网页输入或发送失败；请打开浏览器检查页面状态"
            }
            streamSnapshots(view, adapter, baseline) { emit(it) }
            _state.value = _state.value.copy(status = "${binding.site.displayName} 已完成")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                view?.let { stopGeneration(it, _state.value.site) }
            }
            _state.value = _state.value.copy(status = "网页版任务已停止")
            throw cancelled
        } catch (error: Throwable) {
            view?.let { stopGeneration(it, _state.value.site) }
            val message = error.message ?: "网页版 AI 执行失败"
            _state.value = _state.value.copy(status = message)
            emit(StreamEvent.Error(message))
        } finally {
            _state.value = _state.value.copy(taskRunning = false)
            taskMutex.unlock()
        }
    }.buffer(Channel.UNLIMITED)

    private suspend fun prepareConversation(
        view: WebView,
        adapter: WebAiAdapter
    ): WebAiDomSnapshot {
        withContext(Dispatchers.Main.immediate) { view.loadUrl(adapter.site.entryUrl) }
        delay(300)
        awaitComposer(view, adapter)
        val newChatClicked = evaluateJsonBoolean(view, adapter.newConversationScript())
        delay(600)
        awaitComposer(view, adapter)
        val baseline = evaluateSnapshot(view, adapter.snapshotScript())
        check(newChatClicked || baseline.content.isBlank()) {
            "无法确认已创建新网页对话；为避免混入旧上下文，任务已停止"
        }
        return baseline
    }

    private suspend fun awaitComposer(view: WebView, adapter: WebAiAdapter) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            check(appForeground.get()) { "APP 已进入后台，网页版任务已停止" }
            val url = withContext(Dispatchers.Main.immediate) { view.url.orEmpty() }
            if (adapter.site.allowsAutomationAt(url) && evaluateJsonBoolean(view, adapter.readyScript())) {
                return
            }
            delay(POLL_MILLIS)
        }
        error("网页未进入可发送状态；请打开浏览器确认登录与页面")
    }

    private suspend fun streamSnapshots(
        view: WebView,
        adapter: WebAiAdapter,
        baseline: WebAiDomSnapshot,
        emitEvent: suspend (StreamEvent) -> Unit
    ) {
        val startedAt = System.currentTimeMillis()
        var firstContentAt: Long? = null
        var lastContent = ""
        var lastReasoning = ""
        var lastChangeAt = startedAt
        var responseStarted = false
        while (true) {
            check(appForeground.get()) { "APP 已进入后台，网页版任务已停止" }
            val now = System.currentTimeMillis()
            check(now - startedAt < TASK_TIMEOUT_MILLIS) { "网页版 AI 生成超时" }
            val snapshot = evaluateSnapshot(view, adapter.snapshotScript())
            if (!responseStarted) {
                responseStarted = snapshot.generating ||
                    snapshot.content != baseline.content ||
                    snapshot.reasoning != baseline.reasoning
                if (!responseStarted) {
                    delay(POLL_MILLIS)
                    continue
                }
            }
            if (snapshot.content.isNotBlank()) firstContentAt = firstContentAt ?: now
            if (firstContentAt == null) {
                check(now - startedAt < FIRST_CONTENT_TIMEOUT_MILLIS) {
                    "长时间未检测到网页输出；请打开浏览器检查登录、风控或页面改版"
                }
            }
            if (snapshot.reasoning.isNotBlank() && snapshot.reasoning != lastReasoning) {
                val delta = monotonicDelta(lastReasoning, snapshot.reasoning, "思考过程")
                if (delta.isNotEmpty()) emitEvent(StreamEvent.ReasoningDelta(delta))
                lastReasoning = snapshot.reasoning
                lastChangeAt = now
            }
            if (snapshot.content != lastContent) {
                val delta = monotonicDelta(lastContent, snapshot.content, "正文")
                if (delta.isNotEmpty()) emitEvent(StreamEvent.Delta(delta))
                lastContent = snapshot.content
                lastChangeAt = now
            }
            if (
                lastContent.isNotBlank() &&
                !snapshot.generating &&
                now - lastChangeAt >= STABLE_COMPLETION_MILLIS
            ) {
                emitEvent(StreamEvent.Done)
                return
            }
            delay(POLL_MILLIS)
        }
    }

    private fun monotonicDelta(previous: String, current: String, label: String): String {
        if (current == previous) return ""
        check(current.startsWith(previous)) {
            "网页${label}发生非增量改写，为避免气泡内容错乱已停止；请重试"
        }
        return current.removePrefix(previous)
    }

    private suspend fun stopGeneration(view: WebView, site: WebAiSite) {
        runCatching {
            val adapter = adapter(site)
            val url = withContext(Dispatchers.Main.immediate) { view.url.orEmpty() }
            if (site.allowsAutomationAt(url)) evaluate(view, adapter.stopScript())
        }
    }

    private fun buildPrompt(messages: List<ChatApiMessage>): String {
        val promptMessages = messages.map { message ->
            WebAiPromptMessage(message.role, message.textOnlyContent())
        }
        return PromptTemplates.webAiConversationEnvelope(json.encodeToString(promptMessages))
    }

    private fun ChatApiMessage.textOnlyContent(): String = when (val value = content) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> {
                    val type = part["type"]?.jsonPrimitive?.contentOrNull
                    check(type != "image_url" && part["image_url"] == null) {
                        "网页版 AI 暂不支持图片附件"
                    }
                    part["text"]?.jsonPrimitive?.contentOrNull
                        ?: part["content"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                }
                else -> ""
            }
        }
        else -> ""
    }

    private fun adapter(site: WebAiSite): WebAiAdapter = adapters.first { it.site == site }

    private fun requireWebView(): WebView = webView ?: error("内置浏览器尚未就绪")

    private suspend fun evaluateSnapshot(view: WebView, script: String): WebAiDomSnapshot {
        val result = decodeJavascriptString(evaluate(view, script))
        return runCatching { json.decodeFromString<WebAiDomSnapshot>(result) }
            .getOrElse { error("无法读取网页版输出，页面结构可能已变化") }
    }

    private suspend fun evaluateJsonBoolean(view: WebView, script: String): Boolean {
        val result = decodeJavascriptString(evaluate(view, script))
        return result == "true"
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script, ValueCallback { result ->
                    if (continuation.isActive) continuation.resume(result.orEmpty())
                })
            }
        }

    private fun decodeJavascriptString(value: String): String {
        if (value == "null" || value.isBlank()) return ""
        return runCatching { json.decodeFromString<JsonPrimitive>(value).content }
            .getOrDefault(value)
    }

    private fun safeUrl(value: String?): String {
        val uri = runCatching { Uri.parse(value.orEmpty()) }.getOrNull() ?: return ""
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty().take(160)
        return buildString {
            uri.scheme?.let { append(it).append("://") }
            append(host)
            append(path)
        }
    }

    private fun safeConsoleError(message: String): String = message
        .replace(Regex("https?://[^\\s]+")) { match -> safeUrl(match.value) }
        .replace(
            Regex("(?i)(token|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+")
        ) { match -> "${match.groupValues[1]}=<redacted>" }
        .take(800)

    private fun WebAiAdapter.readyScript(): String = """
        (() => {
          const visible = e => !!e && e.getClientRects().length > 0 && !e.disabled;
          const selectors = ${json.encodeToString(composerSelectors)};
          return JSON.stringify(selectors.some(s => Array.from(document.querySelectorAll(s)).some(visible)));
        })()
    """.trimIndent()

    private fun WebAiAdapter.newConversationScript(): String = """
        (() => {
          const visible = e => !!e && e.getClientRects().length > 0;
          const selectors = ${json.encodeToString(newChatSelectors)};
          let target = selectors.flatMap(s => Array.from(document.querySelectorAll(s))).find(visible);
          if (!target) target = Array.from(document.querySelectorAll('button,a')).find(e => visible(e) && /^(新对话|开启新对话|New chat)$/i.test((e.innerText || e.getAttribute('aria-label') || '').trim()));
          if (target) target.click();
          return JSON.stringify(!!target);
        })()
    """.trimIndent()

    private fun WebAiAdapter.submitScript(prompt: String): String = """
        (() => {
          const visible = e => !!e && e.getClientRects().length > 0 && !e.disabled;
          const composerSelectors = ${json.encodeToString(composerSelectors)};
          const sendSelectors = ${json.encodeToString(sendSelectors)};
          const input = composerSelectors.flatMap(s => Array.from(document.querySelectorAll(s))).find(visible);
          if (!input) return JSON.stringify(false);
          const text = ${JsonPrimitive(prompt)};
          input.focus();
          if (input instanceof HTMLTextAreaElement || input instanceof HTMLInputElement) {
            const proto = input instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
            if (setter) setter.call(input, text); else input.value = text;
          } else {
            input.innerText = text;
          }
          input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
          input.dispatchEvent(new Event('change', { bubbles: true }));
          setTimeout(() => {
            let send = sendSelectors.flatMap(s => Array.from(document.querySelectorAll(s))).find(visible);
            if (!send) send = Array.from(document.querySelectorAll('button')).find(e => visible(e) && /^(发送|Send)$/i.test((e.innerText || e.getAttribute('aria-label') || '').trim()));
            if (send) send.click();
            else input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
          }, 180);
          return JSON.stringify(true);
        })()
    """.trimIndent()

    private fun WebAiAdapter.snapshotScript(): String = """
        (() => {
          const visible = e => !!e && e.getClientRects().length > 0;
          const lastVisibleText = selectors => {
            const nodes = selectors.flatMap(s => Array.from(document.querySelectorAll(s))).filter(visible);
            return (nodes[nodes.length - 1]?.innerText || '').trim();
          };
          const content = lastVisibleText(${json.encodeToString(contentSelectors)});
          const reasoning = lastVisibleText(${json.encodeToString(reasoningSelectors)});
          const generating = Array.from(document.querySelectorAll('button')).some(e => {
            if (!visible(e)) return false;
            const text = (e.innerText || e.getAttribute('aria-label') || e.getAttribute('title') || '').trim();
            return /停止|终止|Stop generating|Stop response/i.test(text);
          });
          return JSON.stringify({ content, reasoning, generating });
        })()
    """.trimIndent()

    private fun WebAiAdapter.stopScript(): String = """
        (() => {
          const visible = e => !!e && e.getClientRects().length > 0;
          const stop = Array.from(document.querySelectorAll('button')).find(e => {
            if (!visible(e)) return false;
            const text = (e.innerText || e.getAttribute('aria-label') || e.getAttribute('title') || '').trim();
            return /停止|终止|Stop generating|Stop response/i.test(text);
          });
          if (stop) stop.click();
          return JSON.stringify(!!stop);
        })()
    """.trimIndent()

    companion object {
        private const val DIAGNOSTIC_TAG = "WebAiDiag"
        private const val INPUT_DIAGNOSTIC_PREFIX = "CHATBAR_INPUT_DIAG "
        private const val MAX_DIAGNOSTIC_RESOURCE_ERRORS = 30
        private const val WINDOW_RESIZE_SCRIPT =
            "window.dispatchEvent(new Event('resize')); true"
        private const val DOM_DIAGNOSTIC_SCRIPT = """
            (() => {
              const describe = element => {
                if (!element) return null;
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return {
                  tag: element.tagName,
                  id: element.id || '',
                  classes: Array.from(element.classList || []).slice(0, 8),
                  rect: [rect.x, rect.y, rect.width, rect.height].map(Math.round),
                  display: style.display,
                  visibility: style.visibility,
                  opacity: style.opacity,
                  position: style.position,
                  transform: style.transform,
                  contentVisibility: style.contentVisibility
                };
              };
              return JSON.stringify({
                ready: document.readyState,
                viewport: [innerWidth, innerHeight, devicePixelRatio],
                visualViewport: window.visualViewport
                  ? [visualViewport.width, visualViewport.height, visualViewport.scale]
                  : null,
                htmlLength: document.documentElement?.outerHTML?.length || 0,
                textLength: document.body?.innerText?.length || 0,
                frameCount: document.querySelectorAll('iframe').length,
                html: describe(document.documentElement),
                body: describe(document.body),
                roots: Array.from(document.body?.children || []).slice(0, 12).map(describe),
                center: describe(document.elementFromPoint(innerWidth / 2, innerHeight / 2))
              });
            })()
        """
        private const val LAYOUT_DIAGNOSTIC_SCRIPT = """
            (() => {
              const root = document.querySelector('#app, #root');
              const describe = element => {
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return {
                  tag: element.tagName,
                  id: element.id || '',
                  classes: Array.from(element.classList || []).slice(0, 5),
                  rect: [rect.x, rect.y, rect.width, rect.height].map(Math.round),
                  display: style.display,
                  visibility: style.visibility,
                  opacity: style.opacity,
                  position: style.position
                };
              };
              const descendants = Array.from(root?.querySelectorAll('*') || [])
                .slice(0, 24)
                .map(describe);
              const matchingRules = element => {
                const matches = [];
                const visit = rules => {
                  for (const rule of Array.from(rules || [])) {
                    if (matches.length >= 12) return;
                    if (rule.cssRules) visit(rule.cssRules);
                    if (!rule.selectorText || !rule.style) continue;
                    try {
                      if (element.matches(rule.selectorText)) {
                        matches.push({
                          selector: rule.selectorText.slice(0, 240),
                          style: rule.style.cssText.slice(0, 600)
                        });
                      }
                    } catch (_) {}
                  }
                };
                for (const sheet of Array.from(document.styleSheets)) {
                  try { visit(sheet.cssRules); } catch (_) {}
                  if (matches.length >= 12) break;
                }
                return matches;
              };
              const styledDescendants = Array.from(root?.querySelectorAll('*') || [])
                .slice(0, 3)
                .map(element => {
                  const style = getComputedStyle(element);
                  return {
                    node: describe(element),
                    inline: element.style.cssText.slice(0, 600),
                    computed: {
                      height: style.height,
                      minHeight: style.minHeight,
                      maxHeight: style.maxHeight,
                      inset: style.inset,
                      overflow: style.overflow,
                      flex: style.flex,
                      alignItems: style.alignItems
                    },
                    rules: matchingRules(element)
                  };
              });
              return JSON.stringify({
                viewportMeta: document.querySelector('meta[name="viewport"]')?.content || '',
                rootChildren: root?.childElementCount || 0,
                descendants: descendants.slice(0, 4),
                styledDescendants
              });
            })()
        """
        private const val DEEPSEEK_LOGIN_CARET_FIX_SCRIPT = """
            (() => {
              if (window.__chatbarLoginCaretFixInstalled) return;
              window.__chatbarLoginCaretFixInstalled = true;
              const restore = input => {
                if (input.ownerDocument.activeElement !== input || !input.value.length) return;
                try {
                  if (input.selectionStart === 0 && input.selectionEnd === 0) {
                    const end = input.value.length;
                    input.setSelectionRange(end, end);
                  }
                } catch (_) {}
              };
              document.addEventListener('input', event => {
                if (!(event.target instanceof HTMLInputElement)) return;
                if (!(event.inputType || '').startsWith('insert')) return;
                const input = event.target;
                queueMicrotask(() => restore(input));
                requestAnimationFrame(() => restore(input));
                setTimeout(() => restore(input), 0);
                setTimeout(() => restore(input), 60);
              }, true);
            })()
        """
        private const val INPUT_DIAGNOSTIC_SCRIPT = """
            (() => {
              if (window.__chatbarInputDiagnosticInstalled) return;
              window.__chatbarInputDiagnosticInstalled = true;
              const installed = new WeakSet();
              const emit = (kind, input) => {
                if (!(input instanceof HTMLInputElement)) return;
                const style = input.ownerDocument.defaultView.getComputedStyle(input);
                console.info('CHATBAR_INPUT_DIAG ' + JSON.stringify({
                  kind,
                  length: input.value.length,
                  start: input.selectionStart,
                  end: input.selectionEnd,
                  dir: style.direction,
                  documentDir: input.ownerDocument.documentElement.dir || ''
                }));
              };
              const install = targetWindow => {
                try {
                  const document = targetWindow.document;
                  if (!installed.has(document)) {
                    installed.add(document);
                    document.addEventListener('beforeinput', event => emit('before', event.target), true);
                    document.addEventListener('input', event => {
                      targetWindow.requestAnimationFrame(() => emit('after', event.target));
                    }, true);
                  }
                  Array.from(document.querySelectorAll('iframe')).forEach(frame => {
                    try { if (frame.contentWindow) install(frame.contentWindow); } catch (_) {}
                  });
                } catch (_) {}
              };
              install(window);
              window.__chatbarInputDiagnosticTimer = setInterval(() => install(window), 1000);
            })()
        """
        private const val POLL_MILLIS = 300L
        private const val READY_TIMEOUT_MILLIS = 45_000L
        private const val FIRST_CONTENT_TIMEOUT_MILLIS = 120_000L
        private const val TASK_TIMEOUT_MILLIS = 600_000L
        private const val STABLE_COMPLETION_MILLIS = 5_000L
    }
}
