package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.folio.pdf.ocr.Ocr
import com.folio.pdf.ocr.OcrLine
import com.folio.pdf.util.decodeScaledBitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Builds PDFs whose pages carry an invisible text layer from OCR, so scans can be
 * searched, selected and copied (Phase 2's text engine reads it like any other text).
 */
object OcrPdf {

    /** Images (e.g. from the camera scanner) → a PDF, optionally searchable. */
    /**
     * Greyscale + auto-contrast: stretches the 2nd–98th percentile of brightness
     * to full range so paper turns white and ink turns dark. Smaller JPEGs, crisper OCR.
     */
    internal fun enhance(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val y = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            px[i] = y
            hist[y]++
        }
        val n = px.size
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] < n * 0.02) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] < n * 0.02) { acc += hist[hi]; hi-- }
        if (hi - lo < 32) { lo = 0; hi = 255 }
        val lut = IntArray(256) { v -> (((v - lo) * 255) / (hi - lo)).coerceIn(0, 255) }
        for (i in px.indices) {
            val g = lut[px[i]]
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    suspend fun fromImages(
        context: Context,
        images: List<Uri>,
        recognise: Boolean,
        target: File,
        progress: (Float) -> Unit,
        blackAndWhite: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val font = lazy { PdfFonts.load(doc) }
            images.forEachIndexed { i, uri ->
                coroutineContext.ensureActive()
                val decoded = decodeScaledBitmap(context, uri, if (com.folio.pdf.util.DeviceProfile.lite) 1600 else 2200)
                    ?: throw ToolException("Couldn't read scanned page ${i + 1}")
                val bitmap = if (blackAndWhite) enhance(decoded).also { if (it !== decoded) decoded.recycle() } else decoded
                // A4 width; height follows the scan, so receipts stay long and cards stay short.
                val pageWidth = 595f
                val pageHeight = pageWidth * bitmap.height / bitmap.width
                val page = PDPage(PDRectangle(pageWidth, pageHeight))
                doc.addPage(page)
                val image = JPEGFactory.createFromImage(doc, bitmap, 0.8f)
                val lines = if (recognise) Ocr.recognize(bitmap) else emptyList()
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                    if (lines.isNotEmpty()) drawInvisibleText(cs, font.value, lines, pageWidth, pageHeight)
                }
                bitmap.recycle()
                progress((i + 1f) / (images.size + 1))
            }
            doc.save(target)
            progress(1f)
        }
    }

    /**
     * Adds a text layer to the pages of an existing PDF that have no text yet.
     * Returns how many pages were recognised.
     */
    suspend fun makeSearchable(
        context: Context,
        input: Uri,
        target: File,
        progress: (Float) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        var recognised = 0
        PdfFile.open(context, input).use { renderer ->
            PdfTools.openForEditing(context, input).use { doc ->
                val font = PdfFonts.load(doc)
                val stripper = PDFTextStripper()
                val count = doc.numberOfPages
                for (i in 0 until count) {
                    coroutineContext.ensureActive()
                    stripper.startPage = i + 1
                    stripper.endPage = i + 1
                    val existing = runCatching { stripper.getText(doc) }.getOrDefault("")
                    if (existing.count { !it.isWhitespace() } > 20) {
                        progress((i + 1f) / (count + 1)); continue
                    }
                    val size = renderer.pageSize(i) ?: continue
                    // ~200 dpi is a good speed/accuracy balance for OCR.
                    val widthPx = (size.width * 200f / 72f).toInt().coerceIn(800, com.folio.pdf.util.DeviceProfile.ocrMaxWidth)
                    val bitmap: Bitmap = renderer.renderPage(i, widthPx) ?: continue
                    val lines = Ocr.recognize(bitmap)
                    if (lines.isNotEmpty()) {
                        val page = doc.getPage(i)
                        val geometry = PageGeometry(page)
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            // Draw in display points so text lines up however the page is rotated.
                            cs.transform(geometry.matrix())
                            drawInvisibleText(cs, font, lines, geometry.displayWidth, geometry.displayHeight)
                        }
                        recognised++
                    }
                    progress((i + 1f) / (count + 1))
                }
                doc.save(target)
            }
        }
        progress(1f)
        recognised
    }

    /**
     * Writes each OCR line as invisible text (render mode "neither"), sized and stretched
     * to cover the line's box. Coordinates: x right, y up, in a [width]×[height] space.
     */
    internal fun drawInvisibleText(
        cs: PDPageContentStream,
        font: PDFont,
        lines: List<OcrLine>,
        width: Float,
        height: Float,
    ) {
        for (line in lines) {
            val text = PdfFonts.encodable(font, line.text)
            if (text.isBlank()) continue
            val boxLeft = line.left * width
            val boxBottom = (1f - line.bottom) * height
            val boxWidth = (line.right - line.left) * width
            val boxHeight = (line.bottom - line.top) * height
            if (boxWidth <= 1f || boxHeight <= 1f) continue
            val fontSize = max(2f, boxHeight * 0.82f)
            val natural = runCatching { font.getStringWidth(text) / 1000f * fontSize }.getOrDefault(0f)
            if (natural <= 0f) continue
            val stretch = min(400f, max(25f, boxWidth / natural * 100f))
            cs.beginText()
            try {
                cs.setFont(font, fontSize)
                cs.setRenderingMode(RenderingMode.NEITHER)
                cs.setHorizontalScaling(stretch)
                cs.newLineAtOffset(boxLeft, boxBottom + boxHeight * 0.2f)
                runCatching { cs.showText(text) }
            } finally {
                cs.endText()
            }
        }
    }
}
