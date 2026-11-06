package com.folio.pdf.compare

/** A word and where it sits in the page text. */
data class Token(val text: String, val start: Int, val endExclusive: Int)

enum class DiffOp { SAME, REMOVED, ADDED }

/**
 * A run of words with the same fate. [aRange]/[bRange] are character ranges in the old/new
 * page text (null on the side where the words don't exist).
 */
data class DiffPiece(val op: DiffOp, val text: String, val aRange: IntRange?, val bRange: IntRange?)

object TextDiff {
    private val word = Regex("""\S+""")

    fun tokenize(text: String): List<Token> =
        word.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()

    /**
     * Word-level diff (longest common subsequence). Long pages are compared in windows of
     * [window] words to keep memory small on phones.
     */
    fun diff(a: List<Token>, b: List<Token>, window: Int = 1500): List<DiffPiece> {
        if (a.size <= window && b.size <= window) return merge(lcs(a, b))
        val out = ArrayList<Pair<DiffOp, Pair<Token?, Token?>>>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            val ea = minOf(a.size, i + window)
            val eb = minOf(b.size, j + window)
            out += lcs(a.subList(i, ea), b.subList(j, eb))
            i = ea
            j = eb
        }
        return merge(out)
    }

    private fun lcs(a: List<Token>, b: List<Token>): List<Pair<DiffOp, Pair<Token?, Token?>>> {
        val n = a.size
        val m = b.size
        // Lengths of the LCS of suffixes, two rows at a time; directions kept in a byte table.
        val dir = ByteArray((n + 1) * (m + 1))
        var next = IntArray(m + 1)
        var cur = IntArray(m + 1)
        for (x in n - 1 downTo 0) {
            for (y in m - 1 downTo 0) {
                val idx = x * (m + 1) + y
                if (a[x].text == b[y].text) {
                    cur[y] = next[y + 1] + 1
                    dir[idx] = 0 // diagonal
                } else if (next[y] >= cur[y + 1]) {
                    cur[y] = next[y]
                    dir[idx] = 1 // skip a[x] → removed
                } else {
                    cur[y] = cur[y + 1]
                    dir[idx] = 2 // skip b[y] → added
                }
            }
            val t = next; next = cur; cur = t
            cur.fill(0)
        }
        val out = ArrayList<Pair<DiffOp, Pair<Token?, Token?>>>(n + m)
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when (dir[x * (m + 1) + y].toInt()) {
                0 -> { out += DiffOp.SAME to (a[x] to b[y]); x++; y++ }
                1 -> { out += DiffOp.REMOVED to (a[x] to null); x++ }
                else -> { out += DiffOp.ADDED to (null to b[y]); y++ }
            }
        }
        while (x < n) { out += DiffOp.REMOVED to (a[x] to null); x++ }
        while (y < m) { out += DiffOp.ADDED to (null to b[y]); y++ }
        return out
    }

    private fun merge(steps: List<Pair<DiffOp, Pair<Token?, Token?>>>): List<DiffPiece> {
        val out = ArrayList<DiffPiece>()
        var i = 0
        while (i < steps.size) {
            val op = steps[i].first
            var j = i
            while (j < steps.size && steps[j].first == op) j++
            val run = steps.subList(i, j)
            val aTokens = run.mapNotNull { it.second.first }
            val bTokens = run.mapNotNull { it.second.second }
            val text = (if (op == DiffOp.ADDED) bTokens else aTokens).joinToString(" ") { it.text }
            out += DiffPiece(
                op, text,
                aTokens.takeIf { it.isNotEmpty() }?.let { it.first().start until it.last().endExclusive },
                bTokens.takeIf { it.isNotEmpty() }?.let { it.first().start until it.last().endExclusive },
            )
            i = j
        }
        return out
    }
}
