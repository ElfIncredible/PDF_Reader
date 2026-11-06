package com.folio.pdf.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.folio.pdf.data.ColorMode
import com.folio.pdf.edit.Annotation
import com.folio.pdf.edit.Ink
import com.folio.pdf.edit.Markup
import com.folio.pdf.edit.MarkupKind
import com.folio.pdf.edit.NPoint
import com.folio.pdf.edit.NRect
import com.folio.pdf.edit.Shape
import com.folio.pdf.edit.ShapeKind
import com.folio.pdf.edit.Tool
import com.folio.pdf.edit.ToolSettings
import com.folio.pdf.edit.distance
import com.folio.pdf.edit.hitTest
import com.folio.pdf.edit.isNear
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfFile
import com.folio.pdf.ui.viewer.PageZoom
import com.folio.pdf.ui.viewer.filter
import com.folio.pdf.ui.viewer.paper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the page editor asks of whoever owns the annotations (the ViewModel). */
interface EditorActions {
    fun newId(): Long
    fun create(a: Annotation)
    fun select(id: Long?)
    fun gestureUpdate(a: Annotation)
    fun gestureRemove(id: Long)
    fun gestureEnd()
}

/**
 * One page, editable. One finger uses the current tool; two fingers zoom and pan.
 */
@Composable
fun PageEditor(
    doc: PdfFile,
    pageIndex: Int,
    colorMode: ColorMode,
    pageText: PageText?,
    annotations: List<Annotation>,
    selectedId: Long?,
    settings: ToolSettings,
    actions: EditorActions,
    onRequestText: (NPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val zoom = remember(pageIndex) { PageZoom() }

    // Render at twice the screen width so zooming in stays sharp.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val renderWidthPx = with(density) { (screenWidth * 2).toPx() }.roundToInt().coerceAtMost(3200)

    Box(modifier.fillMaxSize()) {

        Box(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .twoFingerZoom(zoom),
            contentAlignment = Alignment.Center,
        ) {
            var aspect by remember(doc, pageIndex) { mutableFloatStateOf(1.414f) }
            var bitmap by remember(doc, pageIndex) { mutableStateOf<Bitmap?>(doc.cachedPage(pageIndex, renderWidthPx)) }
            LaunchedEffect(doc, pageIndex, renderWidthPx) {
                doc.pageSize(pageIndex)?.let { aspect = it.aspect }
                doc.renderPage(pageIndex, renderWidthPx)?.let { bitmap = it }
            }

            Surface(
                modifier = Modifier.aspectRatio(1f / aspect, matchHeightConstraintsFirst = true),
                color = colorMode.paper,
                shadowElevation = 3.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1}",
                            contentScale = ContentScale.FillBounds,
                            colorFilter = colorMode.filter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    EditCanvas(
                        pageIndex = pageIndex,
                        aspect = aspect,
                        pageText = pageText,
                        annotations = annotations,
                        selectedId = selectedId,
                        settings = settings,
                        actions = actions,
                        onRequestText = onRequestText,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCanvas(
    pageIndex: Int,
    aspect: Float,
    pageText: PageText?,
    annotations: List<Annotation>,
    selectedId: Long?,
    settings: ToolSettings,
    actions: EditorActions,
    onRequestText: (NPoint) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val images = rememberAnnotationImages(annotations)
    val accent = MaterialTheme.colorScheme.primary
    val handleRadius = with(LocalDensity.current) { 10.dp.toPx() }
    var preview by remember { mutableStateOf<Annotation?>(null) }

    val latestAnnotations by rememberUpdatedState(annotations)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestText by rememberUpdatedState(pageText)
    val latestSettings by rememberUpdatedState(settings)
    val latestAspect by rememberUpdatedState(aspect)
    val latestRequestText by rememberUpdatedState(onRequestText)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(pageIndex, settings.tool) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w <= 0f || h <= 0f) return@awaitEachGesture
                    val asp = latestAspect
                    fun norm(o: Offset) = NPoint((o.x / w).coerceIn(0f, 1f), (o.y / h).coerceIn(0f, 1f))
                    val tol = 22.dp.toPx() / w

                    val s = latestSettings
                    val start = norm(down.position)
                    val handler = GestureHandler(
                        tool = s.tool, settings = s, page = pageIndex, aspect = asp, tolerance = tol,
                        text = latestText, annotations = latestAnnotations, selectedId = latestSelected,
                        actions = actions, setPreview = { preview = it },
                        requestText = { latestRequestText(it) },
                    )
                    handler.down(start)
                    down.consume()

                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            handler.move(norm(change.position))
                            change.consume()
                        }
                    }
                    handler.up(cancelled)
                }
            },
    ) {
        drawAnnotations(annotations, images, measurer)
        preview?.let { drawAnnotation(it, images, measurer) }
        annotations.firstOrNull { it.id == selectedId }?.let { drawSelection(it, aspect, accent, handleRadius) }
    }
}

/** The per-tool logic for one finger-down → move → up sequence. */
private class GestureHandler(
    private val tool: Tool,
    private val settings: ToolSettings,
    private val page: Int,
    private val aspect: Float,
    private val tolerance: Float,
    private val text: PageText?,
    private val annotations: List<Annotation>,
    private val selectedId: Long?,
    private val actions: EditorActions,
    private val setPreview: (Annotation?) -> Unit,
    private val requestText: (NPoint) -> Unit,
) {
    private lateinit var start: NPoint
    private var last: NPoint? = null
    private var moved = false

    // Pen
    private val points = ArrayList<NPoint>()
    private val previewId = -1L

    // Markup
    private var startChar: Int? = null
    private var markup: Markup? = null

    // Select
    private var target: Annotation? = null
    private var resizing = false
    private var dragging = false

    // Eraser
    private val erased = HashSet<Long>()

    fun down(p: NPoint) {
        start = p
        last = p
        when (tool) {
            Tool.PEN -> points += p
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKEOUT -> {
                startChar = text?.takeUnless { it.isEmpty }?.charAt(p.x, p.y)
            }
            Tool.ERASER -> erase(p)
            Tool.SELECT -> {
                val selected = annotations.firstOrNull { it.id == selectedId }
                val handle = selected?.handle(aspect)
                if (selected != null && handle != null && p.isNear(handle, aspect, tolerance * 1.4f)) {
                    target = selected
                    resizing = true
                } else {
                    // Topmost first: the last drawn wins.
                    val hit = annotations.lastOrNull { it.hitTest(p, aspect, tolerance) }
                    actions.select(hit?.id)
                    target = hit?.takeIf { it.movable }
                }
            }
            else -> Unit
        }
    }

    fun move(p: NPoint) {
        if (!moved && distance(p, start, aspect) > tolerance / 3) moved = true
        last = p
        when (tool) {
            Tool.PEN -> {
                val prev = points.last()
                if (distance(prev, p, aspect) > 0.0015f) {
                    points += p
                    setPreview(Ink(previewId, page, points.toList(), settings.penColor, settings.strokeWidth))
                }
            }
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKEOUT -> {
                markup = buildMarkup(p)
                setPreview(markup)
            }
            Tool.RECTANGLE, Tool.OVAL, Tool.LINE, Tool.ARROW -> setPreview(buildShape(previewId, p))
            Tool.ERASER -> erase(p)
            Tool.SELECT -> {
                val t = target ?: return
                if (!moved) return
                dragging = true
                actions.gestureUpdate(
                    if (resizing) t.resizedTo(p, aspect) else t.moved(p.x - start.x, p.y - start.y),
                )
            }
            Tool.TEXT -> Unit
        }
    }

    fun up(cancelled: Boolean) {
        setPreview(null)
        if (cancelled) {
            if (tool == Tool.SELECT || tool == Tool.ERASER) actions.gestureEnd()
            return
        }
        when (tool) {
            Tool.PEN -> {
                if (points.size == 1) points += NPoint(points[0].x + 0.0005f, points[0].y)
                actions.create(Ink(actions.newId(), page, points.toList(), settings.penColor, settings.strokeWidth))
            }
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKEOUT -> {
                val m = if (moved) markup else tapMarkup()
                if (m != null && m.rects.isNotEmpty()) actions.create(m.copy(id = actions.newId()))
            }
            Tool.RECTANGLE, Tool.OVAL, Tool.LINE, Tool.ARROW -> {
                val end = last ?: return
                if (moved) actions.create(buildShape(actions.newId(), end).normalised())
            }
            Tool.TEXT -> if (!moved) requestText(start)
            Tool.ERASER -> actions.gestureEnd()
            Tool.SELECT -> if (dragging) actions.gestureEnd()
        }
    }

    private fun markupKind() = when (tool) {
        Tool.UNDERLINE -> MarkupKind.UNDERLINE
        Tool.STRIKEOUT -> MarkupKind.STRIKEOUT
        else -> MarkupKind.HIGHLIGHT
    }

    private fun markupColor() = if (tool == Tool.HIGHLIGHT) settings.highlightColor else settings.lineMarkColor

    /** Follows the text when the page has some; otherwise marks the dragged rectangle. */
    private fun buildMarkup(p: NPoint): Markup? {
        val t = text
        val from = startChar
        if (t != null && from != null) {
            val to = t.charAt(p.x, p.y) ?: return markup
            val range = min(from, to)..max(from, to)
            return Markup(previewId, page, markupKind(), t.rects(range).toNRects(), markupColor(), t.slice(range))
        }
        val r = NRect.of(start, p)
        if (r.width < 0.005f || r.height < 0.003f) return null
        return Markup(previewId, page, markupKind(), listOf(r), markupColor())
    }

    /** A tap with a markup tool marks the word under the finger. */
    private fun tapMarkup(): Markup? {
        val t = text ?: return null
        val c = startChar ?: return null
        val range = t.wordAt(c)
        val rects = t.rects(range).toNRects()
        if (rects.isEmpty()) return null
        return Markup(previewId, page, markupKind(), rects, markupColor(), t.slice(range))
    }

    private fun buildShape(id: Long, end: NPoint): Shape {
        val kind = when (tool) {
            Tool.OVAL -> ShapeKind.OVAL
            Tool.LINE -> ShapeKind.LINE
            Tool.ARROW -> ShapeKind.ARROW
            else -> ShapeKind.RECTANGLE
        }
        return Shape(id, page, kind, start, end, settings.shapeColor, settings.strokeWidth)
    }

    private fun erase(p: NPoint) {
        annotations.lastOrNull { it.id !in erased && it.hitTest(p, aspect, tolerance) }?.let {
            erased += it.id
            actions.gestureRemove(it.id)
        }
    }
}

/** Zoom and pan with two fingers only, so one finger is always free for the tool. */
private fun Modifier.twoFingerZoom(zoom: PageZoom): Modifier = this
    .onSizeChanged { zoom.size = it }
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom.transform(event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
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

/** Phase 2's text boxes use Compose's Rect; annotations use our own NRect. */
private fun List<androidx.compose.ui.geometry.Rect>.toNRects(): List<NRect> =
    map { NRect(it.left, it.top, it.right, it.bottom) }
