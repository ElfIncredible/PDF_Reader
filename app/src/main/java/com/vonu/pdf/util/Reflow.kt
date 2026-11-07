package com.vonu.pdf.util

/**
 * Rebuilds paragraphs from a PDF page's lines, for the Text (reflow) reading mode and for
 * reading aloud: lines are joined, words split with a hyphen at a line end are mended, and a
 * new paragraph starts after a short line that ends a sentence, or at a bullet or heading.
 */
object Reflow {
    private val bullet = Regex("""^(?:[•▪◦·\-*–]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)])\s+""")
    private val sentenceEnd = Regex("""[.!?:;"”’)\]]$""")

    fun paragraphs(pageText: String): List<String> {
        val lines = pageText.split('\n').map { it.trim().replace(Regex("""\s+"""), " ") }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        // "Full" line length: the longer lines on the page, so short last lines stand out.
        val sorted = lines.map { it.length }.sorted()
        val full = sorted[(sorted.size * 3) / 4].coerceAtLeast(20)

        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out += current.toString().trim()
            current.setLength(0)
        }

        for ((i, line) in lines.withIndex()) {
            val prev = lines.getOrNull(i - 1)
            val startsNew = when {
                prev == null -> false
                bullet.containsMatchIn(line) -> true
                // A short previous line that ended a sentence (end of a paragraph)…
                prev.length < full * 0.7 && sentenceEnd.containsMatchIn(prev) -> true
                // …or a short line with no full stop followed by a capital: a heading.
                prev.length < full * 0.5 && !sentenceEnd.containsMatchIn(prev) && line.first().isUpperCase() &&
                    prev.split(' ').size <= 8 && !prev.endsWith("-") && !prev.endsWith(",") -> true
                else -> false
            }
            if (startsNew) flush()
            when {
                current.isEmpty() -> current.append(line)
                // "infor-" + "mation" → "information" (but keep real dashes like "self-" + "Service").
                current.endsWith("-") && line.first().isLowerCase() -> {
                    current.setLength(current.length - 1)
                    current.append(line)
                }
                else -> current.append(' ').append(line)
            }
        }
        flush()
        return out
    }
}
