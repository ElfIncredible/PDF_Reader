package com.folio.pdf.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.pdf.FolioApp
import com.folio.pdf.data.AppSettings
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.ui.components.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
fun ViewerScreen(uri: Uri, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val vm: ViewerViewModel = viewModel(
        key = uri.toString(),
        factory = viewModelFactory {
            initializer { ViewerViewModel(uri, app, app.container.documents, app.container.recents) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by app.container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    when (val s = state) {
        ViewerUiState.Loading -> SimpleViewerScaffold("Opening…", onBack) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        is ViewerUiState.Error -> SimpleViewerScaffold("", onBack) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = s.title,
                message = s.message,
                modifier = Modifier.align(Alignment.Center),
            ) { Button(onClick = onBack) { Text("Go back") } }
        }
        is ViewerUiState.Ready -> PdfReader(s, uri, onBack, vm::onPageChanged)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleViewerScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding), content = content) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfReader(
    state: ViewerUiState.Ready,
    uri: Uri,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val doc = state.document
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.initialPage)
    val hScroll = rememberScrollState()
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showGoTo by remember { mutableStateOf(false) }

    // `zoom` follows the fingers; `renderZoom` trails it so we only re-render
    // sharp bitmaps once the pinch settles.
    var zoom by remember { mutableFloatStateOf(1f) }
    var renderZoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoom) {
        delay(220)
        renderZoom = zoom
    }

    val currentPage by remember { derivedStateOf { listState.mostVisibleIndex() } }
    LaunchedEffect(currentPage) { onPageChanged(currentPage) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Two-finger pinch. Watching the Initial pass lets us grab the gesture
                    // before the list turns it into a scroll; one-finger drags pass through.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val factor = event.calculateZoom()
                                val old = zoom
                                val new = (old * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                if (new != old) {
                                    val c = event.calculateCentroid(useCurrent = true)
                                    val ratio = new / old
                                    zoom = new
                                    // Keep the point under the fingers roughly in place.
                                    hScroll.dispatchRawDelta((hScroll.value + c.x) * (ratio - 1f))
                                    listState.dispatchRawDelta(
                                        (listState.firstVisibleItemScrollOffset + c.y) * (ratio - 1f),
                                    )
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val viewportWidth = maxWidth
            val pagePadding = 8.dp
            val renderWidthPx = with(density) {
                ((viewportWidth - pagePadding * 2) * renderZoom).toPx().roundToInt()
            }
            val bars = WindowInsets.systemBars.asPaddingValues()

            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll, enabled = zoom > 1.01f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(viewportWidth * zoom).fillMaxHeight(),
                    contentPadding = PaddingValues(
                        top = bars.calculateTopPadding() + 64.dp,
                        bottom = bars.calculateBottomPadding() + 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp * zoom),
                ) {
                    items(count = doc.pageCount, key = { it }) { index ->
                        PdfPage(
                            doc = doc,
                            index = index,
                            renderWidthPx = renderWidthPx,
                            placeholderAspect = state.placeholderAspect,
                            horizontalPadding = pagePadding * zoom,
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { zoom = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM },
                        )
                    }
                }
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) }, enabled = zoom > MIN_ZOOM) {
                        Icon(Icons.Outlined.ZoomOut, "Zoom out")
                    }
                    IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) }, enabled = zoom < MAX_ZOOM) {
                        Icon(Icons.Outlined.ZoomIn, "Zoom in")
                    }
                    IconButton(onClick = { showGoTo = true }) { Icon(Icons.Outlined.FindInPage, "Go to page") }
                    IconButton(onClick = { sharePdf(context, uri, state.title) }) {
                        Icon(Icons.Outlined.Share, "Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                ),
            )
        }

        // Bottom page bar
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PageBar(
                current = currentPage,
                count = doc.pageCount,
                onSeek = { page -> scope.launch { listState.scrollToItem(page) } },
                onTapIndicator = { showGoTo = true },
            )
        }

        // Page pill stays visible when chrome is hidden, for orientation.
        AnimatedVisibility(
            visible = !chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            PagePill(currentPage + 1, doc.pageCount)
        }
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = doc.pageCount,
            current = currentPage + 1,
            onDismiss = { showGoTo = false },
            onGo = { page ->
                showGoTo = false
                scope.launch { listState.scrollToItem(page - 1) }
            },
        )
    }
}

@Composable
private fun PdfPage(
    doc: PdfFile,
    index: Int,
    renderWidthPx: Int,
    placeholderAspect: Float,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    var aspect by remember(index) { mutableFloatStateOf(placeholderAspect) }
    // Keep showing the previous bitmap while a sharper one renders, so zooming never flickers.
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(doc.cachedPage(index, renderWidthPx)) }

    LaunchedEffect(index, renderWidthPx) {
        doc.pageSize(index)?.let { aspect = it.aspect }
        doc.renderPage(index, renderWidthPx)?.let { bitmap = it }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / aspect)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() })
                },
            color = Color.White,
            shadowElevation = 2.dp,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
private fun PageBar(current: Int, count: Int, onSeek: (Int) -> Unit, onTapIndicator: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTapIndicator) { Text("${current + 1} / $count") }
            if (count > 1) {
                // Local slider state so dragging is smooth; seek on every change.
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: current.toFloat(),
                    onValueChange = { v -> dragging = v; onSeek(v.roundToInt()) },
                    onValueChangeFinished = { dragging = null },
                    valueRange = 0f..(count - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PagePill(page: Int, count: Int) {
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
private fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val page = text.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    supportingText = { Text("1 – $pageCount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(onClick = { page?.let(onGo) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The page whose area covers the vertical centre of the viewport. */
private fun LazyListState.mostVisibleIndex(): Int {
    val info = layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.firstOrNull { centre in it.offset..(it.offset + it.size) }?.index
        ?: firstVisibleItemIndex
}

private fun sharePdf(context: android.content.Context, uri: Uri, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share PDF"))
    } catch (_: ActivityNotFoundException) {
        // Nothing can receive it; nothing to do.
    }
}
