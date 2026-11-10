package com.jondude.noteup.ai

/**
 * Chooses which excerpts fit in the AI's reading budget. With many sources the old first-come approach let the first few
 * crowd out the rest; this spreads the budget across every group and, when there is a question, favours matching excerpts.
 */
object Retrieve {
    class Item(val group: String, val text: String)

    private val stop = setOf("the", "and", "for", "are", "but", "not", "you", "with", "that", "this", "what", "which", "how", "does", "from", "have", "has", "was", "were", "can", "will", "about", "into", "than", "then", "there", "their", "they", "your", "all", "any", "who", "why", "when", "where", "would", "could", "should")

    fun terms(q: String): List<String> =
        Regex("[A-Za-z0-9][A-Za-z0-9'-]{2,}").findAll(q.lowercase()).map { it.value }.filter { it !in stop }.distinct().toList()

    fun score(text: String, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val t = text.lowercase()
        var s = 0
        for (w in terms) {
            var i = t.indexOf(w); var n = 0
            while (i >= 0 && n < 5) { n++; i = t.indexOf(w, i + w.length) }
            if (n > 0) s += 3 + n
        }
        return s
    }

    /** Returns (original index, text to use) in original order. */
    fun select(items: List<Item>, query: String?, maxChars: Int): List<Pair<Int, String>> {
        if (items.sumOf { it.text.length } <= maxChars) return items.mapIndexed { i, it -> i to it.text }
        val chosen = LinkedHashMap<Int, String>()
        var used = 0
        fun take(i: Int, text: String): Boolean {
            if (i in chosen || used + text.length > maxChars) return false
            chosen[i] = text; used += text.length; return true
        }
        val groups = items.indices.groupBy { items[it].group }
        // 1. best matches for the question (at most 6 per group), up to 60% of the budget
        val terms = query?.let { terms(it) }.orEmpty()
        if (terms.isNotEmpty()) {
            val perGroup = HashMap<String, Int>()
            val ranked = items.indices.map { it to score(items[it].text, terms) }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            for ((i, _) in ranked) {
                if (used >= maxChars * 6 / 10) break
                val g = items[i].group
                if (perGroup.getOrDefault(g, 0) >= 6) continue
                if (take(i, items[i].text)) perGroup[g] = perGroup.getOrDefault(g, 0) + 1
            }
        }
        // 2. every group gets a share: round-robin, each excerpt trimmed to an even allowance
        val allow = (maxChars / groups.size.coerceAtLeast(1)).coerceIn(600, 3000)
        val cursors = groups.mapValues { it.value.toMutableList() }
        var progress = true
        while (progress && used < maxChars) {
            progress = false
            for ((_, idxs) in cursors) {
                val i = idxs.firstOrNull { it !in chosen } ?: continue
                val text = items[i].text.let { if (it.length > allow) it.take(allow) else it }
                if (take(i, text)) progress = true else idxs.remove(i)
            }
        }
        return chosen.entries.sortedBy { it.key }.map { it.key to it.value }
    }
}
