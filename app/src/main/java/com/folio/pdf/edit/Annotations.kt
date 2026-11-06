package com.folio.pdf.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * All geometry is normalised to the displayed page: x and y run 0..1 from the top-left
 * corner, exactly like Phase 2's character boxes. Lengths (stroke widths, font sizes)
 * are fractions of the page WIDTH. `aspect` is page height / width.
 */

data class NPoint(val x: Float, val y: Float)

data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(p: NPoint, padX: Float = 0f, padY: Float = 0f): Boolean =
        p.x in (left - padX)..(right + padX) && p.y in (top - padY)..(bottom + padY)

    fun offset(dx: Float, dy: Float) = NRect(left + dx, top + dy, right + dx, bottom + dy)

    fun union(o: NRect) = NRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun inflate(dx: Float, dy: Float) = NRect(left - dx, top - dy, right + dx, bottom + dy)

    companion object {
        fun of(a: NPoint, b: NPoint) = NRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

        fun bounding(points: List<NPoint>): NRect {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in points) {
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            return if (points.isEmpty()) NRect(0f, 0f, 0f, 0f) else NRect(l, t, r, b)
        }
    }
}

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }

enum class ShapeKind { RECTANGLE, OVAL, LINE, ARROW }

sealed interface Annotation {
    val id: Long
    val page: Int
    /** ARGB colour. */
    val color: Int

    fun bounds(aspect: Float): NRect
    fun withColor(color: Int): Annotation
    fun moved(dx: Float, dy: Float): Annotation

    /** Whether it can be dragged. Text markups stay attached to their text. */
    val movable: Boolean get() = true

    /** Where the resize handle sits, or null if it can't be resized. */
    fun handle(aspect: Float): NPoint? = null

    /** Drag the resize handle to [p]. */
    fun resizedTo(p: NPoint, aspect: Float): Annotation = this
}

/** Highlight, underline or strike-through over text. */
data class Markup(
    override val id: Long,
    override val page: Int,
    val kind: MarkupKind,
    val rects: List<NRect>,
    override val color: Int,
    val text: String = "",
) : Annotation {
    override fun bounds(aspect: Float) = rects.reduceOrNull { a, b -> a.union(b) } ?: NRect(0f, 0f, 0f, 0f)
    override fun withColor(color: Int) = copy(color = color)
    override fun moved(dx: Float, dy: Float) = this
    override val movable: Boolean get() = false
}

/** One freehand pen stroke. [width] is a fraction of page width. */
data class Ink(
    override val id: Long,
    override val page: Int,
    val points: List<NPoint>,
    override val color: Int,
    val width: Float,
    val opacity: Float = 1f,
) : Annotation {
    private val raw: NRect get() = NRect.bounding(points)

    override fun bounds(aspect: Float) = raw.inflate(width / 2, width / 2 / aspect)
    override fun withColor(color: Int) = copy(color = color)
    override fun moved(dx: Float, dy: Float) = copy(points = points.map { NPoint(it.x + dx, it.y + dy) })
    override fun handle(aspect: Float) = raw.let { NPoint(it.right, it.bottom) }

    override fun resizedTo(p: NPoint, aspect: Float): Ink {
        val b = raw
        val sx = if (b.width < 1e-4f) 1f else ((p.x - b.left) / b.width).coerceAtLeast(0.05f)
        val sy = if (b.height < 1e-4f) 1f else ((p.y - b.top) / b.height).coerceAtLeast(0.05f)
        return copy(points = points.map { NPoint(b.left + (it.x - b.left) * sx, b.top + (it.y - b.top) * sy) })
    }
}

data class Shape(
    override val id: Long,
    override val page: Int,
    val kind: ShapeKind,
    val start: NPoint,
    val end: NPoint,
    override val color: Int,
    val width: Float,
) : Annotation {
    override fun bounds(aspect: Float) = NRect.of(start, end).inflate(width, width / aspect)
    override fun withColor(color: Int) = copy(color = color)
    override fun moved(dx: Float, dy: Float) =
        copy(start = NPoint(start.x + dx, start.y + dy), end = NPoint(end.x + dx, end.y + dy))
    override fun handle(aspect: Float) = end
    override fun resizedTo(p: NPoint, aspect: Float) = copy(end = p)

    /** Rectangles and ovals are stored top-left → bottom-right. */
    fun normalised(): Shape = if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) this else {
        val r = NRect.of(start, end)
        copy(start = NPoint(r.left, r.top), end = NPoint(r.right, r.bottom))
    }
}

/** A text box. [origin] is its top-left corner; [fontSize] is a fraction of page width. */
data class TextNote(
    override val id: Long,
    override val page: Int,
    val origin: NPoint,
    val text: String,
    override val color: Int,
    val fontSize: Float,
) : Annotation {
    val lines: List<String> get() = text.split('\n')

    /** Width/height estimated from Helvetica-like metrics (0.55 em per char). */
    fun size(aspect: Float): Pair<Float, Float> {
        val longest = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val w = longest * fontSize * 0.55f + fontSize * 0.4f
        val h = lines.size * fontSize * LINE_HEIGHT / aspect + fontSize * 0.3f / aspect
        return w to h
    }

    override fun bounds(aspect: Float): NRect {
        val (w, h) = size(aspect)
        return NRect(origin.x, origin.y, origin.x + w, origin.y + h)
    }

    override fun withColor(color: Int) = copy(color = color)
    override fun moved(dx: Float, dy: Float) = copy(origin = NPoint(origin.x + dx, origin.y + dy))
    override fun handle(aspect: Float) = bounds(aspect).let { NPoint(it.right, it.bottom) }

    override fun resizedTo(p: NPoint, aspect: Float): TextNote {
        val longest = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val targetWidth = (p.x - origin.x).coerceAtLeast(0.02f)
        return copy(fontSize = (targetWidth / (longest * 0.55f + 0.4f)).coerceIn(0.008f, 0.25f))
    }

    companion object {
        const val LINE_HEIGHT = 1.25f
    }
}

/** A picture placed on the page. [imageAspect] is the image's own height / width in pixels. */
data class ImageStamp(
    override val id: Long,
    override val page: Int,
    val rect: NRect,
    val file: String,
    val imageAspect: Float,
) : Annotation {
    override val color: Int get() = 0xFF000000.toInt()
    override fun bounds(aspect: Float) = rect
    override fun withColor(color: Int) = this
    override fun moved(dx: Float, dy: Float) = copy(rect = rect.offset(dx, dy))
    override fun handle(aspect: Float) = NPoint(rect.right, rect.bottom)

    override fun resizedTo(p: NPoint, aspect: Float): ImageStamp {
        val w = (p.x - rect.left).coerceAtLeast(0.04f)
        val h = w * imageAspect / aspect
        return copy(rect = NRect(rect.left, rect.top, rect.left + w, rect.top + h))
    }
}

// ---- Hit testing ----------------------------------------------------------------

/** Is [p] on this annotation? [tolerance] is in page-width units. */
fun Annotation.hitTest(p: NPoint, aspect: Float, tolerance: Float): Boolean = when (this) {
    is Ink -> distanceToPolyline(points, p, aspect) <= width / 2 + tolerance
    is Shape -> when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> distanceToSegment(start, end, p, aspect) <= width / 2 + tolerance
        else -> bounds(aspect).contains(p, tolerance, tolerance / aspect)
    }
    else -> bounds(aspect).contains(p, tolerance, tolerance / aspect)
}

private fun distanceToPolyline(points: List<NPoint>, p: NPoint, aspect: Float): Float {
    if (points.isEmpty()) return Float.MAX_VALUE
    if (points.size == 1) return distance(points[0], p, aspect)
    var best = Float.MAX_VALUE
    for (i in 1 until points.size) best = min(best, distanceToSegment(points[i - 1], points[i], p, aspect))
    return best
}

/** Distance in page-width units (y is scaled by aspect so distances are isotropic). */
private fun distanceToSegment(a: NPoint, b: NPoint, p: NPoint, aspect: Float): Float {
    val ax = a.x; val ay = a.y * aspect
    val bx = b.x; val by = b.y * aspect
    val px = p.x; val py = p.y * aspect
    val dx = bx - ax; val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    val cx = ax + t * dx; val cy = ay + t * dy
    return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
}

fun distance(a: NPoint, b: NPoint, aspect: Float): Float {
    val dx = a.x - b.x
    val dy = (a.y - b.y) * aspect
    return sqrt(dx * dx + dy * dy)
}

fun NPoint.isNear(o: NPoint, aspect: Float, tolerance: Float) =
    abs(x - o.x) <= tolerance && abs(y - o.y) * aspect <= tolerance

// ---- Tools & colours ------------------------------------------------------------

enum class Tool(val label: String) {
    SELECT("Select"),
    HIGHLIGHT("Highlight"),
    UNDERLINE("Underline"),
    STRIKEOUT("Strike"),
    PEN("Pen"),
    ERASER("Eraser"),
    RECTANGLE("Box"),
    OVAL("Circle"),
    LINE("Line"),
    ARROW("Arrow"),
    TEXT("Text"),
}

object Palette {
    val YELLOW = 0xFFFFEB3B.toInt()
    val GREEN = 0xFF8BC34A.toInt()
    val BLUE = 0xFF1E88E5.toInt()
    val RED = 0xFFE53935.toInt()
    val PURPLE = 0xFF8E24AA.toInt()
    val ORANGE = 0xFFFB8C00.toInt()
    val BLACK = 0xFF212121.toInt()
    val WHITE = 0xFFFFFFFF.toInt()

    val all = listOf(YELLOW, GREEN, BLUE, RED, PURPLE, ORANGE, BLACK, WHITE)
}

data class ToolSettings(
    val tool: Tool = Tool.HIGHLIGHT,
    val highlightColor: Int = Palette.YELLOW,
    val lineMarkColor: Int = Palette.RED,
    val penColor: Int = Palette.BLUE,
    val shapeColor: Int = Palette.RED,
    val textColor: Int = Palette.BLACK,
    /** Fraction of page width. */
    val strokeWidth: Float = 0.005f,
    /** Fraction of page width. */
    val textSize: Float = 0.03f,
) {
    val color: Int
        get() = when (tool) {
            Tool.HIGHLIGHT -> highlightColor
            Tool.UNDERLINE, Tool.STRIKEOUT -> lineMarkColor
            Tool.PEN -> penColor
            Tool.RECTANGLE, Tool.OVAL, Tool.LINE, Tool.ARROW -> shapeColor
            Tool.TEXT -> textColor
            Tool.SELECT, Tool.ERASER -> penColor
        }

    fun withColor(c: Int): ToolSettings = when (tool) {
        Tool.HIGHLIGHT -> copy(highlightColor = c)
        Tool.UNDERLINE, Tool.STRIKEOUT -> copy(lineMarkColor = c)
        Tool.PEN -> copy(penColor = c)
        Tool.RECTANGLE, Tool.OVAL, Tool.LINE, Tool.ARROW -> copy(shapeColor = c)
        Tool.TEXT -> copy(textColor = c)
        Tool.SELECT, Tool.ERASER -> this
    }

    val hasColor: Boolean get() = tool != Tool.SELECT && tool != Tool.ERASER
    val hasStroke: Boolean get() = tool == Tool.PEN || tool == Tool.RECTANGLE || tool == Tool.OVAL ||
        tool == Tool.LINE || tool == Tool.ARROW
}
