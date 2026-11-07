package com.vonu.pdf.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vonu.pdf.data.ColorMode
import com.vonu.pdf.util.Reflow

/**
 * Text view: the document's words as plain, resizable paragraphs. Easy to read on small
 * screens, and the lightest way to read: no page images are drawn at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReflowReader(
    env: PageEnv,
    scale: Float,
    initialPage: Int,
    readingPage: Int?,
    onNavigator: (PageNavigator?) -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val navigator = remember(listState) {
        object : PageNavigator {
            override suspend fun goTo(page: Int, yFraction: Float) = listState.scrollToItem(page)
        }
    }
    DisposableEffect(navigator) {
        onNavigator(navigator)
        onDispose { onNavigator(null) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onPageChanged(it) }
    }

    val (paper, ink) = when (env.colorMode) {
        ColorMode.NORMAL -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
        ColorMode.SEPIA -> Color(0xFFF4ECD8) to Color(0xFF3E2F1C)
        ColorMode.NIGHT -> Color(0xFF121212) to Color(0xFFD0D0D0)
    }
    val bars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    val bodySize = (17 * scale).sp

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(paper).clickable(interactionSource = null, indication = null) { env.onTap() },
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 72.dp,
            bottom = bars.calculateBottomPadding() + 96.dp,
        ),
    ) {
        items(count = env.doc.pageCount, key = { it }) { index ->
            // textVersion changes as more pages are read in the background.
            val text = remember(index, env.textVersion) { env.pageText(index) }
            val paragraphs = remember(text) { text?.let { Reflow.paragraphs(it.text) } }
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Page ${index + 1}" + if (readingPage == index) "  🔊" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (readingPage == index) MaterialTheme.colorScheme.primary else ink.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                when {
                    paragraphs == null -> Text("Reading this page…", color = ink.copy(alpha = 0.6f))
                    paragraphs.isEmpty() -> Text(
                        "No text on this page. It may be a picture or a scan (Tools → Make PDF searchable).",
                        color = ink.copy(alpha = 0.6f),
                        fontSize = (14 * scale).sp,
                    )
                    else -> paragraphs.forEach { p ->
                        Text(p, color = ink, fontSize = bodySize, lineHeight = bodySize * 1.45f)
                    }
                }
                HorizontalDivider(color = ink.copy(alpha = 0.12f), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
