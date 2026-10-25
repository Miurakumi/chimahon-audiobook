package chimahon.novel.audiobook

/** One subtitle line: text plus its time span in the audiobook, in milliseconds. */
data class SrtLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    /** Dialogue lines in Japanese novels start with a quote bracket. */
    val isQuote: Boolean
        get() = text.firstOrNull()?.let { it in QUOTE_OPENERS } == true

    private companion object {
        const val QUOTE_OPENERS = "「『（(“\"〈《【"
    }
}

object SrtParser {

    private val TIME_LINE = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""",
    )
    private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val ASS_TAG = Regex("""\{\\[^}]*\}""")

    /**
     * Parses SRT text. Tolerant on purpose: BOM, CRLF, missing or non-numeric
     * counters, "." instead of "," in times, styling tags. Blocks without a valid
     * time line are skipped. Result is sorted by start time and re-indexed from 0.
     */
    fun parse(raw: String): List<SrtLine> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val result = ArrayList<SrtLine>()

        for (block in text.split(Regex("\n{2,}"))) {
            val rows = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (rows.isEmpty()) continue

            val timeRowIndex = rows.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeRowIndex < 0) continue
            val match = TIME_LINE.find(rows[timeRowIndex]) ?: continue
            val g = match.groupValues

            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])

            val content = rows.drop(timeRowIndex + 1)
                .joinToString("") { clean(it) }
                .trim()
            if (content.isEmpty() || end <= start) continue

            result += SrtLine(index = 0, startMs = start, endMs = end, text = content)
        }

        return result
            .sortedBy { it.startMs }
            .mapIndexed { i, line -> line.copy(index = i) }
    }

    private fun clean(row: String): String =
        row.replace(HTML_TAG, "").replace(ASS_TAG, "").trim()

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        // "5" means 500 ms, "05" means 50 ms: pad on the right to 3 digits.
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + millis
    }
}
