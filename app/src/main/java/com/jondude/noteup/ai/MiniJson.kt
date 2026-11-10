package com.jondude.noteup.ai

/** Tiny JSON reader for AI replies (pure Kotlin so it can be tested off-device). Values: Map, List, String, Double, Boolean, null. */
object MiniJson {
    fun parse(text: String): Any? = try { P(text).run { ws(); val v = value(); v } } catch (e: Exception) { null }

    /** Finds the first {...} or [...] in a reply that may have prose or code fences around it. */
    fun extract(reply: String): String? {
        val s = reply.indexOfFirst { it == '{' || it == '[' }
        if (s < 0) return null
        val open = reply[s]; val close = if (open == '{') '}' else ']'
        var depth = 0; var inStr = false; var esc = false
        for (i in s until reply.length) {
            val c = reply[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            if (c == '"') inStr = true else if (c == open) depth++ else if (c == close) { depth--; if (depth == 0) return reply.substring(s, i + 1) }
        }
        return null
    }

    fun parseReply(reply: String): Any? = extract(reply)?.let { parse(it) }

    @Suppress("UNCHECKED_CAST") fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST") fun list(v: Any?): List<Any?>? = v as? List<Any?>
    fun str(v: Any?): String? = when (v) { is String -> v; is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString(); is Boolean -> v.toString(); else -> null }
    fun int(v: Any?): Int? = when (v) { is Double -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad json")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); if (s[i] != ':') throw IllegalStateException(); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw IllegalStateException()
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw IllegalStateException()
            }
        }
        fun str(): String {
            if (s[i] != '"') throw IllegalStateException(); i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(s[i])
                i++
            }
            i++; return sb.toString()
        }
        fun num(): Double {
            val st = i; i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(st, i).toDouble()
        }
    }
}

class Card(val q: String, val a: String)
class Quiz(val q: String, val options: List<String>, val answer: Int, val why: String)

/** Turns AI replies into checked study material. Anything malformed is dropped rather than shown broken. */
object Study {
    fun cards(reply: String): List<Card> {
        val root = MiniJson.parseReply(reply)
        val arr = MiniJson.list(MiniJson.map(root)?.get("cards")) ?: MiniJson.list(root) ?: return emptyList()
        return arr.mapNotNull { MiniJson.map(it) }.mapNotNull { m ->
            val q = MiniJson.str(m["q"])?.trim().orEmpty(); val a = MiniJson.str(m["a"])?.trim().orEmpty()
            if (q.isNotEmpty() && a.isNotEmpty()) Card(q, a) else null
        }
    }

    fun quiz(reply: String): List<Quiz> {
        val root = MiniJson.parseReply(reply)
        val arr = MiniJson.list(MiniJson.map(root)?.get("questions")) ?: MiniJson.list(root) ?: return emptyList()
        return arr.mapNotNull { MiniJson.map(it) }.mapNotNull { m ->
            val q = MiniJson.str(m["q"])?.trim().orEmpty()
            val opts = MiniJson.list(m["options"])?.mapNotNull { MiniJson.str(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
            val ans = MiniJson.int(m["answer"])
            if (q.isEmpty() || opts.size < 2 || opts.distinct().size != opts.size || ans == null || ans !in opts.indices) null
            else Quiz(q, opts, ans, MiniJson.str(m["why"])?.trim().orEmpty())
        }
    }

    fun tree(reply: String, maxDepth: Int = 3, maxKids: Int = 7): Tree? {
        fun build(v: Any?, depth: Int): Tree? {
            val m = MiniJson.map(v) ?: return null
            val t = MiniJson.str(m["text"])?.trim().orEmpty().take(90)
            if (t.isEmpty()) return null
            val kids = if (depth >= maxDepth) emptyList() else MiniJson.list(m["kids"]).orEmpty().mapNotNull { build(it, depth + 1) }.take(maxKids)
            return Tree(t, kids)
        }
        return build(MiniJson.parseReply(reply), 0)?.takeIf { it.kids.isNotEmpty() }
    }

    fun outline(t: Tree, depth: Int = 0): String =
        "  ".repeat(depth) + (if (depth == 0) "" else "- ") + t.text + "\n" + t.kids.joinToString("") { outline(it, depth + 1) }

    /** Columns and rows, every row padded or cut to the column count. */
    fun table(reply: String, maxCols: Int = 6): Pair<List<String>, List<List<String>>>? {
        val m = MiniJson.map(MiniJson.parseReply(reply)) ?: return null
        val cols = MiniJson.list(m["columns"])?.mapNotNull { MiniJson.str(it)?.trim() }?.take(maxCols).orEmpty()
        if (cols.isEmpty()) return null
        val rows = MiniJson.list(m["rows"])?.mapNotNull { r -> MiniJson.list(r)?.let { cells -> List(cols.size) { i -> MiniJson.str(cells.getOrNull(i))?.trim().orEmpty() } } }.orEmpty()
            .filter { r -> r.any { it.isNotEmpty() } }
        return if (rows.isEmpty()) null else cols to rows
    }
}
