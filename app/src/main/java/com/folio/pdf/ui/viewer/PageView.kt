package com.folio.pdf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.folio.pdf.data.ColorMode
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.pdf.SearchHit

/** A text selection on one page. */
data class PageSelection(val page: Int, val range: IntRange)

/** Everything a page needs from the reader, bundled so both reading modes share it. */
class PageEnv(
    val doc: PdfFile,
    val colorMode: ColorMode,
    val placeholderAspect: Float,
    /** Changes as more text is read, so pages re-check [pageText]. */
    val textVersion: Int,
    val pageText: (Int) -> PageText?,
    val hitsByPage: Map<Int, List<SearchHit>>,
    val activeHit: SearchHit?,
    val selection: PageSelection?,
    val onSelectionChange: (PageSelection?) -> Unit,
    val onTap: () -> Unit,
    val onNoText: () -> Unit,
)

private val SearchColor = Color(0x66FFD54F)
private val ActiveSearchColor = Color(0x99FF9800)
private val SelectionColor = Color(0x553D7BFD)

val ColorMode.paper: Color
    get() = when (this) {
        ColorMode.NORMAL -> Color.White
        ColorMode.SEPIA -> Color(0xFFF4ECD8)
        ColorMode.NIGHT -> Color(0xFF121212)
    }

private val sepiaFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.957f, 0f, 0f, 0f, 0f,
            0f, 0.925f, 0f, 0f, 0f,
            0f, 0f, 0.847f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

// Inverts the page, but softly: white paper becomes near-black, black text becomes light grey.
private val nightFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 235f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

val ColorMode.filter: ColorFilter?
    get() = when (this) {
        ColorMode.NORMAL -> null
        ColorMode.SEPIA -> sepiaFilter
        ColorMode.NIGHT -> nightFilter
    }

/**
 * One rendered page with search highlights and long-press text selection.
 *
 * @param fitWithin true when the page must fit inside the parent (page-by-page mode);
 *   false when it fills the available width (continuous scroll).
 */
@Composable
fun PageView(
    env: PageEnv,
    index: Int,
    renderWidthPx: Int,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
    fitWithin: Boolean = false,
) {
    var aspect by remember(index) { mutableFloatStateOf(env.placeholderAspect) }
    // Keep showing the previous bitmap while a sharper one renders, so zooming never flickers.
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(env.doc.cachedPage(index, renderWidthPx)) }

    LaunchedEffect(index, renderWidthPx) {
        env.doc.pageSize(index)?.let { aspect = it.aspect }
        env.doc.renderPage(index, renderWidthPx)?.let { bitmap = it }
    }

    // Re-read whenever the index grows (textVersion) — the page may have just been read.
    val pageText = remember(index, env.textVersion) { env.pageText(index) }
    val hits = env.hitsByPage[index].orEmpty()
    val active = env.activeHit?.takeIf { it.page == index }
    val hitRects = remember(hits, active, pageText) {
        pageText?.let { t -> hits.filter { it != active }.flatMap { t.rects(it.range) } }.orEmpty()
    }
    val activeRects = remember(active, pageText) {
        if (active == null || pageText == null) emptyList() else pageText.rects(active.range)
    }
    val selected = env.selection?.takeIf { it.page == index }?.range
    val selectionRects = remember(selected, pageText) {
        if (selected == null || pageText == null) emptyList() else pageText.rects(selected)
    }

    val latestEnv by rememberUpdatedState(env)
    val latestText by rememberUpdatedState(pageText)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .aspectRatio(1f / aspect, matchHeightConstraintsFirst = fitWithin)
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { latestEnv.onTap() },
                    onDoubleTap = { latestDoubleTap() },
                )
            }
            .pointerInput(index) {
                // Long-press selects a word; keep holding and drag to extend the selection.
                var anchor: IntRange? = null
                fun charUnder(t: PageText, p: Offset): Int? =
                    t.charAt(p.x / size.width.toFloat(), p.y / size.height.toFloat())

                detectDragGesturesAfterLongPress(
                    onDragStart = start@{ position ->
                        val t = latestText
                        if (t == null || t.isEmpty) {
                            latestEnv.onNoText()
                            return@start
                        }
                        val i = charUnder(t, position) ?: return@start
                        val word = t.wordAt(i)
                        anchor = word
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestEnv.onSelectionChange(PageSelection(index, word))
                    },
                    onDrag = drag@{ change, _ ->
                        val t = latestText ?: return@drag
                        val a = anchor ?: return@drag
                        val i = charUnder(t, change.position) ?: return@drag
                        val range = if (i < a.first) i..a.last else a.first..maxOf(i, a.last)
                        latestEnv.onSelectionChange(PageSelection(index, range))
                    },
                    onDragEnd = { anchor = null },
                    onDragCancel = { anchor = null },
                )
            },
        color = env.colorMode.paper,
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = env.colorMode.filter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    Modifier.size(28.dp).align(Alignment.Center),
                    strokeWidth = 2.dp,
                    color = Color.Gray,
                )
            }
            if (hitRects.isNotEmpty() || activeRects.isNotEmpty() || selectionRects.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    hitRects.forEach { drawNormalized(it, SearchColor) }
                    activeRects.forEach { drawNormalized(it, ActiveSearchColor) }
                    selectionRects.forEach { drawNormalized(it, SelectionColor) }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNormalized(r: Rect, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(r.left * size.width, r.top * size.height),
        size = Size(r.width * size.width, r.height * size.height),
    )
}

// ---- Zoom for page-by-page mode -------------------------------------------------

@Stable
class PageZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    internal var size: IntSize = IntSize.Zero

    val isZoomed: Boolean get() = scale > 1.01f

    fun transform(zoom: Float, pan: Offset) {
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = clamp(offset + pan)
    }

    fun toggle() {
        if (isZoomed) reset() else { scale = 2.5f; offset = Offset.Zero }
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(o: Offset): Offset {
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Pinch to zoom, drag to pan while zoomed. At 1× single-finger drags fall through,
 * so the pager can still swipe between pages.
 */
fun Modifier.zoomable(zoom: PageZoom): Modifier = this
    .onSizeChanged { zoom.size = it }
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || zoom.isZoomed) {
                    zoom.transform(event.calculateZoom(), event.calculatePan())
                    if (fingers >= 2 || zoom.isZoomed) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offset.x
        translationY = zoom.offset.y
    }
