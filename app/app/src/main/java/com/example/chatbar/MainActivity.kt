package com.example.chatbar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.resolveDarkTheme
import com.example.chatbar.ui.kit.ChatBarTheme
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
            val view = LocalView.current
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
}
