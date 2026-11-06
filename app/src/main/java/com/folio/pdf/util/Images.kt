package com.folio.pdf.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build

/**
 * Decodes an image no larger than [maxSide] pixels on its longest edge.
 * On Android 9+ the photo's rotation (EXIF) is applied, so pictures come out upright.
 */
fun decodeScaledBitmap(context: Context, source: Uri, maxSide: Int): Bitmap? {
    val resolver = context.contentResolver
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source)) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > maxSide) {
                    val scale = maxSide.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()
}
