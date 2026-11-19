package com.vonu.pdf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vonu.pdf.data.AppSettings
import com.vonu.pdf.data.ThemeMode
import com.vonu.pdf.navigation.AppNavHost
import com.vonu.pdf.ui.components.VonuLanding
import com.vonu.pdf.ui.theme.VonuTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    /** A PDF handed to us by another app ("Open with Vonu"). */
    private val incomingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingUri.value = intent.pdfUri()
        // Brand landing on a cold launch only; "Open with Vonu" goes straight to the document.
        val coldLaunch = savedInstanceState == null && incomingUri.value == null

        val container = (application as VonuApp).container

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Keep status/navigation bar icons readable for the chosen theme.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }

            var landing by rememberSaveable { mutableStateOf(coldLaunch) }
            LaunchedEffect(Unit) {
                if (landing) {
                    delay(LANDING_MS)
                    landing = false
                }
            }

            VonuTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppNavHost(
                            incomingUri = incomingUri.value,
                            onIncomingHandled = { incomingUri.value = null },
                        )
                    }
                    AnimatedVisibility(visible = landing, exit = fadeOut(tween(300))) { VonuLanding() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.pdfUri()?.let { incomingUri.value = it }
    }

    private fun Intent.pdfUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null

    private companion object {
        const val LANDING_MS = 900L
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
