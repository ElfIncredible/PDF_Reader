package com.folio.pdf.ui.editor

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import com.folio.pdf.edit.Annotation
import com.folio.pdf.edit.ImageStamp
import com.folio.pdf.edit.Ink
import com.folio.pdf.edit.Markup
import com.folio.pdf.edit.MarkupKind
import com.folio.pdf.edit.NPoint
import com.folio.pdf.edit.NRect
import com.folio.pdf.edit.Shape
import com.folio.pdf.edit.ShapeKind
import com.folio.pdf.edit.TextNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Decoded images for picture stamps, shared by reader and editor. */
object AnnotationImages {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun peek(path: String): ImageBitmap? = cache.get(path)

    fun load(path: String): ImageBitmap? =
        cache.get(path) ?: runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            .getOrNull()?.also { cache.put(path, it) }
}

/** Loads the images needed by [annotations] off the main thread. */
@Composable
fun rememberAnnotationImages(annotations: List<Annotation>): Map<String, ImageBitmap> {
    val files = remember(annotations) {
        annotations.mapNotNull { (it as? ImageStamp)?.file }.toSortedSet().toList()
    }
    val images by produceState(
        initialValue = files.mapNotNull { f -> AnnotationImages.peek(f)?.let { f to it } }.toMap(),
        key1 = files,
    ) {
        value = withContext(Dispatchers.IO) {
            files.mapNotNull { f -> AnnotationImages.load(f)?.let { f to it } }.toMap()
        }
    }
    return images
}

private fun DrawScope.px(p: NPoint) = Offset(p.x * size.width, p.y * size.height)

private fun DrawScope.px(r: NRect): Pair<Offset, Size> =
    Offset(r.left * size.width, r.top * size.height) to Size(r.width * size.width, r.height * size.height)

fun DrawScope.drawAnnotations(
    list: List<Annotation>,
    images: Map<String, ImageBitmap>,
    measurer: TextMeasurer,
) {
    list.forEach { drawAnnotation(it, images, measurer) }
}

fun DrawScope.drawAnnotation(
    a: Annotation,
    images: Map<String, ImageBitmap>,
    measurer: TextMeasurer,
) {
    val color = Color(a.color)
    when (a) {
        is Markup -> a.rects.forEach { r ->
            val (topLeft, sz) = px(r)
            when (a.kind) {
                MarkupKind.HIGHLIGHT -> drawRect(color.copy(alpha = 0.42f), topLeft, sz, blendMode = BlendMode.Multiply)
                MarkupKind.UNDERLINE -> {
                    val y = topLeft.y + sz.height * 0.88f
                    drawLine(color, Offset(topLeft.x, y), Offset(topLeft.x + sz.width, y), max(1.5f, sz.height * 0.08f))
                }
                MarkupKind.STRIKEOUT -> {
                    val y = topLeft.y + sz.height * 0.55f
                    drawLine(color, Offset(topLeft.x, y), Offset(topLeft.x + sz.width, y), max(1.5f, sz.height * 0.08f))
                }
            }
        }

        is Ink -> {
            val strokePx = max(1f, a.width * size.width)
            if (a.points.size == 1) {
                drawCircle(color.copy(alpha = a.opacity), strokePx / 2, px(a.points[0]))
            } else {
                val path = Path().apply {
                    val first = px(a.points[0])
                    moveTo(first.x, first.y)
                    for (i in 1 until a.points.size) {
                        val p = px(a.points[i])
                        lineTo(p.x, p.y)
                    }
                }
                drawPath(
                    path,
                    color.copy(alpha = a.opacity),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        is Shape -> {
            val strokePx = max(1f, a.width * size.width)
            val s = px(a.start)
            val e = px(a.end)
            val topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
            val sz = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (a.kind) {
                ShapeKind.RECTANGLE -> drawRect(color, topLeft, sz, style = stroke)
                ShapeKind.OVAL -> drawOval(color, topLeft, sz, style = stroke)
                ShapeKind.LINE -> drawLine(color, s, e, strokePx, cap = StrokeCap.Round)
                ShapeKind.ARROW -> {
                    drawLine(color, s, e, strokePx, cap = StrokeCap.Round)
                    val angle = atan2(e.y - s.y, e.x - s.x)
                    val len = max(strokePx * 4f, size.width * 0.025f)
                    for (sign in listOf(-1f, 1f)) {
                        val t = angle + Math.PI.toFloat() + sign * 0.45f
                        drawLine(color, e, Offset(e.x + len * cos(t), e.y + len * sin(t)), strokePx, cap = StrokeCap.Round)
                    }
                }
            }
        }

        is TextNote -> {
            val fontPx = a.fontSize * size.width
            if (fontPx < 1f) return
            val style = TextStyle(
                color = color,
                fontSize = fontPx.toSp(),
                lineHeight = TextNote.LINE_HEIGHT.em,
                fontFamily = FontFamily.SansSerif,
            )
            drawText(
                textMeasurer = measurer,
                text = a.text,
                topLeft = Offset(a.origin.x * size.width + fontPx * 0.2f, a.origin.y * size.height),
                style = style,
                softWrap = false,
            )
        }

        is ImageStamp -> {
            val image = images[a.file]
            val (topLeft, sz) = px(a.rect)
            if (image != null && sz.width >= 1f && sz.height >= 1f) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                    dstSize = IntSize(sz.width.toInt(), sz.height.toInt()),
                )
            } else {
                drawRect(Color.Gray.copy(alpha = 0.3f), topLeft, sz)
            }
        }
    }
}

/** Dashed box plus a round resize handle around the selected annotation. */
fun DrawScope.drawSelection(a: Annotation, aspect: Float, accent: Color, handleRadiusPx: Float) {
    val b = a.bounds(aspect)
    val (topLeft, sz) = px(b)
    val pad = handleRadiusPx * 0.6f
    drawRect(
        color = accent,
        topLeft = Offset(topLeft.x - pad, topLeft.y - pad),
        size = Size(sz.width + pad * 2, sz.height + pad * 2),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
    )
    a.handle(aspect)?.let { h ->
        val c = px(h)
        drawCircle(Color.White, handleRadiusPx, c)
        drawCircle(accent, handleRadiusPx, c, style = Stroke(width = 3f))
    }
}
