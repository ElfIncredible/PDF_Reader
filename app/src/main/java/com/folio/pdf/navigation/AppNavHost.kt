package com.folio.pdf.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.folio.pdf.ui.browser.BrowserScreen
import com.folio.pdf.ui.history.HistoryScreen
import com.folio.pdf.ui.home.HomeScreen
import com.folio.pdf.ui.settings.SettingsScreen
import com.folio.pdf.ui.forms.FormScreen
import com.folio.pdf.ui.sign.DigitalSignScreen
import com.folio.pdf.ui.tools.OrganizeScreen
import com.folio.pdf.ui.tools.PickPdfFirst
import com.folio.pdf.ui.tools.ToolKind
import com.folio.pdf.ui.tools.ToolScreen
import com.folio.pdf.ui.tools.ToolsHubScreen
import com.folio.pdf.ui.viewer.ViewerScreen

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

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenDocument = openDocument,
                onBrowse = { navController.navigate(Routes.BROWSER) },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onTool = { kind -> navController.navigate(if (kind == null) Routes.TOOLS else Routes.tool(kind)) },
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
            ToolsHubScreen(
                onOpenTool = { navController.navigate(Routes.tool(it)) },
                onBack = { navController.popBackStack() },
            )
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
            HistoryScreen(
                onOpenDocument = openDocument,
                onBack = { navController.popBackStack() },
            )
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
