package com.folio.pdf.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.folio.pdf.ui.tools.ToolKind
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    title: String,
    bookmarked: Boolean,
    favourite: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onEdit: () -> Unit,
    unsaved: Boolean,
    onToggleBookmark: () -> Unit,
    onContents: () -> Unit,
    onReadingMode: () -> Unit,
    onGoToPage: () -> Unit,
    onToggleFavourite: () -> Unit,
    onShare: () -> Unit,
    onTool: (ToolKind) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    if (unsaved) "Edit (unsaved changes)" else "Edit",
                    tint = if (unsaved) MaterialTheme.colorScheme.tertiary else LocalContentColor.current,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                if (bookmarked) {
                    Icon(Icons.Filled.Bookmark, "Remove bookmark", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.BookmarkBorder, "Bookmark this page")
                }
            }
            IconButton(onClick = onContents) {
                Icon(Icons.AutoMirrored.Outlined.List, "Contents, bookmarks and pages")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Reading mode") },
                        leadingIcon = { Icon(Icons.Outlined.AutoStories, null) },
                        onClick = { menu = false; onReadingMode() },
                    )
                    DropdownMenuItem(
                        text = { Text("Go to page") },
                        leadingIcon = { Icon(Icons.Outlined.FindInPage, null) },
                        onClick = { menu = false; onGoToPage() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (favourite) "Remove from favourites" else "Add to favourites") },
                        leadingIcon = {
                            Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder, null)
                        },
                        onClick = { menu = false; onToggleFavourite() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share PDF") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = { menu = false; onShare() },
                    )
                    HorizontalDivider()
                    listOf(
                        ToolKind.FILL_FORM, ToolKind.DIGITAL_SIGN, ToolKind.OCR,
                        ToolKind.ORGANIZE, ToolKind.COMPRESS, ToolKind.SPLIT, ToolKind.PDF_TO_IMAGES,
                    ).forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.title) },
                            leadingIcon = { Icon(kind.icon, null) },
                            onClick = { menu = false; onTool(kind) },
                        )
                    }
                }
            }
        },
        colors = barColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    state: SearchState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShowResults: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Search in document") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = {
            if (state.hits.isNotEmpty()) {
                TextButton(onClick = { keyboard?.hide(); onShowResults() }) {
                    Text("${state.current + 1}/${state.hits.size}")
                }
            }
            IconButton(onClick = { keyboard?.hide(); onPrevious() }, enabled = state.hits.isNotEmpty()) {
                Icon(Icons.Outlined.KeyboardArrowUp, "Previous match")
            }
            IconButton(onClick = { keyboard?.hide(); onNext() }, enabled = state.hits.isNotEmpty()) {
                Icon(Icons.Outlined.KeyboardArrowDown, "Next match")
            }
        },
        colors = barColors(),
    )
}

/** Thin strip under the search bar explaining what's going on. */
@Composable
fun SearchStatus(index: IndexProgress, search: SearchState) {
    Column(Modifier.fillMaxWidth()) {
        if (!index.finished) {
            LinearProgressIndicator(progress = { index.fraction }, modifier = Modifier.fillMaxWidth())
        }
        val message = when {
            index.failed -> "Couldn't read the text in this PDF, so it can't be searched."
            index.finished && !index.hasText ->
                "This PDF has no searchable text. It may be a scan; text recognition (OCR) arrives in Phase 5."
            search.query.isNotBlank() && search.hits.isEmpty() && !search.searching && index.finished ->
                "No matches for \"${search.query.trim()}\""
            !index.finished -> "Reading text… ${(index.fraction * 100).roundToInt()}%. Matches appear as pages are read."
            search.hits.size >= 2000 -> "Showing the first 2,000 matches"
            else -> null
        }
        if (message != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    preview: String,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSearch: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Clear selection") } },
        title = {
            Text(
                preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
        },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy") }
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search for this") }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share text") }
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Select whole page") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
fun PageBar(
    current: Int,
    count: Int,
    onSeek: (Int) -> Unit,
    onTapIndicator: () -> Unit,
    zoom: ContinuousZoom?,
) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onTapIndicator) { Text("${current + 1} / $count") }
            if (count > 1) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: current.toFloat(),
                    onValueChange = { v -> dragging = v; onSeek(v.roundToInt()) },
                    onValueChangeFinished = { dragging = null },
                    valueRange = 0f..(count - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            if (zoom != null) {
                IconButton(
                    onClick = { zoom.value = (zoom.value / 1.25f).coerceAtLeast(MIN_ZOOM) },
                    enabled = zoom.value > MIN_ZOOM,
                ) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
                IconButton(
                    onClick = { zoom.value = (zoom.value * 1.25f).coerceAtMost(MAX_ZOOM) },
                    enabled = zoom.value < MAX_ZOOM,
                ) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
            }
        }
    }
}

@Composable
fun PagePill(page: Int, count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            "$page / $count",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val page = text.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                supportingText = { Text("1 – $pageCount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(page!!) }),
            )
        },
        confirmButton = { TextButton(onClick = { page?.let(onGo) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hides the status and navigation bars while reading full-screen. */
@Composable
fun ImmersiveEffect(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
