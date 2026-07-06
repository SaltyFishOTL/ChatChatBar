package com.example.chatbar

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.chatbar.ui.components.BottomNavBar
import com.example.chatbar.ui.community.CommunityScreen
import com.example.chatbar.ui.home.HomeScreen
import com.example.chatbar.ui.chat.ChatScreen
import com.example.chatbar.ui.manage.ManageScreen
import com.example.chatbar.ui.character.CharacterEditScreen
import com.example.chatbar.ui.model.ModelEditScreen
import com.example.chatbar.ui.format.FormatCardEditScreen
import com.example.chatbar.ui.worldbook.WorldBookEditScreen
import com.example.chatbar.ui.tutorial.TutorialScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MainNavigation(tutorialCompleted: Boolean, sharedImportUri: StateFlow<Uri?>) {
    val initialRoute: NavKey = if (tutorialCompleted) HomeRoute else TutorialRoute(firstLaunch = true)
    val backStack = rememberNavBackStack(initialRoute)
    val currentRoute = backStack.lastOrNull() ?: HomeRoute
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var lastHomeBackPressAt by remember { mutableLongStateOf(0L) }
    val sharedUri by sharedImportUri.collectAsState()

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

    fun pushRoute(route: NavKey) {
        backStack.add(route as NavKey)
    }

    BackHandler(
        enabled = backStack.size == 1 && (
            currentRoute == HomeRoute ||
                currentRoute == CommunityRoute ||
                currentRoute == ManageRoute
            )
    ) {
        when (currentRoute) {
            CommunityRoute,
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
                transitionSpec = {
                    (
                        fadeIn(tween(durationMillis = 220, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                            scaleIn(initialScale = 0.98f, animationSpec = tween(durationMillis = 220, delayMillis = 90, easing = FastOutSlowInEasing))
                        ).togetherWith(
                            fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing)) +
                                scaleOut(targetScale = 1.02f, animationSpec = tween(durationMillis = 90, easing = FastOutLinearInEasing))
                        )
                },
                popTransitionSpec = {
                    (
                        fadeIn(tween(durationMillis = 220, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                            scaleIn(initialScale = 0.98f, animationSpec = tween(durationMillis = 220, delayMillis = 90, easing = FastOutSlowInEasing))
                        ).togetherWith(
                            fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing)) +
                                scaleOut(targetScale = 1.02f, animationSpec = tween(durationMillis = 90, easing = FastOutLinearInEasing))
                        )
                },
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        HomeScreen(onNavigate = { route -> pushRoute(route as NavKey) })
                    }
                    entry<CommunityRoute> {
                        CommunityScreen()
                    }
                    entry<ManageRoute> {
                        ManageScreen(onNavigate = { route -> pushRoute(route as NavKey) }, sharedUri = sharedUri)
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
                        CharacterEditScreen(characterId = key.characterId, draftId = key.draftId, onBack = ::popBackStack)
                    }
                    entry<ModelEditRoute> { key ->
                        ModelEditScreen(modelId = key.modelId, onBack = ::popBackStack)
                    }
                    entry<FormatCardEditRoute> { key ->
                        FormatCardEditScreen(formatCardId = key.formatCardId, onBack = ::popBackStack)
                    }
                    entry<WorldBookEditRoute> { key ->
                        WorldBookEditScreen(worldBookId = key.worldBookId, onBack = ::popBackStack)
                    }
                }
            )
        }
        if (currentRoute == HomeRoute || currentRoute == CommunityRoute || currentRoute == ManageRoute) {
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
