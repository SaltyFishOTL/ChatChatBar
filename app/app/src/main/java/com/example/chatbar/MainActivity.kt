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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.update.AppUpdateInfo
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbLoadingState
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.ChatBarSpacing
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    val sharedImportUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleSharedIntent(intent)

        enableEdgeToEdge()
        setContent {
            val settingsRepository = ChatBarApp.instance.settingsRepository
            LaunchedEffect(settingsRepository) { settingsRepository.initialize() }
            val settings by settingsRepository.appSettings.collectAsState(initial = AppSettings())
            val settingsInitialized by settingsRepository.isInitialized.collectAsState(initial = false)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = settings.themeMode.resolveDarkTheme(systemDark)
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val view = LocalView.current
            var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
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
                            sharedImportUri = sharedImportUri
                        )
                    } else {
                        CbLoadingState(label = "ChatBar")
                    }
                    updateInfo?.let { info ->
                        AppUpdateDialog(
                            updateInfo = info,
                            onDismiss = { updateInfo = null },
                            onOpenRelease = {
                                val releaseUrl = info.releaseUrl.ifBlank { AppUpdateChecker.DEFAULT_RELEASES_URL }
                                runCatching {
                                    uriHandler.openUri(releaseUrl)
                                }.onFailure { error ->
                                    Log.w(TAG, "Failed to open release URL: $releaseUrl", error)
                                    Toast.makeText(context, "无法打开 GitHub Release 页面", Toast.LENGTH_SHORT).show()
                                }
                                updateInfo = null
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                sharedImportUri.value = uri
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
private fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onDismiss: () -> Unit,
    onOpenRelease: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "发现新版本",
        confirm = {
            CbButton(
                text = "去更新",
                onClick = onOpenRelease
            )
        },
        dismiss = {
            CbButton(
                text = "稍后",
                onClick = onDismiss,
                variant = ButtonVariant.Ghost
            )
        }
    ) {
        CbText(
            text = "当前版本：${updateInfo.currentVersion}",
            color = ChatBarTheme.colors.mutedForeground
        )
        Spacer(Modifier.height(ChatBarSpacing.sm))
        CbText(
            text = "最新版本：${updateInfo.latestVersion}",
            color = ChatBarTheme.colors.foreground
        )
        Spacer(Modifier.height(ChatBarSpacing.md))
        CbText(
            text = "可前往 GitHub Releases 下载更新。",
            color = ChatBarTheme.colors.mutedForeground
        )
    }
}
