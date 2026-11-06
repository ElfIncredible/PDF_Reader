package com.folio.pdf.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognised line. The box is normalised to 0..1 of the image (top-left origin). */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * On-device text recognition with ML Kit (Latin script: English, French, Swahili, Spanish…).
 * Nothing leaves the phone.
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.isEmpty()) null
                else OcrLine(text, box.left / w, box.top / h, box.right / w, box.bottom / h)
            }
        }
    }
}

/** Await a Play services Task without an extra library. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
