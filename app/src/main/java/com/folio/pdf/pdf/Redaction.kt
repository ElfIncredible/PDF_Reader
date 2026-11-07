package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.folio.pdf.edit.NRect
import com.folio.pdf.ocr.Ocr
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** An area to black out, normalised to the displayed page. */
data class RedactMark(val page: Int, val rect: NRect)

/**
 * True redaction: each page with marks is replaced by a picture of itself with black boxes
 * burnt in, so the hidden text, images and links are gone, not just covered. Optionally
 * the rest of the page is re-recognised (OCR) so it stays searchable.
 */
object Redaction {

    suspend fun apply(
        context: Context,
        input: Uri,
        marks: List<RedactMark>,
        removeHiddenData: Boolean,
        keepSearchable: Boolean,
        target: File,
        progress: (Float) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (marks.isEmpty()) throw ToolException("Mark something to redact first")
        val byPage = marks.groupBy { it.page }
        PdfFile.open(context, input).use { renderer ->
            PdfTools.openForEditing(context, input).use { doc ->
                // Form answers could otherwise survive in the form data.
                doc.documentCatalog.acroForm?.let { form -> if (form.fields.isNotEmpty()) runCatching { form.flatten() } }
                val font = lazy { PdfFonts.load(doc) }
                var done = 0
                for ((pageIndex, pageMarks) in byPage) {
                    coroutineContext.ensureActive()
                    val size = renderer.pageSize(pageIndex) ?: continue
                    val widthPx = (size.width * 200f / 72f).toInt().coerceIn(900, com.folio.pdf.util.DeviceProfile.ocrMaxWidth)
                    // Copy: the renderer's bitmap is cached and must not be drawn on.
                    val rendered = renderer.renderPage(pageIndex, widthPx) ?: continue
                    val bitmap = rendered.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(bitmap)
                    val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
                    val w = bitmap.width.toFloat()
                    val h = bitmap.height.toFloat()
                    for (m in pageMarks) canvas.drawRect(m.rect.left * w, m.rect.top * h, m.rect.right * w, m.rect.bottom * h, black)

                    val lines = if (keepSearchable) runCatching { Ocr.recognize(bitmap) }.getOrDefault(emptyList()) else emptyList()

                    val page = doc.getPage(pageIndex)
                    val geometry = PageGeometry(page)
                    page.resources = PDResources()
                    page.annotations = emptyList()
                    val image = JPEGFactory.createFromImage(doc, bitmap, 0.88f)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
                        cs.transform(geometry.matrix())
                        cs.drawImage(image, 0f, 0f, geometry.displayWidth, geometry.displayHeight)
                        if (lines.isNotEmpty()) {
                            OcrPdf.drawInvisibleText(cs, font.value, lines, geometry.displayWidth, geometry.displayHeight)
                        }
                    }
                    done++
                    progress(done / (byPage.size + 1f))
                }
                if (removeHiddenData) Sanitizer.clean(doc)
                doc.save(target)
                progress(1f)
                done
            }
        }
    }
}
