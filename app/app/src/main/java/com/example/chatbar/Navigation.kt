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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.chatbar.ui.moments.MomentsScreen
import com.example.chatbar.ui.character.CharacterEditScreen
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.ui.model.ModelEditScreen
import com.example.chatbar.ui.format.FormatCardEditScreen
import com.example.chatbar.ui.worldbook.WorldBookEditScreen
import com.example.chatbar.ui.tutorial.TutorialScreen
import com.example.chatbar.ui.kit.swipeToAdjacentTab
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MainNavigation(tutorialCompleted: Boolean, sharedImportUri: StateFlow<Uri?>) {
    val initialRoute: NavKey = if (tutorialCompleted) HomeRoute else TutorialRoute(firstLaunch = true)
    val backStack = rememberNavBackStack(initialRoute)
    val currentRoute = backStack.lastOrNull() ?: HomeRoute
    val communityEnabled by ChatBarApp.instance.communityService.enabled.collectAsState()
    val appSettings by ChatBarApp.instance.settingsRepository.appSettings.collectAsState(initial = com.example.chatbar.data.local.entity.AppSettings())
    val momentsEnabled = appSettings.momentsEnabled
    val chatSessions by ChatBarApp.instance.chatRepository.sessions.collectAsState(initial = emptyList())
    val momentPosts by ChatBarApp.instance.momentRepository.posts.collectAsState(initial = emptyList())
    val latestAssistantMessageAt = remember(chatSessions) {
        chatSessions.asSequence()
            .filter { it.lastMessageRole == MessageRole.ASSISTANT }
            .mapNotNull { it.lastMessageTime }
            .maxOrNull() ?: 0L
    }
    val latestMomentAt = remember(momentPosts) {
        momentPosts.maxOfOrNull { it.generatedAt } ?: 0L
    }
    val rootRoutes = remember(communityEnabled, momentsEnabled) {
        buildList<NavKey> {
            add(HomeRoute)
            if (momentsEnabled) add(MomentsRoute)
            if (communityEnabled) add(CommunityRoute)
            add(ManageRoute)
        }
    }
    val currentRootIndex = rootRoutes.indexOf(currentRoute)
    val chatHasUnread = currentRoute != HomeRoute && latestAssistantMessageAt > appSettings.lastSeenChatAt
    val momentsHasUnread = momentsEnabled && currentRoute != MomentsRoute && latestMomentAt > appSettings.lastSeenMomentsAt
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
        val targetRoute = when {
            route == CommunityRoute && !communityEnabled -> HomeRoute
            route == MomentsRoute && !momentsEnabled -> HomeRoute
            else -> route
        }
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        if (backStack.isEmpty()) {
            backStack.add(targetRoute)
        } else {
            backStack[0] = targetRoute
        }
    }

    fun showRootAt(index: Int) {
        rootRoutes.getOrNull(index)?.let(::showRoot)
    }

    fun pushRoute(route: NavKey) {
        backStack.add(route)
    }

    LaunchedEffect(communityEnabled, momentsEnabled, currentRoute) {
        if ((!communityEnabled && currentRoute == CommunityRoute) || (!momentsEnabled && currentRoute == MomentsRoute)) {
            showRoot(HomeRoute)
        }
    }
    LaunchedEffect(Unit) {
        ChatBarApp.instance.chatRepository.initialize()
        ChatBarApp.instance.momentRepository.initialize()
        ChatBarApp.instance.settingsRepository.initialize()
    }
    LaunchedEffect(currentRoute, latestAssistantMessageAt) {
        if (currentRoute == HomeRoute && latestAssistantMessageAt > 0L) {
            val current = ChatBarApp.instance.settingsRepository.getAppSettings()
            if (latestAssistantMessageAt > current.lastSeenChatAt) {
                ChatBarApp.instance.settingsRepository.saveAppSettings(
                    current.copy(lastSeenChatAt = latestAssistantMessageAt)
                )
            }
        }
    }
    LaunchedEffect(currentRoute, latestMomentAt) {
        if (currentRoute == MomentsRoute && latestMomentAt > 0L) {
            val current = ChatBarApp.instance.settingsRepository.getAppSettings()
            if (latestMomentAt > current.lastSeenMomentsAt) {
                ChatBarApp.instance.settingsRepository.saveAppSettings(
                    current.copy(lastSeenMomentsAt = latestMomentAt)
                )
            }
        }
    }

    BackHandler(
        enabled = backStack.size == 1 && (
            currentRoute == HomeRoute ||
                (momentsEnabled && currentRoute == MomentsRoute) ||
                (communityEnabled && currentRoute == CommunityRoute) ||
                currentRoute == ManageRoute
            )
    ) {
        when (currentRoute) {
            MomentsRoute,
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

    val rootSwipeModifier = Modifier.swipeToAdjacentTab(
        selectedIndex = currentRootIndex.coerceAtLeast(0),
        itemCount = rootRoutes.size,
        onSelected = ::showRootAt,
        enabled = currentRootIndex >= 0
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).then(rootSwipeModifier)) {
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
                    entry<MomentsRoute> {
                        MomentsScreen()
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
        if (currentRootIndex >= 0) {
            BottomNavBar(
                currentRoute = currentRoute,
                communityEnabled = communityEnabled,
                momentsEnabled = momentsEnabled,
                chatHasUnread = chatHasUnread,
                momentsHasUnread = momentsHasUnread,
                modifier = rootSwipeModifier,
                onNavigate = { route ->
                    if ((route == CommunityRoute && !communityEnabled) || (route == MomentsRoute && !momentsEnabled)) {
                        showRoot(HomeRoute)
                    } else if (backStack.lastOrNull() != route) {
                        showRoot(route)
                    }
                }
            )
        }
    }
}

const val CURRENT_TUTORIAL_VERSION = 1
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
