package com.folio.pdf.pdf

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The text of one page plus a box for every character.
 *
 * Boxes are normalised to 0..1 of the page's width/height (top-left origin), so they
 * map straight onto however large the page is drawn. Gap characters (spaces and
 * line breaks we insert between words) have NaN boxes.
 */
class PageText(val text: String, rawBoxes: FloatArray) {

    /*
     * Boxes are stored quantised to 16 bits (0..65534, 65535 = "no box"): half the memory of
     * floats, still 1/65534 of a page in precision. Matters on long documents and basic phones.
     */
    private val packed: ShortArray = ShortArray(rawBoxes.size) { i ->
        val v = rawBoxes[i]
        if (v.isNaN()) NONE.toShort() else (v.coerceIn(0f, 1f) * SCALE).toInt().toShort()
    }

    private fun box(i: Int): Float {
        val raw = packed[i].toInt() and 0xFFFF
        return if (raw == NONE) Float.NaN else raw / SCALE
    }

    private val boxes = object {
        operator fun get(i: Int): Float = box(i)
    }

    val isEmpty: Boolean get() = text.isBlank()

    /** Lower-cased, line breaks as spaces; same length as [text] so indices line up. */
    val searchable: String by lazy {
        buildString(text.length) {
            for (c in text) append(if (c == '\n') ' ' else c.lowercaseChar())
        }
    }

    /** Index of the character at (or nearest to) a normalised point. */
    fun charAt(x: Float, y: Float): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in text.indices) {
            val o = i * 4
            val l = boxes[o]
            if (l.isNaN()) continue
            val t = boxes[o + 1]; val r = boxes[o + 2]; val b = boxes[o + 3]
            val dx = if (x < l) l - x else if (x > r) x - r else 0f
            val dy = if (y < t) t - y else if (y > b) y - b else 0f
            val d = dx + dy * 4f // strongly prefer the line under the finger
            if (d < bestDistance) {
                bestDistance = d
                best = i
                if (d == 0f) break
            }
        }
        return best.takeIf { it >= 0 }
    }

    /** The word containing [index]; a single character if it's punctuation. */
    fun wordAt(index: Int): IntRange {
        if (index !in text.indices || !text[index].isLetterOrDigit()) return index..index
        var s = index
        while (s > 0 && text[s - 1].isLetterOrDigit()) s--
        var e = index
        while (e < text.length - 1 && text[e + 1].isLetterOrDigit()) e++
        return s..e
    }

    fun slice(range: IntRange): String {
        val s = range.first.coerceIn(0, text.length)
        val e = (range.last + 1).coerceIn(s, text.length)
        return text.substring(s, e).trim()
    }

    /** Highlight rectangles for a character range, merged into one per line. */
    fun rects(range: IntRange): List<Rect> {
        val out = ArrayList<Rect>()
        var current: Rect? = null
        val from = range.first.coerceAtLeast(0)
        val to = range.last.coerceAtMost(text.length - 1)
        for (i in from..to) {
            val o = i * 4
            val l = boxes[o]
            if (l.isNaN()) continue
            val r = Rect(l, boxes[o + 1], boxes[o + 2], boxes[o + 3])
            val c = current
            current = if (c != null && sameLine(c, r)) {
                Rect(min(c.left, r.left), min(c.top, r.top), max(c.right, r.right), max(c.bottom, r.bottom))
            } else {
                if (c != null) out += c
                r
            }
        }
        current?.let { out += it }
        return out
    }

    private fun sameLine(a: Rect, b: Rect): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height, b.height) && b.left >= a.left - a.height
    }

    companion object {
        private const val NONE = 0xFFFF
        private const val SCALE = 65534f

        val EMPTY = PageText("", FloatArray(0))
    }
}

/** Growable float buffer, to avoid boxing millions of Floats. */
internal class FloatBuffer(capacity: Int = 4096) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add4(a: Float, b: Float, c: Float, d: Float) {
        if (size + 4 > data.size) data = data.copyOf(max(data.size * 2, size + 4))
        data[size] = a; data[size + 1] = b; data[size + 2] = c; data[size + 3] = d
        size += 4
    }

    fun clear() { size = 0 }

    fun toArray(): FloatArray = data.copyOf(size)
}
