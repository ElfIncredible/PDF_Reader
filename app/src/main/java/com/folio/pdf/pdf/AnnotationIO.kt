package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Maps between Folio's normalised display coordinates and PDF user space, honouring
 * the page's crop box and /Rotate. Drawing happens in "display points": x right,
 * y up, origin at the displayed page's bottom-left. [matrix] maps those to user space,
 * so text and images drawn there appear upright however the page is rotated.
 */
internal class PageGeometry(page: PDPage) {
    private val box: PDRectangle = page.cropBox
    private val llx = box.lowerLeftX
    private val lly = box.lowerLeftY
    private val w = box.width
    private val h = box.height
    private val rotation = ((page.rotation % 360) + 360) % 360

    val displayWidth: Float = if (rotation == 90 || rotation == 270) h else w
    val displayHeight: Float = if (rotation == 90 || rotation == 270) w else h

    /** x = a·px + c·py + e ; y = b·px + d·py + f */
    private val m: FloatArray = when (rotation) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + w, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + w, lly + h)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + h)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }

    fun matrix() = Matrix(m[0], m[1], m[2], m[3], m[4], m[5])

    fun dx(n: NPoint) = n.x * displayWidth
    fun dy(n: NPoint) = (1f - n.y) * displayHeight

    /** Normalised display point → user space. */
    fun toUser(n: NPoint): FloatArray {
        val px = dx(n)
        val py = dy(n)
        return floatArrayOf(m[0] * px + m[2] * py + m[4], m[1] * px + m[3] * py + m[5])
    }

    /** User space → normalised display point. */
    fun toNormalised(x: Float, y: Float): NPoint {
        val u = (x - llx) / w
        val v = (y - lly) / h
        return when (rotation) {
            90 -> NPoint(v, u)
            180 -> NPoint(1f - u, v)
            270 -> NPoint(1f - v, 1f - u)
            else -> NPoint(u, 1f - v)
        }
    }

    /** Bounding box in user space of a normalised rect, grown by [pad] points. */
    fun userBox(r: NRect, pad: Float): PDRectangle {
        val corners = listOf(
            toUser(NPoint(r.left, r.top)), toUser(NPoint(r.right, r.top)),
            toUser(NPoint(r.left, r.bottom)), toUser(NPoint(r.right, r.bottom)),
        )
        val minX = corners.minOf { it[0] } - pad
        val minY = corners.minOf { it[1] } - pad
        val maxX = corners.maxOf { it[0] } + pad
        val maxY = corners.maxOf { it[1] } + pad
        return PDRectangle(minX, minY, maxX - minX, maxY - minY)
    }

    /** Normalised bounding box of a user-space rect array [x1 y1 x2 y2]. */
    fun normalisedRect(r: FloatArray): NRect = NRect.bounding(
        listOf(
            toNormalised(r[0], r[1]), toNormalised(r[2], r[3]),
            toNormalised(r[0], r[3]), toNormalised(r[2], r[1]),
        ),
    )
}

private fun name(s: String): COSName = COSName.getPDFName(s)

/** Annotation types Folio reads, shows, edits and rewrites. Everything else is left untouched. */
private val MANAGED = setOf("Highlight", "Underline", "StrikeOut", "Ink", "Square", "Circle", "Line", "FreeText")
private const val ID_PREFIX = "folio-"

private fun isManaged(dict: COSDictionary): Boolean {
    val subtype = dict.getNameAsString(name("Subtype")) ?: return false
    if (subtype in MANAGED) return true
    return subtype == "Stamp" && dict.getString(name("NM"))?.startsWith(ID_PREFIX) == true
}

// ---- Reading ---------------------------------------------------------------------

object AnnotationReader {

    /**
     * Reads Folio-editable annotations from every page. Images from Folio stamps are
     * written into [imageDir] so they can be shown and re-saved.
     */
    fun read(doc: PDDocument, imageDir: File, newId: () -> Long): List<Annotation> {
        val out = mutableListOf<Annotation>()
        for (pageIndex in 0 until doc.numberOfPages) {
            val page = runCatching { doc.getPage(pageIndex) }.getOrNull() ?: continue
            val geometry = runCatching { PageGeometry(page) }.getOrNull() ?: continue
            val annotations = runCatching { page.annotations }.getOrNull() ?: continue
            for (a in annotations) {
                runCatching { parse(a, pageIndex, geometry, imageDir, newId) }.getOrNull()?.let(out::addAll)
            }
        }
        return out
    }

    private fun parse(
        a: PDAnnotation,
        page: Int,
        g: PageGeometry,
        imageDir: File,
        newId: () -> Long,
    ): List<Annotation> {
        val d = a.cosObject
        if (!isManaged(d)) return emptyList()
        val subtype = d.getNameAsString(name("Subtype"))
        val color = colorOf(d.getDictionaryObject(name("C"))) ?: 0xFFFFEB3B.toInt()
        val opacity = d.getFloat(name("CA"), 1f)
        val borderPt = borderWidth(d)
        val rect = floats(d, "Rect")

        return when (subtype) {
            "Highlight", "Underline", "StrikeOut" -> {
                val q = floats(d, "QuadPoints") ?: return emptyList()
                val rects = (0 until q.size / 8).map { i ->
                    val o = i * 8
                    NRect.bounding((0 until 4).map { k -> g.toNormalised(q[o + k * 2], q[o + k * 2 + 1]) })
                }
                val kind = when (subtype) {
                    "Highlight" -> MarkupKind.HIGHLIGHT
                    "Underline" -> MarkupKind.UNDERLINE
                    else -> MarkupKind.STRIKEOUT
                }
                listOf(Markup(newId(), page, kind, rects, color, d.getString(name("Contents")).orEmpty()))
            }

            "Ink" -> {
                val list = d.getDictionaryObject(name("InkList")) as? COSArray ?: return emptyList()
                (0 until list.size()).mapNotNull { i ->
                    val stroke = (list.getObject(i) as? COSArray)?.toFloatArray() ?: return@mapNotNull null
                    val points = (0 until stroke.size / 2).map { k -> g.toNormalised(stroke[k * 2], stroke[k * 2 + 1]) }
                    if (points.isEmpty()) null
                    else Ink(newId(), page, points, color, borderPt / g.displayWidth, opacity)
                }
            }

            "Square", "Circle" -> {
                val r = rect?.let(g::normalisedRect) ?: return emptyList()
                // /Rect includes the border; move inwards by half of it.
                val inX = borderPt / 2 / g.displayWidth
                val inY = borderPt / 2 / g.displayHeight
                val kind = if (subtype == "Square") ShapeKind.RECTANGLE else ShapeKind.OVAL
                listOf(
                    Shape(
                        newId(), page, kind,
                        NPoint(r.left + inX, r.top + inY), NPoint(r.right - inX, r.bottom - inY),
                        color, borderPt / g.displayWidth,
                    ),
                )
            }

            "Line" -> {
                val l = floats(d, "L") ?: return emptyList()
                val endings = (d.getDictionaryObject(name("LE")) as? COSArray)
                    ?.let { arr -> (0 until arr.size()).mapNotNull { (arr.getObject(it) as? COSName)?.name } }
                    .orEmpty()
                val arrow = endings.any { it.contains("Arrow") }
                listOf(
                    Shape(
                        newId(), page, if (arrow) ShapeKind.ARROW else ShapeKind.LINE,
                        g.toNormalised(l[0], l[1]), g.toNormalised(l[2], l[3]),
                        color, borderPt / g.displayWidth,
                    ),
                )
            }

            "FreeText" -> {
                val r = rect?.let(g::normalisedRect) ?: return emptyList()
                val da = d.getString(name("DA")).orEmpty()
                val sizePt = Regex("""([0-9]*\.?[0-9]+)\s+Tf""").find(da)?.groupValues?.get(1)?.toFloatOrNull() ?: 12f
                val textColor = Regex("""([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+rg""").find(da)?.let { m ->
                    argb(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
                } ?: 0xFF212121.toInt()
                val text = d.getString(name("Contents")).orEmpty()
                if (text.isBlank()) emptyList()
                else listOf(TextNote(newId(), page, NPoint(r.left, r.top), text, textColor, sizePt / g.displayWidth))
            }

            "Stamp" -> {
                val r = rect?.let(g::normalisedRect) ?: return emptyList()
                val bitmap = imageFromAppearance(a) ?: return emptyList()
                imageDir.mkdirs()
                val id = newId()
                val file = File(imageDir, "imported-$id.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                listOf(ImageStamp(id, page, r, file.absolutePath, bitmap.height.toFloat() / bitmap.width))
            }

            else -> emptyList()
        }
    }

    private fun imageFromAppearance(a: PDAnnotation): Bitmap? {
        val resources = a.normalAppearanceStream?.resources ?: return null
        for (n in resources.xObjectNames) {
            val x = runCatching { resources.getXObject(n) }.getOrNull()
            if (x is PDImageXObject) return runCatching { x.image }.getOrNull()
        }
        return null
    }

    private fun floats(d: COSDictionary, key: String): FloatArray? =
        (d.getDictionaryObject(name(key)) as? COSArray)?.toFloatArray()

    private fun borderWidth(d: COSDictionary): Float {
        (d.getDictionaryObject(name("BS")) as? COSDictionary)?.let { return it.getFloat(name("W"), 1f) }
        (d.getDictionaryObject(name("Border")) as? COSArray)?.let { arr ->
            if (arr.size() >= 3) (arr.getObject(2) as? COSNumber)?.let { return it.floatValue() }
        }
        return 1f
    }

    private fun colorOf(base: COSBase?): Int? {
        val c = (base as? COSArray)?.toFloatArray() ?: return null
        return when (c.size) {
            1 -> argb(c[0], c[0], c[0])
            3 -> argb(c[0], c[1], c[2])
            4 -> argb((1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3]))
            else -> null
        }
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

// ---- Writing ---------------------------------------------------------------------

object AnnotationWriter {

    /**
     * Writes [annotations] into a copy of [source], saved to [target]. Folio-editable
     * annotations already in the file are replaced (they were read into the editor
     * when the document opened); links, form fields and other kinds are kept as-is.
     */
    fun write(context: Context, source: Uri, annotations: List<Annotation>, target: File) {
        val doc = context.contentResolver.openInputStream(source)?.use {
            PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
        } ?: throw FileNotFoundException("Cannot read $source")

        doc.use {
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            val font = lazy { loadFont(doc) }
            val byPage = annotations.groupBy { it.page }

            for (pageIndex in 0 until doc.numberOfPages) {
                val page = doc.getPage(pageIndex)
                val existing = page.annotations
                val kept = existing.filterNot { isManaged(it.cosObject) || isManagedPopup(it.cosObject) }
                val geometry = PageGeometry(page)
                val added = byPage[pageIndex].orEmpty().mapNotNull { a ->
                    runCatching { build(doc, geometry, a, font) }.getOrNull()
                }
                if (kept.size != existing.size || added.isNotEmpty()) {
                    page.annotations = kept + added
                }
            }
            doc.save(target)
        }
    }

    /** Pop-up notes belonging to annotations we replace would otherwise be left dangling. */
    private fun isManagedPopup(d: COSDictionary): Boolean {
        if (d.getNameAsString(name("Subtype")) != "Popup") return false
        val parent = d.getDictionaryObject(name("Parent")) as? COSDictionary ?: return false
        return isManaged(parent)
    }

    private fun build(doc: PDDocument, g: PageGeometry, a: Annotation, font: Lazy<PDFont>): PDAnnotation {
        val d = COSDictionary()
        d.setItem(name("Type"), name("Annot"))
        d.setString(name("NM"), ID_PREFIX + a.id)
        d.setString(name("M"), "D:" + SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
        d.setString(name("T"), "Folio")
        d.setInt(name("F"), 4) // print
        d.setItem(name("C"), rgbArray(a.color))

        val appearance = PDAppearanceStream(doc)
        appearance.resources = PDResources()
        val aspect = g.displayHeight / g.displayWidth

        val box: PDRectangle = when (a) {
            is Markup -> {
                d.setItem(
                    name("Subtype"),
                    name(
                        when (a.kind) {
                            MarkupKind.HIGHLIGHT -> "Highlight"
                            MarkupKind.UNDERLINE -> "Underline"
                            MarkupKind.STRIKEOUT -> "StrikeOut"
                        },
                    ),
                )
                // QuadPoints order as Acrobat writes it: top-left, top-right, bottom-left, bottom-right.
                val quads = COSArray()
                for (r in a.rects) {
                    for (p in listOf(NPoint(r.left, r.top), NPoint(r.right, r.top), NPoint(r.left, r.bottom), NPoint(r.right, r.bottom))) {
                        val u = g.toUser(p)
                        quads.add(COSFloat(u[0])); quads.add(COSFloat(u[1]))
                    }
                }
                d.setItem(name("QuadPoints"), quads)
                if (a.text.isNotBlank()) d.setString(name("Contents"), a.text)
                val box = g.userBox(a.bounds(aspect), 2f)
                appearance.setBBox(box)
                draw(doc, appearance, g) { cs ->
                    setColor(cs, a.color)
                    for (r in a.rects) {
                        val l = r.left * g.displayWidth
                        val rr = r.right * g.displayWidth
                        val top = (1 - r.top) * g.displayHeight
                        val bottom = (1 - r.bottom) * g.displayHeight
                        val height = top - bottom
                        when (a.kind) {
                            MarkupKind.HIGHLIGHT -> {
                                graphicsState(cs, alpha = 1f, multiply = true)
                                cs.addRect(l, bottom, rr - l, height)
                                cs.fill()
                            }
                            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                                val thickness = max(0.6f, height * 0.07f)
                                val y = if (a.kind == MarkupKind.UNDERLINE) bottom + height * 0.12f else bottom + height * 0.45f
                                cs.setLineWidth(thickness)
                                cs.moveTo(l, y); cs.lineTo(rr, y); cs.stroke()
                            }
                        }
                    }
                }
                box
            }

            is Ink -> {
                d.setItem(name("Subtype"), name("Ink"))
                val widthPt = a.width * g.displayWidth
                val stroke = COSArray()
                a.points.forEach { p -> val u = g.toUser(p); stroke.add(COSFloat(u[0])); stroke.add(COSFloat(u[1])) }
                d.setItem(name("InkList"), COSArray().apply { add(stroke) })
                d.setItem(name("BS"), borderStyle(widthPt))
                d.setFloat(name("CA"), a.opacity)
                val box = g.userBox(NRect.bounding(a.points), widthPt)
                appearance.setBBox(box)
                draw(doc, appearance, g) { cs ->
                    if (a.opacity < 1f) graphicsState(cs, a.opacity, multiply = false)
                    setStroke(cs, a.color, widthPt)
                    val first = a.points.first()
                    cs.moveTo(g.dx(first), g.dy(first))
                    if (a.points.size == 1) cs.lineTo(g.dx(first) + 0.01f, g.dy(first))
                    a.points.drop(1).forEach { cs.lineTo(g.dx(it), g.dy(it)) }
                    cs.stroke()
                }
                box
            }

            is Shape -> {
                val widthPt = a.width * g.displayWidth
                d.setItem(name("BS"), borderStyle(widthPt))
                val x1 = g.dx(a.start); val y1 = g.dy(a.start)
                val x2 = g.dx(a.end); val y2 = g.dy(a.end)
                val box: PDRectangle
                when (a.kind) {
                    ShapeKind.RECTANGLE, ShapeKind.OVAL -> {
                        d.setItem(name("Subtype"), name(if (a.kind == ShapeKind.RECTANGLE) "Square" else "Circle"))
                        box = g.userBox(NRect.of(a.start, a.end), widthPt / 2)
                    }
                    ShapeKind.LINE, ShapeKind.ARROW -> {
                        d.setItem(name("Subtype"), name("Line"))
                        val s = g.toUser(a.start)
                        val e = g.toUser(a.end)
                        d.setItem(name("L"), COSArray().apply { listOf(s[0], s[1], e[0], e[1]).forEach { add(COSFloat(it)) } })
                        if (a.kind == ShapeKind.ARROW) {
                            d.setItem(name("LE"), COSArray().apply { add(name("None")); add(name("OpenArrow")) })
                        }
                        box = g.userBox(NRect.of(a.start, a.end), widthPt * 6 + 4)
                    }
                }
                appearance.setBBox(box)
                draw(doc, appearance, g) { cs ->
                    setStroke(cs, a.color, widthPt)
                    when (a.kind) {
                        ShapeKind.RECTANGLE -> {
                            cs.addRect(min(x1, x2), min(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
                            cs.stroke()
                        }
                        ShapeKind.OVAL -> {
                            ellipse(cs, (x1 + x2) / 2, (y1 + y2) / 2, kotlin.math.abs(x2 - x1) / 2, kotlin.math.abs(y2 - y1) / 2)
                            cs.stroke()
                        }
                        ShapeKind.LINE, ShapeKind.ARROW -> {
                            cs.moveTo(x1, y1); cs.lineTo(x2, y2); cs.stroke()
                            if (a.kind == ShapeKind.ARROW) {
                                val angle = atan2(y2 - y1, x2 - x1)
                                val len = max(widthPt * 4f, 8f)
                                for (sign in listOf(-1f, 1f)) {
                                    val t = angle + Math.PI.toFloat() + sign * 0.45f
                                    cs.moveTo(x2, y2)
                                    cs.lineTo(x2 + len * cos(t), y2 + len * sin(t))
                                }
                                cs.stroke()
                            }
                        }
                    }
                }
                box
            }

            is TextNote -> {
                d.setItem(name("Subtype"), name("FreeText"))
                d.setString(name("Contents"), a.text)
                val sizePt = a.fontSize * g.displayWidth
                val (r, gr, b) = rgb(a.color)
                d.setString(name("DA"), String.format(Locale.US, "/Helv %.2f Tf %.3f %.3f %.3f rg", sizePt, r, gr, b))
                d.setItem(name("BS"), borderStyle(0f))
                val box = g.userBox(a.bounds(aspect), 1f)
                appearance.setBBox(box)
                val f = font.value
                draw(doc, appearance, g) { cs ->
                    cs.setNonStrokingColor(r, gr, b)
                    val left = g.dx(a.origin) + sizePt * 0.2f
                    val top = g.dy(a.origin)
                    a.lines.forEachIndexed { i, line ->
                        val safe = encodable(f, line)
                        if (safe.isNotEmpty()) {
                            cs.beginText()
                            cs.setFont(f, sizePt)
                            cs.newLineAtOffset(left, top - sizePt * (0.95f + i * TextNote.LINE_HEIGHT))
                            cs.showText(safe)
                            cs.endText()
                        }
                    }
                }
                box
            }

            is ImageStamp -> {
                d.setItem(name("Subtype"), name("Stamp"))
                val bitmap = BitmapFactory.decodeFile(a.file) ?: throw FileNotFoundException(a.file)
                val image = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap)
                else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
                val box = g.userBox(a.rect, 0f)
                appearance.setBBox(box)
                draw(doc, appearance, g) { cs ->
                    val x = a.rect.left * g.displayWidth
                    val y = (1 - a.rect.bottom) * g.displayHeight
                    cs.drawImage(image, x, y, a.rect.width * g.displayWidth, a.rect.height * g.displayHeight)
                }
                box
            }
        }

        d.setItem(name("Rect"), box.cosArray)
        d.setItem(name("AP"), COSDictionary().apply { setItem(name("N"), appearance.cosObject) })
        return PDAnnotation.createAnnotation(d)
    }

    /** Draw in display points; the page geometry matrix maps them to user space. */
    private inline fun draw(doc: PDDocument, ap: PDAppearanceStream, g: PageGeometry, block: (PDPageContentStream) -> Unit) {
        PDPageContentStream(doc, ap).use { cs ->
            cs.transform(g.matrix())
            block(cs)
        }
    }

    private fun graphicsState(cs: PDPageContentStream, alpha: Float, multiply: Boolean) {
        val gs = PDExtendedGraphicsState()
        gs.cosObject.setFloat(name("CA"), alpha)
        gs.cosObject.setFloat(name("ca"), alpha)
        if (multiply) gs.cosObject.setItem(name("BM"), name("Multiply"))
        cs.setGraphicsStateParameters(gs)
    }

    private fun setColor(cs: PDPageContentStream, color: Int) {
        val (r, g, b) = rgb(color)
        cs.setNonStrokingColor(r, g, b)
        cs.setStrokingColor(r, g, b)
    }

    private fun setStroke(cs: PDPageContentStream, color: Int, width: Float) {
        setColor(cs, color)
        cs.setLineWidth(width)
        cs.setLineCapStyle(1)  // round
        cs.setLineJoinStyle(1) // round
    }

    private fun ellipse(cs: PDPageContentStream, cx: Float, cy: Float, rx: Float, ry: Float) {
        val k = 0.5523f
        cs.moveTo(cx + rx, cy)
        cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.closePath()
    }

    private fun borderStyle(width: Float) = COSDictionary().apply {
        setItem(name("Type"), name("Border"))
        setFloat(name("W"), width)
        setItem(name("S"), name("S"))
    }

    private fun rgb(color: Int): Triple<Float, Float, Float> = Triple(
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
    )

    private fun rgbArray(color: Int): COSArray {
        val (r, g, b) = rgb(color)
        return COSArray().apply { add(COSFloat(r)); add(COSFloat(g)); add(COSFloat(b)) }
    }

    /** An embedded system font covers most languages; Helvetica is the fallback. */
    private fun loadFont(doc: PDDocument): PDFont {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
        }
        return PDType1Font.HELVETICA
    }

    /** Drop characters the font can't encode instead of failing the whole save. */
    private fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        return buildString {
            for (c in text) {
                val s = c.toString()
                append(if (runCatching { font.encode(s) }.isSuccess) s else "?")
            }
        }
    }
}
