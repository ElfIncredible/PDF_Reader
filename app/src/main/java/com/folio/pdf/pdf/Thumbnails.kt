package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache

/** First-page thumbnails for lists, kept in a small in-memory cache. */
object Thumbnails {
    private val cache = LruCache<String, Bitmap>(80)

    fun peek(uri: String): Bitmap? = cache.get(uri)

    fun invalidate(uri: String) {
        cache.remove(uri)
    }

    suspend fun load(context: Context, uri: String, widthPx: Int = 160): Bitmap? {
        cache.get(uri)?.let { return it }
        return runCatching {
            PdfFile.open(context, Uri.parse(uri)).use { it.renderPage(0, widthPx) }
        }.getOrNull()?.also { cache.put(uri, it) }
    }
}
