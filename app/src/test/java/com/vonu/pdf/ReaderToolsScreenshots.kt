package com.vonu.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.vonu.pdf.ui.theme.VonuTheme
import com.vonu.pdf.ui.tools.ToolKind
import com.vonu.pdf.ui.viewer.PageBar
import com.vonu.pdf.ui.viewer.ReaderToolStrip
import com.vonu.pdf.ui.viewer.ReaderToolsSheet
import com.vonu.pdf.ui.viewer.rankReaderTools
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's tool strip and its "More" sheet, for the docs. PdfRenderer can't run on the JVM,
 * so the page is a plain placeholder; the strip, page bar and sheet are the real composables.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ReaderToolsScreenshots {

    @get:Rule
    val compose = createComposeRule()

    // A phone that signs and compresses a lot, opening a PDF with a form.
    private val tools = rankReaderTools(
        locked = false,
        hasForm = true,
        isScan = false,
        uses = mapOf(ToolKind.SIGN.id to 6, ToolKind.COMPRESS.id to 3),
    )

    @Test
    fun strip() {
        compose.setContent {
            VonuTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFFDAD4C8))) {
                    Box(
                        Modifier.align(Alignment.TopCenter).padding(24.dp).fillMaxWidth().aspectRatio(0.707f)
                            .background(Color.White).border(1.dp, Color(0xFFCCC4B5)),
                        contentAlignment = Alignment.Center,
                    ) { Text("PDF page", color = Color(0xFF9AA3A5)) }
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            ReaderToolStrip(tools, onTool = {}, onMore = {})
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PageBar(current = 2, count = 14, onSeek = {}, onTapIndicator = {}, zoom = null)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("../docs/screenshots/06-reader-tools.png")
    }

    @Test
    fun moreSheet() {
        compose.setContent {
            VonuTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFFDAD4C8)))
                ReaderToolsSheet(tools.drop(com.vonu.pdf.ui.viewer.READER_STRIP_SIZE), onTool = {}, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../docs/screenshots/07-reader-more-tools.png")
    }
}
