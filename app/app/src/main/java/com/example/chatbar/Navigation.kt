package com.example.chatbar

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.chatbar.ui.components.BottomNavBar
import com.example.chatbar.ui.home.HomeScreen
import com.example.chatbar.ui.chat.ChatScreen
import com.example.chatbar.ui.manage.ManageScreen
import com.example.chatbar.ui.character.CharacterEditScreen
import com.example.chatbar.ui.model.ModelEditScreen
import com.example.chatbar.ui.format.FormatCardEditScreen
import com.example.chatbar.ui.tutorial.TutorialScreen
import kotlinx.coroutines.launch

@Composable
fun MainNavigation(tutorialCompleted: Boolean) {
    val initialRoute: NavKey = if (tutorialCompleted) HomeRoute else TutorialRoute(firstLaunch = true)
    val backStack = rememberNavBackStack(initialRoute)
    val currentRoute = backStack.lastOrNull() ?: HomeRoute
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastHomeBackPressAt by remember { mutableLongStateOf(0L) }

    fun popBackStack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun showRoot(route: NavKey) {
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        if (backStack.isEmpty()) {
            backStack.add(route)
        } else {
            backStack[0] = route
        }
    }

    BackHandler(enabled = backStack.size == 1 && (currentRoute == HomeRoute || currentRoute == ManageRoute)) {
        when (currentRoute) {
            ManageRoute -> {
                lastHomeBackPressAt = 0L
                showRoot(HomeRoute)
            }

            HomeRoute -> {
                val now = System.currentTimeMillis()
                if (now - lastHomeBackPressAt <= EXIT_CONFIRM_WINDOW_MS) {
                    (context as? Activity)?.finish()
                } else {
                    lastHomeBackPressAt = now
                    Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NavDisplay(
                backStack = backStack,
                onBack = ::popBackStack,
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        HomeScreen(onNavigate = { route -> backStack.add(route as NavKey) })
                    }
                    entry<ManageRoute> {
                        ManageScreen(onNavigate = { route -> backStack.add(route as NavKey) })
                    }
                    entry<TutorialRoute> { key ->
                        TutorialScreen(
                            onExit = {
                                if (key.firstLaunch) {
                                    showRoot(HomeRoute)
                                    scope.launch {
                                        ChatBarApp.instance.settingsRepository.completeTutorial(CURRENT_TUTORIAL_VERSION)
                                    }
                                } else {
                                    popBackStack()
                                }
                            }
                        )
                    }
                    entry<ChatRoute> { key ->
                        ChatScreen(sessionId = key.sessionId, onBack = ::popBackStack)
                    }
                    entry<CharacterEditRoute> { key ->
                        CharacterEditScreen(characterId = key.characterId, onBack = ::popBackStack)
                    }
                    entry<ModelEditRoute> { key ->
                        ModelEditScreen(modelId = key.modelId, onBack = ::popBackStack)
                    }
                    entry<FormatCardEditRoute> { key ->
                        FormatCardEditScreen(formatCardId = key.formatCardId, onBack = ::popBackStack)
                    }
                }
            )
        }
        if (currentRoute == HomeRoute || currentRoute == ManageRoute) {
            BottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (backStack.lastOrNull() != route) {
                        showRoot(route)
                    }
                }
            )
        }
    }
}

const val CURRENT_TUTORIAL_VERSION = 1
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
