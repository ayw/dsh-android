package ai.deepseek.dsh.android.navigation

import ai.deepseek.dsh.android.AppScope
import ai.deepseek.dsh.android.R
import ai.deepseek.dsh.android.core.designsystem.R as DshR
import ai.deepseek.dsh.android.designsystem.components.DshGlyph
import ai.deepseek.dsh.android.designsystem.components.DshGlyphIcon
import ai.deepseek.dsh.android.designsystem.theme.DshTheme
import ai.deepseek.dsh.android.feature.conversation.ConversationScreen
import ai.deepseek.dsh.android.feature.conversation.ConversationViewModel
import ai.deepseek.dsh.android.feature.inbox.InboxRoute
import ai.deepseek.dsh.android.feature.inbox.InboxViewModel
import ai.deepseek.dsh.android.feature.sessions.SessionsScreen
import ai.deepseek.dsh.android.feature.sessions.SessionsViewModel
import ai.deepseek.dsh.android.feature.settings.SettingsRoute
import ai.deepseek.dsh.android.feature.settings.SettingsViewModel
import ai.deepseek.dsh.android.model.ServerId
import ai.deepseek.dsh.android.model.SessionId
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** 三个顶层目的地（D05）。 */
@Serializable
data object SessionsTab

@Serializable
data object InboxTab

@Serializable
data object SettingsTab

/** 会话详情：参数是 `SessionId` 的字符串值，路由层不携带任何传输字段。 */
@Serializable
data class ConversationDest(val sessionId: String)

private enum class ShellTab(
    val titleRes: Int,
    val glyph: DshGlyph,
) {
    Sessions(DshR.string.sessions_title, DshGlyph.Ring),
    Inbox(DshR.string.inbox_title, DshGlyph.Dot),
    Settings(DshR.string.settings_title, DshGlyph.Dash),
}

/**
 * 单 Activity、单 NavHost 的壳（D05）。底部栏只在三个顶层目的地出现；
 * 会话详情自带返回按钮并占满内容区，壳不为它再画一层 AppBar。
 *
 * M4 没有配对入口：没有服务器行时读侧走空 `ServerId("")`，屏幕显示“未知”而不是伪造的 0。
 */
@Composable
fun DshApp(appScope: AppScope) {
    val navController = rememberNavController()
    val servers by appScope.backend.servers().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeServerId = servers.firstOrNull()?.serverId ?: ServerId("")
    val graph = remember(activeServerId) { appScope.server(activeServerId) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val showTabs = current?.hasRoute<SessionsTab>() == true ||
        current?.hasRoute<InboxTab>() == true ||
        current?.hasRoute<SettingsTab>() == true

    DshTheme {
        Scaffold(
            bottomBar = {
                if (showTabs) {
                    NavigationBar {
                        ShellTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = when (tab) {
                                    ShellTab.Sessions -> current?.hasRoute<SessionsTab>() == true
                                    ShellTab.Inbox -> current?.hasRoute<InboxTab>() == true
                                    ShellTab.Settings -> current?.hasRoute<SettingsTab>() == true
                                },
                                onClick = {
                                    val route: Any = when (tab) {
                                        ShellTab.Sessions -> SessionsTab
                                        ShellTab.Inbox -> InboxTab
                                        ShellTab.Settings -> SettingsTab
                                    }
                                    navController.switchTab(route)
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                icon = { DshGlyphIcon(glyph = tab.glyph, contentDescription = null) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = SessionsTab,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable<SessionsTab> {
                    val viewModel: SessionsViewModel = viewModel(
                        key = "sessions-${graph.serverId.value}",
                        factory = SessionsViewModel.Factory(graph.sessions, graph.pending, appScope.tuning),
                    )
                    SessionsScreen(
                        viewModel = viewModel,
                        onOpenSession = { sessionId ->
                            navController.navigate(ConversationDest(sessionId.value))
                        },
                        onOpenInbox = { navController.switchTab(InboxTab) },
                    )
                }

                composable<InboxTab> {
                    val viewModel: InboxViewModel = viewModel(
                        key = "inbox-${graph.serverId.value}",
                        factory = InboxViewModel.Factory(
                            pending = graph.pending,
                            sessions = graph.sessions,
                            decoder = appScope.decoder,
                            tuning = appScope.tuning,
                        ),
                    )
                    InboxRoute(
                        viewModel = viewModel,
                        onOpenSession = { sessionId ->
                            navController.navigate(ConversationDest(sessionId.value))
                        },
                    )
                }

                composable<SettingsTab> {
                    val viewModel: SettingsViewModel = viewModel(
                        key = "settings",
                        factory = SettingsViewModel.Factory(
                            backendRepository = appScope.backend,
                            diagnosticsRepository = graph.diagnostics,
                            syncRequestPort = appScope.syncRequestPort,
                            tuning = appScope.tuning,
                        ),
                    )
                    SettingsRoute(viewModel = viewModel, versionName = appVersionName())
                }

                composable<ConversationDest> { entry ->
                    val dest = entry.toRoute<ConversationDest>()
                    val sessionId = SessionId(dest.sessionId)
                    val viewModel: ConversationViewModel = viewModel(
                        key = "conversation-${dest.sessionId}",
                        factory = ConversationViewModel.Factory(
                            sessionId = sessionId,
                            timeline = graph.timeline,
                            pending = graph.pending,
                            decoder = appScope.decoder,
                            tuning = appScope.tuning,
                        ),
                    )
                    ConversationScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun appVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
