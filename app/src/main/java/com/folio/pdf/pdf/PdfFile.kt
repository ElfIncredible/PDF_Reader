package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PageSize(val width: Int, val height: Int) {
    /** height / width */
    val aspect: Float get() = if (width == 0) 1.414f else height.toFloat() / width
}

/**
 * One open PDF. Wraps the platform [PdfRenderer], which can only render one page
 * at a time, so every call is serialised with a [Mutex].
 *
 * Phase 2 note: PdfRenderer has no text extraction below API 35. When search and
 * text selection land, re-implement this class on PDFium and keep its public API,
 * so the UI doesn't change.
 */
class PdfFile private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int = renderer.pageCount

    init {
        synchronized(open) { open.add(this) }
    }

    private val mutex = Mutex()
    private val sizes = arrayOfNulls<PageSize>(pageCount)
    @Volatile private var closeRequested = false
    private var released = false

    private val bitmaps = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun cachedPage(index: Int, widthPx: Int): Bitmap? = bitmaps.get(key(index, widthPx))

    /** height / width if the page has been measured already. */
    fun cachedAspect(index: Int): Float? = sizes.getOrNull(index)?.aspect

    suspend fun pageSize(index: Int): PageSize? {
        sizes.getOrNull(index)?.let { return it }
        return withRenderer {
            sizes[index] ?: renderer.openPage(index).use { PageSize(it.width, it.height) }
                .also { sizes[index] = it }
        }
    }

    /** Renders [index] at [widthPx] wide (height follows the page's aspect ratio). */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        if (index !in 0 until pageCount || widthPx <= 0) return null
        bitmaps.get(key(index, widthPx))?.let { return it }
        return withRenderer {
            renderer.openPage(index).use { page ->
                sizes[index] = PageSize(page.width, page.height)
                var w = widthPx
                var h = (page.height.toFloat() * w / page.width).roundToInt().coerceAtLeast(1)
                // Never allocate more than MAX_PIXELS; the UI scales up if needed.
                val pixels = w.toLong() * h
                if (pixels > MAX_PIXELS) {
                    val f = sqrt(MAX_PIXELS.toDouble() / pixels)
                    w = (w * f).toInt().coerceAtLeast(1)
                    h = (h * f).toInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }?.also { bitmaps.put(key(index, widthPx), it) }
    }

    private suspend fun <T> withRenderer(block: () -> T): T? = mutex.withLock {
        if (closeRequested) {
            release(); null
        } else {
            withContext(Dispatchers.IO) { block() }
        }
    }

    override fun close() {
        closeRequested = true
        // If a render is in flight, the next withRenderer() call releases instead.
        if (mutex.tryLock()) {
            try { release() } finally { mutex.unlock() }
        }
    }

    /** Drops cached page images (called when Android is short of memory). */
    fun trimCache() = bitmaps.evictAll()

    private fun release() {
        if (released) return
        released = true
        synchronized(open) { open.remove(this) }
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        bitmaps.evictAll()
    }

    private fun key(index: Int, widthPx: Int) = "$index@$widthPx"

    companion object {
        private const val MAX_PIXELS = 12_000_000L // ~48 MB per page bitmap at worst

        /** Every open document, so their caches can be emptied under memory pressure. */
        private val open: MutableSet<PdfFile> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        fun trimAll() {
            val snapshot = synchronized(open) { open.toList() }
            snapshot.forEach { it.trimCache() }
        }

        private fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()

        /**
         * Opens [uri]. Throws [SecurityException] for password-protected files or
         * revoked access, and [java.io.IOException] for damaged files.
         */
        suspend fun open(context: Context, uri: Uri): PdfFile = withContext(Dispatchers.IO) {
            var pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Cannot open $uri")
            // PdfRenderer needs a seekable file. Some apps (email, chat) hand us a pipe,
            // so copy those to the cache first.
            if (pfd.statSize <= 0) {
                pfd.close()
                pfd = ParcelFileDescriptor.open(
                    copyToCache(context, uri),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )
            }
            try {
                PdfFile(pfd, PdfRenderer(pfd))
            } catch (t: Throwable) {
                pfd.close()
                throw t
            }
        }

        private fun copyToCache(context: Context, uri: Uri): File {
            val dir = File(context.cacheDir, "opened").apply { mkdirs() }
            val target = File(dir, "${uri.toString().hashCode()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw FileNotFoundException("Cannot read $uri")
            return target
        }
    }
}
