package com.vonu.pdf

import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vonu.pdf.data.DocumentInfo
import com.vonu.pdf.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real app screens for the user manual and technical docs (docs/screenshots).
 * Not a regression test: run it when the UI changes, then commit the images.
 *
 *     ./gradlew :app:testDebugUnitTest --tests "*DocScreenshots*"
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class DocScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<VonuApp>()

    @Before
    fun seed() = runBlocking {
        val recents = app.container.recents
        val now = System.currentTimeMillis()
        listOf(
            // Opened oldest first, so the lease (mid-read) is the "Continue reading" card.
            Triple("Product brochure.pdf", 24, 0),
            Triple("Board minutes - October.pdf", 8, 0),
            Triple("Invoice VN-0142.pdf", 2, 1),
            Triple("Lease agreement 2026.pdf", 14, 6),
        ).forEachIndexed { i, (name, pages, page) ->
            val uri = Uri.parse("content://docs.sample/$i")
            recents.recordOpened(DocumentInfo(uri, name, 180_000L + i * 420_000L, now), pages)
            Thread.sleep(5)
            if (page > 0) recents.saveProgress(uri.toString(), page)
            if (i == 2) recents.toggleFavorite(uri.toString())
        }
    }

    private fun theme(mode: ThemeMode) = runBlocking { app.container.settings.setThemeMode(mode) }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/screenshots/$name.png")
    }

    private fun pastLanding() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntilAtLeastOneExists(hasText("Open a PDF"), 5_000)
    }

    @Test
    fun light() {
        theme(ThemeMode.LIGHT)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        shot("00-landing")
        compose.mainClock.autoAdvance = true
        pastLanding()
        shot("01-files")
        compose.onNodeWithText("Tools").performClick()
        shot("02-tools")
        compose.onNodeWithText("Merge PDFs").performClick()
        shot("03-tool-merge")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("History").performClick()
        shot("04-history")
        compose.onNodeWithText("Files").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        shot("05-settings")
    }

    @Test
    fun dark() {
        theme(ThemeMode.DARK)
        pastLanding()
        shot("11-files-dark")
        compose.onNodeWithText("Tools").performClick()
        shot("12-tools-dark")
    }
}
