package com.jondude.noteup.ai

/** Numbered citations like [3] or [2, 5] in an AI answer. */
object Cites {
    private val group = Regex("""\[(\d{1,3}(?:\s*[,;]\s*\d{1,3})*)]""")

    /** The citation numbers used, once each, in order of first appearance. */
    fun numbers(answer: String): List<Int> {
        val out = LinkedHashSet<Int>()
        for (m in group.findAll(answer)) m.groupValues[1].split(',', ';').mapNotNull { it.trim().toIntOrNull() }.forEach { out.add(it) }
        return out.toList()
    }

    /** Numbered excerpts ready to paste into a prompt: "[1] label\ntext". */
    fun format(chunks: List<Triple<Int, String, String>>): String =
        chunks.joinToString("\n\n") { (n, label, text) -> "[$n] $label\n$text" }
}

/** Minimal web page to plain text, for adding a link as a source. */
object Html {
    fun title(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            ?.groupValues?.get(1)?.let { decode(it).trim().replace(Regex("\\s+"), " ") }?.takeIf { it.isNotEmpty() }

    fun toText(html: String): String {
        var s = html
        for (tag in listOf("script", "style", "noscript", "svg", "head", "nav", "footer", "form", "iframe")) {
            s = s.replace(Regex("<$tag[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE), " ")
        }
        s = s.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        s = s.replace(Regex("</?(p|div|br|li|tr|h[1-6]|section|article|blockquote|ul|ol|table)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decode(s)
        return s.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun decode(t: String): String = t
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&rsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"")
        .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        .replace(Regex("&#(\\d{1,5});")) { m -> m.groupValues[1].toInt().toChar().toString() }
}

/** Splits long text into page-sized pieces (for notes saved onto pages, and for numbering source excerpts). */
object Splitter {
    fun split(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur = StringBuilder() }
        for (line in text.lines()) {
            var l = line
            while (l.length > maxChars) {
                // break an overlong line at a space near the limit
                val at = l.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
                if (cur.isNotEmpty()) flush()
                out.add(l.substring(0, at).trim()); l = l.substring(at).trimStart()
            }
            if (cur.length + l.length + 1 > maxChars) flush()
            cur.append(l).append('\n')
        }
        flush()
        return out
    }
}
