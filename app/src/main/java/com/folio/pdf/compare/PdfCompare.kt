package com.folio.pdf.compare

import android.content.Context
import android.net.Uri
import com.folio.pdf.pdf.PageText
import com.folio.pdf.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PageDiff(val page: Int, val pieces: List<DiffPiece>) {
    val changes: Int get() = pieces.count { it.op != DiffOp.SAME }
}

data class CompareResult(
    val pagesA: Int,
    val pagesB: Int,
    val textA: List<PageText>,
    val textB: List<PageText>,
    val diffs: List<PageDiff>,
) {
    val changedPages: List<PageDiff> get() = diffs.filter { it.changes > 0 }
    val totalChanges: Int get() = diffs.sumOf { it.changes }
    val aHasText: Boolean get() = textA.any { !it.isEmpty }
    val bHasText: Boolean get() = textB.any { !it.isEmpty }
}

/** Compares the text of two PDFs page by page (page 1 with page 1, and so on). */
object PdfCompare {

    suspend fun compare(context: Context, a: Uri, b: Uri, progress: (Float) -> Unit): CompareResult =
        withContext(Dispatchers.Default) {
            val textA = readAll(context, a) { progress(it * 0.45f) }
            val textB = readAll(context, b) { progress(0.45f + it * 0.45f) }
            val pages = maxOf(textA.size, textB.size)
            val diffs = (0 until pages).map { p ->
                val ta = textA.getOrNull(p)?.text.orEmpty()
                val tb = textB.getOrNull(p)?.text.orEmpty()
                PageDiff(p, TextDiff.diff(TextDiff.tokenize(ta), TextDiff.tokenize(tb)))
            }
            progress(1f)
            CompareResult(textA.size, textB.size, textA, textB, diffs)
        }

    private suspend fun readAll(context: Context, uri: Uri, progress: (Float) -> Unit): List<PageText> {
        val pages = ArrayList<Pair<Int, PageText>>()
        PdfTextExtractor.extract(context, uri, onOutline = {}) { i, t -> pages += i to t }
        progress(1f)
        val count = (pages.maxOfOrNull { it.first } ?: -1) + 1
        val out = MutableList(count) { PageText.EMPTY }
        pages.forEach { (i, t) -> out[i] = t }
        return out
    }
}
