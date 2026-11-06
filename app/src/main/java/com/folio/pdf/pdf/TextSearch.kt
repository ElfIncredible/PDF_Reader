package com.folio.pdf.pdf

import java.util.concurrent.atomic.AtomicReferenceArray

data class SearchHit(
    val page: Int,
    /** Character range in [PageText.text]. */
    val range: IntRange,
    val snippet: String,
    /** Where the match sits inside [snippet], for bolding. */
    val snippetMatch: IntRange,
)

object TextSearch {
    private const val MAX_HITS = 2000
    private const val CONTEXT = 32

    /** Case-insensitive; any run of whitespace in the query matches a space or line break. */
    fun find(pages: AtomicReferenceArray<PageText?>, query: String): List<SearchHit> {
        val q = buildString {
            for (c in query.trim()) append(c.lowercaseChar())
        }.replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()
        for (p in 0 until pages.length()) {
            val page = pages.get(p) ?: continue
            val hay = page.searchable
            var from = 0
            while (true) {
                val at = hay.indexOf(q, from)
                if (at < 0) break
                val range = at until at + q.length
                val s = (at - CONTEXT).coerceAtLeast(0)
                val e = (at + q.length + CONTEXT).coerceAtMost(page.text.length)
                val prefix = if (s > 0) "…" else ""
                val raw = page.text.substring(s, e).replace('\n', ' ')
                val snippet = prefix + raw + if (e < page.text.length) "…" else ""
                val matchStart = prefix.length + (at - s)
                hits += SearchHit(p, range, snippet, matchStart until matchStart + q.length)
                if (hits.size >= MAX_HITS) return hits
                from = at + q.length
            }
        }
        return hits
    }
}
