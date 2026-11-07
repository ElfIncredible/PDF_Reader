package com.folio.pdf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.folio.pdf.data.ColorMode
import com.folio.pdf.data.ScrollMode
import com.folio.pdf.data.db.Bookmark
import com.folio.pdf.pdf.OutlineEntry
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.SearchHit
import com.folio.pdf.ui.components.EmptyState

// ---- Contents · Bookmarks · Pages ------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSheet(
    doc: PdfFile,
    colorMode: ColorMode,
    currentPage: Int,
    outline: List<OutlineEntry>?,
    bookmarks: List<Bookmark>,
    onGoTo: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Open on Contents when the PDF has one, otherwise on the page grid.
    var tab by rememberSaveable { mutableIntStateOf(if (outline.isNullOrEmpty()) 2 else 0) }
    val tabs = listOf("Contents", "Bookmarks (${bookmarks.size})", "Pages")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) })
            }
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            when (tab) {
                0 -> OutlineList(outline, currentPage, onGoTo)
                1 -> BookmarkList(bookmarks, onGoTo, onDeleteBookmark)
                else -> PageGrid(doc, colorMode, currentPage, onGoTo)
            }
        }
    }
}

@Composable
private fun OutlineList(outline: List<OutlineEntry>?, currentPage: Int, onGoTo: (Int) -> Unit) {
    when {
        outline == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        outline.isEmpty() -> EmptyState(
            icon = Icons.AutoMirrored.Outlined.List,
            title = "No table of contents",
            message = "This PDF doesn't include one. Use Pages to jump around, or bookmark the pages you need.",
        )
        else -> {
            // The chapter you're in: the last entry starting at or before the current page.
            val currentEntry = outline.indexOfLast { it.page in 0..currentPage }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentEntry - 2).coerceAtLeast(0))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(outline) { i, entry ->
                    val enabled = entry.page >= 0
                    ListItem(
                        modifier = Modifier.clickable(enabled = enabled) { onGoTo(entry.page) },
                        headlineContent = {
                            Text(
                                entry.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (entry.depth == 0) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.padding(start = (entry.depth * 16).dp),
                            )
                        },
                        trailingContent = { if (enabled) Text("${entry.page + 1}") },
                        colors = if (i == currentEntry) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else ListItemDefaults.colors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkList(bookmarks: List<Bookmark>, onGoTo: (Int) -> Unit, onDelete: (Bookmark) -> Unit) {
    if (bookmarks.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.BookmarkBorder,
            title = "No bookmarks yet",
            message = "Tap the bookmark icon at the top of the reader to save the page you're on.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(bookmarks, key = { it.id }) { b ->
            ListItem(
                modifier = Modifier.clickable { onGoTo(b.page) },
                headlineContent = { Text("Page ${b.page + 1}") },
                supportingContent = if (b.label.isNotBlank()) {
                    { Text(b.label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                } else null,
                trailingContent = {
                    IconButton(onClick = { onDelete(b) }) { Icon(Icons.Outlined.Delete, "Delete bookmark") }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PageGrid(doc: PdfFile, colorMode: ColorMode, currentPage: Int, onGoTo: (Int) -> Unit) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 3).coerceAtLeast(0))
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = doc.pageCount, key = { it }) { index ->
            PageThumb(doc, colorMode, index, selected = index == currentPage, onClick = { onGoTo(index) })
        }
    }
}

@Composable
private fun PageThumb(doc: PdfFile, colorMode: ColorMode, index: Int, selected: Boolean, onClick: () -> Unit) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(doc.cachedPage(index, THUMB_WIDTH)) }
    LaunchedEffect(index) { if (bitmap == null) bitmap = doc.renderPage(index, THUMB_WIDTH) }

    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
            color = colorMode.paper,
            border = BorderStroke(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            shadowElevation = 1.dp,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    colorFilter = colorMode.filter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val THUMB_WIDTH = 220

// ---- Reading mode ----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingModeSheet(
    prefs: ReaderPrefs,
    onScrollMode: (ScrollMode) -> Unit,
    onColorMode: (ColorMode) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onReflowScale: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Reading mode", style = MaterialTheme.typography.titleLarge)

            Text("Page flow", style = MaterialTheme.typography.labelLarge)
            val flows = listOf(ScrollMode.VERTICAL to "Scroll", ScrollMode.PAGED to "Pages", ScrollMode.TEXT to "Text")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                flows.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = prefs.scrollMode == mode,
                        onClick = { onScrollMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, flows.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Text(
                when (prefs.scrollMode) {
                    ScrollMode.VERTICAL -> "Pages one under another; scroll down."
                    ScrollMode.PAGED -> "One page at a time; swipe sideways."
                    ScrollMode.TEXT -> "Just the words, in a size you choose. Lightest on the battery and easiest on small screens."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (prefs.scrollMode == ScrollMode.TEXT) {
                Text("Text size", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.Slider(
                    value = prefs.reflowScale,
                    onValueChange = onReflowScale,
                    valueRange = 0.8f..2.2f,
                    steps = 6,
                )
            }

            Text("Colours", style = MaterialTheme.typography.labelLarge)
            val colours = listOf(ColorMode.NORMAL to "Normal", ColorMode.SEPIA to "Sepia", ColorMode.NIGHT to "Night")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                colours.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = prefs.colorMode == mode,
                        onClick = { onColorMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, colours.size),
                    ) { Text(label, maxLines = 1) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Stop the screen dimming while you read",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = prefs.keepScreenOn, onCheckedChange = onKeepScreenOn)
            }

            Text(
                "Tip: double-tap to zoom, long-press a word to select text, tap once to hide the controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Search results list ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsSheet(
    state: SearchState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "${state.hits.size} matches for \"${state.query.trim()}\"",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.current.coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.75f)) {
            itemsIndexed(state.hits) { i, hit ->
                ListItem(
                    modifier = Modifier.clickable { onSelect(i) },
                    overlineContent = { Text("Page ${hit.page + 1}") },
                    headlineContent = { Text(highlighted(hit), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = if (i == state.current) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else ListItemDefaults.colors(),
                )
            }
        }
    }
}

private fun highlighted(hit: SearchHit) = buildAnnotatedString {
    val s = hit.snippet
    val m = hit.snippetMatch
    val start = m.first.coerceIn(0, s.length)
    val end = (m.last + 1).coerceIn(start, s.length)
    append(s.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(start, end)) }
    append(s.substring(end))
}
