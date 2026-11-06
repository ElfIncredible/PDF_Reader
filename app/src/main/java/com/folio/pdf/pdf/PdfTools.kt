package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.folio.pdf.util.decodeScaledBitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A page in a new order, with extra clockwise rotation (0/90/180/270). */
data class PageOp(val source: Int, val rotation: Int = 0)

enum class CompressionLevel(val label: String, val detail: String, val maxSide: Int, val quality: Float) {
    LOW("Light", "Best quality, smaller saving", 2400, 0.8f),
    MEDIUM("Balanced", "Good quality, good saving", 1600, 0.65f),
    HIGH("Strong", "Smallest file, lower image quality", 1100, 0.5f),
}

enum class PaperSize(val label: String, val width: Float, val height: Float) {
    A4("A4", 595f, 842f),
    LETTER("Letter", 612f, 792f),
    FIT("Fit image", 0f, 0f),
}

enum class ImageFormat(val label: String, val extension: String, val mime: String) {
    JPEG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
}

/** A clear message for the screen. */
class ToolException(message: String) : IOException(message)

/**
 * Phase 4 toolkit. Every function reads its input with PdfBox (buffered to temp files,
 * so big PDFs don't exhaust memory), writes a new file and never touches the input.
 * Run them off the main thread; they check for cancellation between pages.
 */
object PdfTools {

    // ---- Merge -------------------------------------------------------------------

    suspend fun merge(context: Context, inputs: List<Uri>, target: File, progress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val destination = PDDocument(MemoryUsageSetting.setupTempFileOnly())
            val sources = ArrayList<PDDocument>()
            try {
                val merger = PDFMergerUtility()
                inputs.forEachIndexed { i, uri ->
                    coroutineContext.ensureActive()
                    val source = openForEditing(context, uri)
                    sources += source
                    merger.appendDocument(destination, source)
                    progress((i + 1f) / (inputs.size + 1))
                }
                destination.save(target)
                progress(1f)
            } finally {
                sources.forEach { runCatching { it.close() } }
                destination.close()
            }
        }

    // ---- Split / extract ---------------------------------------------------------

    /**
     * Writes one PDF per range. [open] gives the output stream for part i (0-based),
     * or null to skip it.
     */
    suspend fun split(
        context: Context,
        input: Uri,
        ranges: List<IntRange>,
        open: (index: Int, range: IntRange) -> java.io.OutputStream?,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        openForEditing(context, input).use { source ->
            ranges.forEachIndexed { i, range ->
                coroutineContext.ensureActive()
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { part ->
                    for (p in range) part.importPage(pinned(source.getPage(p)))
                    open(i, range)?.use { part.save(it) }
                }
                progress((i + 1f) / ranges.size)
            }
        }
    }

    /** A new PDF with just [pages], in that order. */
    suspend fun extract(context: Context, input: Uri, pages: List<PageOp>, target: File) =
        withContext(Dispatchers.IO) {
            openForEditing(context, input).use { source ->
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { out ->
                    for (op in pages) {
                        coroutineContext.ensureActive()
                        val imported = out.importPage(pinned(source.getPage(op.source)))
                        imported.rotation = normaliseRotation(imported.rotation + op.rotation)
                    }
                    out.save(target)
                }
            }
        }

    // ---- Organize (reorder / rotate / delete) ------------------------------------

    /**
     * Rebuilds the page order in place, so bookmarks, links, form fields and metadata
     * survive. Pages not listed in [order] are deleted.
     */
    suspend fun organize(context: Context, input: Uri, order: List<PageOp>, target: File) =
        withContext(Dispatchers.IO) {
            if (order.isEmpty()) throw ToolException("A PDF needs at least one page")
            openForEditing(context, input).use { doc ->
                val all = doc.pages.toList()
                all.forEach { pinned(it) }
                all.forEach { doc.pages.remove(it) }
                for (op in order) {
                    val page = all[op.source]
                    page.rotation = normaliseRotation(page.rotation + op.rotation)
                    doc.pages.add(page)
                }
                doc.save(target)
            }
        }

    // ---- Compress ----------------------------------------------------------------

    /** Re-encodes large images as JPEG at the level's size/quality. Text stays sharp. */
    suspend fun compress(
        context: Context,
        input: Uri,
        level: CompressionLevel,
        target: File,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        openForEditing(context, input).use { doc ->
            val done = HashMap<COSBase, PDImageXObject?>()
            val count = doc.numberOfPages
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                doc.getPage(i).resources?.let { shrinkImages(doc, it, level, done, depth = 0) }
                progress((i + 1f) / (count + 1))
            }
            doc.save(target)
            progress(1f)
        }
    }

    private fun shrinkImages(
        doc: PDDocument,
        resources: PDResources,
        level: CompressionLevel,
        done: HashMap<COSBase, PDImageXObject?>,
        depth: Int,
    ) {
        for (name in resources.xObjectNames.toList()) {
            val x = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
            when (x) {
                is PDImageXObject -> {
                    val key: COSBase = x.cosObject
                    val replacement = if (done.containsKey(key)) done[key] else shrink(doc, x, level).also { done[key] = it }
                    if (replacement != null) resources.put(name, replacement)
                }
                is PDFormXObject -> if (depth < 6) x.resources?.let { shrinkImages(doc, it, level, done, depth + 1) }
            }
        }
    }

    private fun shrink(doc: PDDocument, image: PDImageXObject, level: CompressionLevel): PDImageXObject? {
        return runCatching {
            val w = image.width
            val h = image.height
            // Leave small images, masks and transparency alone.
            if (w.toLong() * h < 90_000) return null
            if (image.isStencil || image.bitsPerComponent == 1) return null
            if (image.cosObject.getDictionaryObject(COSName.getPDFName("SMask")) != null) return null
            if (image.cosObject.getDictionaryObject(COSName.getPDFName("Mask")) != null) return null

            val largest = max(w, h)
            val subsample = max(1, largest / (level.maxSide * 2))
            var bitmap: Bitmap = image.getImage(null, subsample) ?: return null
            val side = max(bitmap.width, bitmap.height)
            if (side > level.maxSide) {
                val s = level.maxSide.toFloat() / side
                bitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * s).roundToInt().coerceAtLeast(1),
                    (bitmap.height * s).roundToInt().coerceAtLeast(1),
                    true,
                )
            }
            val jpeg = JPEGFactory.createFromImage(doc, bitmap, level.quality)
            // Only swap if it actually got smaller.
            if (jpeg.cosObject.length >= image.cosObject.length) null else jpeg
        }.getOrNull()
    }

    // ---- Images → PDF ------------------------------------------------------------

    suspend fun imagesToPdf(
        context: Context,
        images: List<Uri>,
        paper: PaperSize,
        marginPt: Float,
        target: File,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            images.forEachIndexed { i, uri ->
                coroutineContext.ensureActive()
                val bitmap = decodeScaledBitmap(context, uri, 2500)
                    ?: throw ToolException("Couldn't read image ${i + 1}")
                val landscape = bitmap.width > bitmap.height
                val box = when (paper) {
                    PaperSize.FIT -> {
                        // 150 dpi-ish, but always at least A4-width so small photos don't make tiny pages.
                        val w = max(595f, bitmap.width * 72f / 150f)
                        PDRectangle(w + marginPt * 2, w * bitmap.height / bitmap.width + marginPt * 2)
                    }
                    else -> if (landscape) PDRectangle(paper.height, paper.width) else PDRectangle(paper.width, paper.height)
                }
                val page = PDPage(box)
                doc.addPage(page)
                val image = if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap)
                else JPEGFactory.createFromImage(doc, bitmap, 0.85f)
                val availW = box.width - marginPt * 2
                val availH = box.height - marginPt * 2
                val scale = min(availW / bitmap.width, availH / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, (box.width - dw) / 2, (box.height - dh) / 2, dw, dh)
                }
                progress((i + 1f) / (images.size + 1))
            }
            doc.save(target)
            progress(1f)
        }
    }

    // ---- Text → PDF --------------------------------------------------------------

    suspend fun textToPdf(context: Context, input: Uri, fontSize: Float, target: File) =
        withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(input)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw FileNotFoundException("Cannot read $input")
            val text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").removePrefix("﻿")

            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font = PdfFonts.load(doc)
                val margin = 56f
                val box = PDRectangle.A4
                val maxWidth = box.width - margin * 2
                val leading = fontSize * 1.4f
                val linesPerPage = ((box.height - margin * 2) / leading).toInt().coerceAtLeast(1)

                val lines = ArrayList<String>()
                for (paragraph in text.split('\n')) {
                    coroutineContext.ensureActive()
                    lines += wrap(PdfFonts.encodable(font, paragraph), font, fontSize, maxWidth)
                }
                if (lines.isEmpty()) lines += ""

                lines.chunked(linesPerPage).forEach { pageLines ->
                    val page = PDPage(box)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setLeading(leading)
                        cs.newLineAtOffset(margin, box.height - margin - fontSize)
                        pageLines.forEach { line ->
                            if (line.isNotEmpty()) cs.showText(line)
                            cs.newLine()
                        }
                        cs.endText()
                    }
                }
                doc.save(target)
            }
        }

    private fun wrap(paragraph: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        if (paragraph.isEmpty()) return listOf("")
        fun width(s: String) = runCatching { font.getStringWidth(s) / 1000f * size }.getOrDefault(s.length * size * 0.55f)
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in paragraph.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
                continue
            }
            if (line.isNotEmpty()) out += line.toString()
            // A single word longer than the line: break it by characters.
            var rest = word
            while (width(rest) > maxWidth && rest.length > 1) {
                var cut = rest.length - 1
                while (cut > 1 && width(rest.substring(0, cut)) > maxWidth) cut--
                out += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            line = StringBuilder(rest)
        }
        out += line.toString()
        return out
    }

    // ---- PDF → images ------------------------------------------------------------

    /** Renders every page into [folder]. Returns the created files. */
    suspend fun pdfToImages(
        context: Context,
        input: Uri,
        format: ImageFormat,
        dpi: Int,
        folder: Uri,
        baseName: String,
        progress: (Float) -> Unit,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, folder) ?: throw ToolException("Can't open that folder")
        val out = ArrayList<Uri>()
        PdfFile.open(context, input).use { pdf ->
            val count = pdf.pageCount
            val digits = count.toString().length.coerceAtLeast(2)
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val size = pdf.pageSize(i) ?: continue
                val widthPx = (size.width * dpi / 72f).roundToInt().coerceIn(200, 5000)
                val bitmap = pdf.renderPage(i, widthPx) ?: continue
                val name = "$baseName-p${(i + 1).toString().padStart(digits, '0')}.${format.extension}"
                val file = dir.createFile(format.mime, name) ?: throw ToolException("Can't create files in that folder")
                context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                    bitmap.compress(
                        if (format == ImageFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        92,
                        stream,
                    )
                }
                out += file.uri
                progress((i + 1f) / count)
            }
        }
        out
    }

    // ---- Helpers -----------------------------------------------------------------

    fun pageCount(context: Context, input: Uri): Int = openForEditing(context, input).use { it.numberOfPages }

    /**
     * Loads a PDF ready to be changed. Files with only an owner password (printing or
     * copying restrictions) are unlocked; files that need a password to open are refused.
     */
    fun openForEditing(context: Context, uri: Uri): PDDocument {
        val doc = try {
            context.contentResolver.openInputStream(uri)?.use {
                PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            } ?: throw FileNotFoundException("Cannot read $uri")
        } catch (e: InvalidPasswordException) {
            throw ToolException("This PDF is password-protected. Unlocking arrives in Phase 6.")
        }
        if (!doc.isEncrypted) return doc
        // Re-load an unencrypted copy so pages can be moved between documents safely.
        val temp = File.createTempFile("folio-unlocked", ".pdf", context.cacheDir)
        try {
            doc.setAllSecurityToBeRemoved(true)
            doc.save(temp)
        } finally {
            doc.close()
        }
        return try {
            PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
        } finally {
            temp.deleteOnExit()
        }
    }

    /**
     * Copies inherited attributes (resources, boxes, rotation) onto the page itself, so it
     * looks the same after moving to another document or another place in the page tree.
     */
    private fun pinned(page: PDPage): PDPage {
        page.resources?.let { page.resources = it }
        page.mediaBox = page.mediaBox
        page.cropBox = page.cropBox
        page.rotation = page.rotation
        return page
    }

    private fun normaliseRotation(r: Int) = ((r % 360) + 360) % 360
}

/** Fonts for text we write ourselves: an embedded system font covers most languages. */
object PdfFonts {
    private val candidates = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    )

    fun load(doc: PDDocument): PDFont {
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) runCatching { return PDType0Font.load(doc, f) }
        }
        return PDType1Font.HELVETICA
    }

    /** Replaces characters the font can't show with "?" instead of failing. */
    fun encodable(font: PDFont, text: String): String {
        if (runCatching { font.encode(text) }.isSuccess) return text
        return buildString {
            for (c in text) {
                val s = c.toString()
                append(if (runCatching { font.encode(s) }.isSuccess) s else "?")
            }
        }
    }
}
