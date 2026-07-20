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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.domain.card.SharedImportEventFlow
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.update.AppUpdateDownloadState
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.domain.update.AppUpdateInstallResult
import com.example.chatbar.ui.kit.CbLoadingState
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.components.AppUpdateDialog
import com.example.chatbar.ui.components.CrashReportDialog
import com.example.chatbar.utils.diagnostics.CrashReportManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedImportEvents = SharedImportEventFlow<Uri>()
    private var currentIntentHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportManager.recordBreadcrumb("lifecycle", "main_activity_created")

        currentIntentHandled = savedInstanceState?.getBoolean(STATE_CURRENT_INTENT_HANDLED) == true
        if (!currentIntentHandled) {
            handleSharedIntent(intent)
        }

        enableEdgeToEdge()
        setContent {
            val settingsRepository = ChatBarApp.instance.settingsRepository
            LaunchedEffect(settingsRepository) { settingsRepository.initialize() }
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
            ChatBarTheme(darkTheme = darkTheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ChatBarTheme.colors.background)
                ) {
                    if (settingsInitialized) {
                        MainNavigation(
                            tutorialCompleted = settings.tutorialVersion >= CURRENT_TUTORIAL_VERSION,
                            sharedImportEvents = sharedImportEvents.events,
                            onSharedImportClaimed = ::claimSharedImport,
                            onSharedImportCompleted = ::completeSharedImport
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
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        CrashReportManager.recordBreadcrumb("lifecycle", "main_activity_new_intent")
        currentIntentHandled = false
        setIntent(intent)
        handleSharedIntent(intent)
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
                sharedImportEvents.publish(uri)
                return
            }
        }
        currentIntentHandled = true
    }

    private fun completeSharedImport(id: Long): Boolean {
        val completed = sharedImportEvents.complete(id)
        if (completed) currentIntentHandled = true
        return completed
    }

    private fun claimSharedImport(id: Long): Boolean {
        val claimed = sharedImportEvents.claim(id)
        if (claimed) currentIntentHandled = true
        return claimed
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
