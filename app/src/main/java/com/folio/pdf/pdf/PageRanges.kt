package com.folio.pdf.pdf

/**
 * Parses page ranges the way people type them: "1-3, 5, 8-" (1-based, inclusive).
 * "8-" means page 8 to the end; "-3" means pages 1 to 3. Results are 0-based.
 */
object PageRanges {

    sealed interface Parsed {
        data class Ok(val ranges: List<IntRange>) : Parsed
        data class Error(val message: String) : Parsed
    }

    fun parse(spec: String, pageCount: Int): Parsed {
        if (pageCount <= 0) return Parsed.Error("This PDF has no pages")
        val parts = spec.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Parsed.Error("Type pages like 1-3, 5, 8-")
        val out = ArrayList<IntRange>()
        for (part in parts) {
            val cleaned = part.replace('–', '-').replace('—', '-').replace(" ", "")
            val range = if ('-' in cleaned) {
                val (a, b) = cleaned.split('-', limit = 2)
                val start = if (a.isEmpty()) 1 else a.toIntOrNull() ?: return Parsed.Error("\"$part\" isn't a page range")
                val end = if (b.isEmpty()) pageCount else b.toIntOrNull() ?: return Parsed.Error("\"$part\" isn't a page range")
                start..end
            } else {
                val n = cleaned.toIntOrNull() ?: return Parsed.Error("\"$part\" isn't a page number")
                n..n
            }
            if (range.first < 1 || range.last > pageCount) {
                return Parsed.Error("\"$part\" is outside 1–$pageCount")
            }
            if (range.first > range.last) return Parsed.Error("\"$part\" goes backwards")
            out += (range.first - 1)..(range.last - 1)
        }
        return Parsed.Ok(out)
    }

    /** Groups of [size] pages: 0..size-1, size..2size-1, … */
    fun everyN(size: Int, pageCount: Int): List<IntRange> {
        if (size <= 0 || pageCount <= 0) return emptyList()
        return (0 until pageCount step size).map { it..minOf(it + size - 1, pageCount - 1) }
    }

    /** "1-3" / "5" for display. */
    fun label(range: IntRange): String =
        if (range.first == range.last) "${range.first + 1}" else "${range.first + 1}-${range.last + 1}"
}
