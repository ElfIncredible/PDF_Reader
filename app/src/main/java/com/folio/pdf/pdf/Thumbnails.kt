package com.folio.pdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.folio.pdf.util.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * First-page thumbnails for lists. Kept in memory and on disk, so Home doesn't re-open and
 * re-render every PDF on each launch, and made at most one or two at a time so a long list
 * never floods a basic phone.
 */
object Thumbnails {
    private val cache = LruCache<String, Bitmap>(60)
    private val workers by lazy { Semaphore(DeviceProfile.thumbnailWorkers) }
    @Volatile private var dir: File? = null

    fun peek(uri: String): Bitmap? = cache.get(uri)

    fun trim() = cache.evictAll()

    fun invalidate(uri: String) {
        cache.remove(uri)
        dir?.let { File(it, fileName(uri)).delete() }
    }

    suspend fun load(context: Context, uri: String, widthPx: Int = 160): Bitmap? {
        cache.get(uri)?.let { return it }
        val folder = dir ?: File(context.cacheDir, "thumbs").apply { mkdirs() }.also { dir = it }
        val file = File(folder, fileName(uri))
        withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }?.let {
            cache.put(uri, it)
            return it
        }
        return workers.withPermit {
            runCatching {
                PdfFile.open(context, Uri.parse(uri)).use { it.renderPage(0, widthPx) }
            }.getOrNull()?.let { rendered ->
                // Copy: the renderer's bitmap belongs to its (now closed) cache.
                val small = rendered.copy(Bitmap.Config.RGB_565, false) ?: rendered
                withContext(Dispatchers.IO) {
                    runCatching { file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                }
                cache.put(uri, small)
                small
            }
        }
    }

    private fun fileName(uri: String) = "${uri.hashCode().toUInt()}-${uri.length}.jpg"
}
