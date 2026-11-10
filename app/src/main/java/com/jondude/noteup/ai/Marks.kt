package com.jondude.noteup.ai

/** Turns the ★ marks made during a recording into lines the AI can use as "this mattered". */
object Marks {
    /** [lines] are (startMs, text) of the transcript. Each mark picks up what was said from just before it to a few seconds after. */
    fun context(marks: List<Long>, lines: List<Pair<Long, String>>, before: Long = 15_000, after: Long = 5_000): String {
        if (marks.isEmpty()) return ""
        val sorted = lines.sortedBy { it.first }
        val seen = HashSet<Int>()
        val out = ArrayList<String>()
        for (m in marks.sorted()) {
            val picked = sorted.withIndex().filter { (i, l) -> l.first in (m - before)..(m + after) && i !in seen }
            picked.forEach { seen.add(it.index) }
            val said = picked.joinToString(" ") { it.value.second }.trim()
            out.add("[${stamp(m)}] ${if (said.isEmpty()) "(starred, nothing transcribed nearby)" else said}")
        }
        return out.joinToString("\n")
    }

    fun stamp(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** True if a transcript line (start..end) is within a few seconds of a mark. */
    fun near(marks: List<Long>, startMs: Long, endMs: Long, slack: Long = 3_000): Boolean =
        marks.any { it in (startMs - slack)..(endMs + slack) }
}
