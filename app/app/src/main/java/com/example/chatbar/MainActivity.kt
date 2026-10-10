package com.example.chatbar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.domain.card.SharedImportSource
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.update.AppUpdateDownloadState
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.domain.update.AppUpdateInstallResult
import com.example.chatbar.ui.kit.CbLoadingState
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.components.AppUpdateDialog
import com.example.chatbar.ui.components.CrashReportDialog
import com.example.chatbar.ui.components.StorageReadFailureScreen
import com.example.chatbar.ui.manage.AppBackupHost
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.example.chatbar.utils.diagnostics.CrashReportManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var currentIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntentHandled = savedInstanceState?.getBoolean(STATE_CURRENT_INTENT_HANDLED) == true

        enableEdgeToEdge()
        setContent {
            val backupError by ChatBarApp.instance.backupStartupError.collectAsState()
            val backupReady by ChatBarApp.instance.backupStartupReady.collectAsState()
            if (backupError != null) {
                ChatBarTheme {
                    Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background).padding(24.dp),
                        verticalArrangement = Arrangement.Center) {
                        CbText(requireNotNull(backupError))
                        CbButton("关闭 APP，重新打开重试", ::closeForBackup)
                    }
                }
                return@setContent
            }
            if (!backupReady) {
                ChatBarTheme { CbLoadingState(label = "正在加载本地数据，请稍候") }
                return@setContent
            }
            LaunchedEffect(Unit) {
                CrashReportManager.recordBreadcrumb("lifecycle", "main_activity_created")
                if (!currentIntentHandled) handleSharedIntent(intent)
            }
            val settingsRepository = ChatBarApp.instance.settingsRepository
            val storageFailures by ChatBarApp.instance.jsonFileStorage.singletonReadFailures.collectAsState()
            val scope = rememberCoroutineScope()
            var retryingStorage by remember { mutableStateOf(false) }
            LaunchedEffect(settingsRepository) {
                try {
                    settingsRepository.initialize()
                } catch (error: JsonFileStorage.SingletonReadException) {
                    Log.e(TAG, error.message.orEmpty())
                }
            }
            val settings by settingsRepository.appSettings.collectAsState(initial = AppSettings())
            val settingsInitialized by settingsRepository.isInitialized.collectAsState(initial = false)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = settings.themeMode.resolveDarkTheme(systemDark)
            val context = LocalContext.current
            val view = LocalView.current
            val updateManager = ChatBarApp.instance.appUpdateManager
            val updateDownloadState by updateManager.downloadState.collectAsState()
            var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
            val pendingCrashReport by CrashReportManager.pendingReport.collectAsState()
            var crashDialogDismissed by rememberSaveable(pendingCrashReport?.createdAt) {
                mutableStateOf(false)
            }
            LaunchedEffect(settingsInitialized) {
                if (settingsInitialized) {
                    runCatching {
                        ChatBarApp.instance.appUpdateChecker.checkLatestRelease()
                    }.onSuccess { result ->
                        updateInfo = result
                    }.onFailure { error ->
                        Log.w(TAG, "GitHub release check failed", error)
                    }
                }
            }
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            ChatBarTheme(
                darkTheme = darkTheme,
                themeColor = settings.themeColor
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChatBarTheme.colors.background)
                ) {
                    if (storageFailures.isNotEmpty() || retryingStorage) {
                        StorageReadFailureScreen(
                            failures = storageFailures.values.toList(),
                            retrying = retryingStorage,
                            onRetry = {
                                scope.launch {
                                    retryingStorage = true
                                    try {
                                        ChatBarApp.instance.jsonFileStorage.retryFailedSingletonReads()
                                        settingsRepository.initialize(forceReload = true)
                                        ChatBarApp.instance.initializePersistentState().join()
                                    } catch (error: JsonFileStorage.SingletonReadException) {
                                        Log.e(TAG, error.message.orEmpty())
                                    } finally {
                                        retryingStorage = false
                                    }
                                }
                            }
                        )
                    } else if (settingsInitialized) {
                        MainNavigation(
                            tutorialCompleted = settings.tutorialVersion >= CURRENT_TUTORIAL_VERSION
                        )
                    } else {
                        CbLoadingState(label = "ChatBar")
                    }
                    val showCrashDialog = pendingCrashReport != null && !crashDialogDismissed
                    if (!showCrashDialog) updateInfo?.let { info ->
                        val visibleDownloadState = remember(updateDownloadState, info) {
                            updateManager.stateFor(info)
                        }
                        AppUpdateDialog(
                            updateInfo = info,
                            downloadState = visibleDownloadState,
                            onDismiss = {
                                if (visibleDownloadState is AppUpdateDownloadState.Downloading) {
                                    updateManager.cancelDownload()
                                }
                                updateInfo = null
                            },
                            onUpdate = {
                                when {
                                    info.apkAsset == null -> openReleasePage(context, info)
                                    visibleDownloadState is AppUpdateDownloadState.Ready -> {
                                        updateManager.requestInstall(context, info)
                                            .onSuccess { result ->
                                                if (result == AppUpdateInstallResult.PermissionRequired) {
                                                    Toast.makeText(
                                                        context,
                                                        "请允许 ChatBar 安装未知应用，返回后再次点“安装更新”",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                            .onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    "无法安装更新：${error.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                    }
                                    visibleDownloadState !is AppUpdateDownloadState.Downloading -> {
                                        updateManager.startDownload(info)
                                    }
                                }
                            }
                        )
                    }
                    if (showCrashDialog) pendingCrashReport?.let { report ->
                        CrashReportDialog(
                            report = report,
                            onShare = {
                                CrashReportManager.sharePendingReport(context)
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            "发送报告失败：${error.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                crashDialogDismissed = true
                            },
                            onDelete = CrashReportManager::deletePendingReport,
                            onDismiss = { crashDialogDismissed = true }
                        )
                    }
                    AppBackupHost(ChatBarApp.instance.appBackupService, ::closeForBackup)
                }
            }
        }
    }

    private fun closeForBackup() {
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ChatBarApp.instance.backupStartupError.value != null) return
        currentIntentHandled = false
        setIntent(intent)
        if (ChatBarApp.instance.backupStartupReady.value) {
            CrashReportManager.recordBreadcrumb("lifecycle", "main_activity_new_intent")
            handleSharedIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CURRENT_INTENT_HANDLED, currentIntentHandled)
        super.onSaveInstanceState(outState)
    }

    private fun handleSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && handleCommunityAuthCallback(intent.data)) {
            currentIntentHandled = true
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            CrashReportManager.recordBreadcrumb("action", "receive_shared_file")
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                ChatBarApp.instance.sharedImportCoordinator.enqueue(SharedImportSource.FileUri(uri))
                currentIntentHandled = true
                return
            }
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)?.let { text ->
                ChatBarApp.instance.sharedImportCoordinator.enqueue(SharedImportSource.TextJson(text))
                currentIntentHandled = true
                return
            }
        }
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && isImportableFileUri(uri)) {
                CrashReportManager.recordBreadcrumb("action", "receive_view_file")
                ChatBarApp.instance.sharedImportCoordinator.enqueue(SharedImportSource.FileUri(uri))
                currentIntentHandled = true
                return
            }
        }
        currentIntentHandled = true
    }

    private fun isImportableFileUri(uri: Uri): Boolean {
        val type = runCatching { contentResolver.getType(uri) }
            .getOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (type == null) return true
        return type.startsWith("image/") ||
            type == "application/json" ||
            type == "text/json" ||
            type == "text/plain" ||
            type == "application/octet-stream"
    }

    private fun handleCommunityAuthCallback(uri: Uri?): Boolean {
        if (uri?.scheme != "chatbar" || uri.host != "auth" || uri.path != "/callback") return false
        lifecycleScope.launch {
            runCatching {
                ChatBarApp.instance.communityService.handleAuthCallback(uri)
            }.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, "Discord 登录成功", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    Log.w(TAG, "Community auth callback failed", error)
                    Toast.makeText(this@MainActivity, "Discord 登录失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
        return true
    }

    private fun openReleasePage(context: android.content.Context, updateInfo: AppUpdateInfo) {
        val releaseUrl = updateInfo.releaseUrl.ifBlank { AppUpdateChecker.DEFAULT_RELEASES_URL }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
        }.onFailure { error ->
            Log.w(TAG, "Failed to open release URL: $releaseUrl", error)
            Toast.makeText(context, "无法打开 GitHub Release 页面", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_CURRENT_INTENT_HANDLED = "current_intent_handled"
    }
}
