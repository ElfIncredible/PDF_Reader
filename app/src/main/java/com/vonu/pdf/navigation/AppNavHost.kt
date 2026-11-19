package com.vonu.pdf.navigation

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vonu.pdf.ui.browser.BrowserScreen
import com.vonu.pdf.ui.history.HistoryScreen
import com.vonu.pdf.ui.home.HomeScreen
import com.vonu.pdf.ui.settings.SettingsScreen
import com.vonu.pdf.ui.assistant.AssistantScreen
import com.vonu.pdf.ui.batch.BatchScreen
import com.vonu.pdf.ui.compare.CompareScreen
import com.vonu.pdf.ui.forms.FormScreen
import com.vonu.pdf.ui.metadata.MetadataScreen
import com.vonu.pdf.ui.redact.RedactScreen
import com.vonu.pdf.ui.sign.DigitalSignScreen
import com.vonu.pdf.ui.tools.OrganizeScreen
import com.vonu.pdf.ui.tools.PickPdfFirst
import com.vonu.pdf.ui.tools.ToolKind
import com.vonu.pdf.ui.tools.ToolScreen
import com.vonu.pdf.ui.tools.ToolsHubScreen
import com.vonu.pdf.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val TOOLS = "tools"
    const val TOOL = "tool/{kind}?uri={uri}"
    const val VIEWER = "viewer?uri={uri}&action={action}"

    fun viewer(uri: Uri, action: String = "") =
        "viewer?uri=${Uri.encode(uri.toString())}&action=${Uri.encode(action)}"

    fun tool(kind: ToolKind, uri: Uri? = null) =
        "tool/${kind.id}" + (uri?.let { "?uri=${Uri.encode(it.toString())}" } ?: "")
}

/** The three top-level tabs. Everything else opens on top of them without the bar. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    FILES(Routes.HOME, "Files", Icons.Outlined.Description),
    TOOLS(Routes.TOOLS, "Tools", Icons.Outlined.Apps),
    HISTORY(Routes.HISTORY, "History", Icons.Outlined.History),
}

@Composable
fun AppNavHost(
    incomingUri: Uri?,
    onIncomingHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val openDocument: (Uri) -> Unit = { uri ->
        navController.navigate(Routes.viewer(uri)) { launchSingleTop = true }
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            openDocument(incomingUri)
            onIncomingHandled()
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = Tab.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        // Only the tab bar's own height; each screen still handles the status and gesture bars.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { outer ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // Screens keep their own Scaffolds; hand them only the space the tab bar leaves.
            modifier = Modifier.padding(outer).consumeWindowInsets(outer),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenDocument = openDocument,
                    onBrowse = { navController.navigate(Routes.BROWSER) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.BROWSER) {
                BrowserScreen(
                    onOpenDocument = openDocument,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.TOOLS) {
                ToolsHubScreen(onOpenTool = { navController.navigate(Routes.tool(it)) })
            }
            composable(
                route = Routes.TOOL,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("uri") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val kind = ToolKind.fromId(entry.arguments?.getString("kind"))
                val uri = entry.arguments?.getString("uri").orEmpty().takeIf { it.isNotEmpty() }?.let(Uri::parse)
                when (kind) {
                    null -> LaunchedEffect(Unit) { navController.popBackStack() }
                    ToolKind.SIGN -> PickPdfFirst(
                        initialUri = uri,
                        kind = kind,
                        message = "Pick the PDF to sign. It opens in the editor with your signatures ready.",
                        onBack = { navController.popBackStack() },
                    ) { chosen ->
                        LaunchedEffect(chosen) {
                            navController.navigate(Routes.viewer(chosen, "sign")) { popUpTo(Routes.TOOL) { inclusive = true } }
                        }
                    }
                    ToolKind.FILL_FORM -> FormScreen(
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = { saved ->
                            navController.navigate(Routes.viewer(saved)) { popUpTo(Routes.TOOL) { inclusive = true } }
                        },
                    )
                    ToolKind.ASSISTANT -> if (ToolKind.ASSISTANT.comingSoon) {
                        // Held back for a later update; leave if someone lands here from an old link.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        AssistantScreen(
                            initialUri = uri,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                    ToolKind.REDACT -> RedactScreen(
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = openDocument,
                    )
                    ToolKind.COMPARE -> CompareScreen(onBack = { navController.popBackStack() })
                    ToolKind.BATCH -> BatchScreen(onBack = { navController.popBackStack() })
                    ToolKind.METADATA -> MetadataScreen(
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = openDocument,
                    )
                    ToolKind.DIGITAL_SIGN -> DigitalSignScreen(
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = openDocument,
                    )
                    ToolKind.ORGANIZE -> OrganizeScreen(
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = { saved ->
                            navController.navigate(Routes.viewer(saved)) { popUpTo(Routes.TOOL) { inclusive = true } }
                        },
                    )
                    else -> ToolScreen(
                        kind = kind,
                        initialUri = uri,
                        onBack = { navController.popBackStack() },
                        onOpenDocument = openDocument,
                    )
                }
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenDocument = openDocument)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.VIEWER,
                arguments = listOf(
                    navArgument("uri") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("action") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val raw = entry.arguments?.getString("uri").orEmpty()
                val action = entry.arguments?.getString("action").orEmpty()
                if (raw.isEmpty()) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    ViewerScreen(
                        uri = Uri.parse(raw),
                        startAction = action,
                        onBack = { navController.popBackStack() },
                        // After "Save as copy", swap this reader for the new file.
                        onOpenDocument = { copy ->
                            navController.navigate(Routes.viewer(copy)) {
                                popUpTo(Routes.VIEWER) { inclusive = true }
                            }
                        },
                        onOpenTool = { kind, doc ->
                            navController.navigate(Routes.tool(kind, doc)) {
                                // These may rewrite this very file, so close the reader first.
                                if (kind == ToolKind.ORGANIZE || kind == ToolKind.FILL_FORM) {
                                    popUpTo(Routes.VIEWER) { inclusive = true }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
