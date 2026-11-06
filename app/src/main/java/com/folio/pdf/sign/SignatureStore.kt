package com.folio.pdf.sign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Saved handwritten signatures and initials: transparent PNGs in the app's private storage. */
object SignatureStore {

    private fun dir(context: Context) = File(context.filesDir, "signatures").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun save(context: Context, bitmap: Bitmap): File {
        val trimmed = trimTransparent(bitmap) ?: bitmap
        val file = File(dir(context), "signature-${System.currentTimeMillis()}.png")
        file.outputStream().use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun delete(file: File) {
        file.delete()
    }

    /** Crops away empty transparent margins (with a little padding). */
    fun trimTransparent(src: Bitmap, pad: Int = 12): Bitmap? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) > 16) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        val l = max(0, left - pad)
        val t = max(0, top - pad)
        val r = min(w - 1, right + pad)
        val b = min(h - 1, bottom + pad)
        return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }

    enum class TypedStyle(val label: String, val typeface: Typeface) {
        SCRIPT("Script", Typeface.create("cursive", Typeface.NORMAL)),
        ELEGANT("Elegant", Typeface.create(Typeface.SERIF, Typeface.ITALIC)),
        PLAIN("Plain", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)),
    }

    /** Renders a typed name as a signature image. */
    fun renderTyped(text: String, style: TypedStyle, color: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = style.typeface
            textSize = 160f
            this.color = color
        }
        val width = max(1, paint.measureText(text).toInt() + 60)
        val fm = paint.fontMetrics
        val height = (fm.descent - fm.ascent).toInt() + 60
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 30f, 30f - fm.ascent, paint)
        return bitmap
    }

    /**
     * Turns a photo of a signature on paper into ink on a transparent background:
     * light pixels (paper) disappear, dark pixels become [inkColor].
     */
    fun cleanPhoto(src: Bitmap, inkColor: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        // Estimate the paper brightness from the brightest tenth of the image.
        val lumas = IntArray(256)
        for (p in pixels) lumas[luma(p)]++
        var count = 0
        var paper = 255
        for (l in 255 downTo 0) {
            count += lumas[l]
            if (count > pixels.size / 10) { paper = l; break }
        }
        val threshold = (paper * 0.78f).toInt()
        val r = Color.red(inkColor); val g = Color.green(inkColor); val b = Color.blue(inkColor)
        for (i in pixels.indices) {
            val l = luma(pixels[i])
            val alpha = if (l >= threshold) 0 else (((threshold - l) * 255f) / threshold * 1.6f).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, r, g, b)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun luma(p: Int): Int =
        ((Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000).coerceIn(0, 255)
}
