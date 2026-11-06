package com.folio.pdf.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.Writer
import kotlin.coroutines.CoroutineContext

/** One entry of the PDF's table of contents. [page] is -1 if it points nowhere. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/**
 * Reads the document's structure with PdfBox in a single background pass:
 * the outline first (fast), then the text of every page, reported page by page
 * so search works before the whole file has been read.
 */
object PdfTextExtractor {

    suspend fun extract(
        context: Context,
        uri: Uri,
        onOutline: (List<OutlineEntry>) -> Unit,
        onPage: (pageIndex: Int, text: PageText) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val document = context.contentResolver.openInputStream(uri)?.use { input ->
            // Temp-file buffering keeps memory flat even for very large PDFs.
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly())
        } ?: throw FileNotFoundException("Cannot read $uri")

        document.use { doc ->
            onOutline(readOutline(doc))
            val collector = PageCollector(coroutineContext, onPage)
            collector.setSortByPosition(true)
            collector.writeText(doc, DiscardWriter)
        }
    }

    private fun readOutline(doc: PDDocument): List<OutlineEntry> {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val out = mutableListOf<OutlineEntry>()
        fun walk(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                if (out.size >= 3000 || depth > 8) return // guard against broken/cyclic outlines
                val page = runCatching {
                    item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }
                }.getOrNull() ?: -1
                val title = item.title?.trim().orEmpty().ifEmpty { "Untitled" }
                out += OutlineEntry(title, page, depth)
                walk(item, depth + 1)
            }
        }
        runCatching { walk(root, 0) }
        return out
    }

    private object DiscardWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    private class PageCollector(
        private val job: CoroutineContext,
        private val onPage: (Int, PageText) -> Unit,
    ) : PDFTextStripper() {
        private val text = StringBuilder()
        private val boxes = FloatBuffer()

        override fun startPage(page: PDPage) {
            text.setLength(0)
            boxes.clear()
            super.startPage(page)
        }

        override fun writeString(string: String, textPositions: List<TextPosition>) {
            job.ensureActive() // stop promptly when the reader is closed
            for (tp in textPositions) {
                val unicode = tp.unicode ?: continue
                val pw = tp.pageWidth
                val ph = tp.pageHeight
                if (pw <= 0f || ph <= 0f) continue
                val h = if (tp.heightDir > 0f) tp.heightDir else tp.fontSizeInPt
                // tp.y is the baseline measured from the top; pad for ascenders/descenders.
                val l = tp.x / pw
                val t = (tp.y - h * 1.15f) / ph
                val r = (tp.x + tp.width) / pw
                val b = (tp.y + h * 0.25f) / ph
                for (c in unicode) {
                    text.append(c)
                    boxes.add4(l, t, r, b)
                }
            }
        }

        override fun writeWordSeparator() = gap(' ')

        override fun writeLineSeparator() = gap('\n')

        private fun gap(c: Char) {
            if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') {
                text.append(c)
                boxes.add4(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            }
        }

        override fun endPage(page: PDPage) {
            super.endPage(page)
            onPage(currentPageNo - 1, PageText(text.toString(), boxes.toArray()))
        }
    }
}
