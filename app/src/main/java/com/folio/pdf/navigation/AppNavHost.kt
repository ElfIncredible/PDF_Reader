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
import com.folio.pdf.ui.home.HomeScreen
import com.folio.pdf.ui.settings.SettingsScreen
import com.folio.pdf.ui.viewer.ViewerScreen

object Routes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer?uri={uri}"

    fun viewer(uri: Uri) = "viewer?uri=${Uri.encode(uri.toString())}"
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
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.BROWSER) {
            BrowserScreen(
                onOpenDocument = openDocument,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.VIEWER,
            arguments = listOf(navArgument("uri") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { entry ->
            val raw = entry.arguments?.getString("uri").orEmpty()
            if (raw.isEmpty()) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                ViewerScreen(uri = Uri.parse(raw), onBack = { navController.popBackStack() })
            }
        }
    }
}
