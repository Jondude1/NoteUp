package com.jondude.noteup.data

/**
 * Page outline logic, on plain ids so it can be tested without Android.
 * [ids] is the page order (a page is followed by its sub-pages); [parents] maps page -> parent page.
 */
object Outline {
    class Result(val ids: List<String>, val parents: Map<String, String>)

    private fun parentOf(ids: List<String>, parents: Map<String, String>, p: String) = parents[p]?.takeIf { it in ids }

    fun depth(ids: List<String>, parents: Map<String, String>, p: String): Int {
        var d = 0
        var cur = parentOf(ids, parents, p)
        while (cur != null && d < 12) { d++; cur = parentOf(ids, parents, cur) }
        return d
    }

    private fun isDescendant(ids: List<String>, parents: Map<String, String>, p: String, anc: String): Boolean {
        var cur = parentOf(ids, parents, p)
        var n = 0
        while (cur != null && n < 12) { if (cur == anc) return true; cur = parentOf(ids, parents, cur); n++ }
        return false
    }

    /** Index just past the last sub-page (at any depth) of the page at [index]. */
    fun subtreeEnd(ids: List<String>, parents: Map<String, String>, index: Int): Int {
        var i = index + 1
        while (i < ids.size && isDescendant(ids, parents, ids[i], ids[index])) i++
        return i
    }

    /** New page [id] as the last sub-page of [parent]. */
    fun addSub(ids: List<String>, parents: Map<String, String>, parent: String, id: String): Result {
        val at = ids.indexOf(parent).takeIf { it >= 0 } ?: ids.lastIndex
        val out = ids.toMutableList()
        out.add(subtreeEnd(ids, parents, at), id)
        return Result(out, parents + (id to ids[at]))
    }

    /** Page becomes a sub-page of the page just above it at its level; null if there is none. */
    fun indent(ids: List<String>, parents: Map<String, String>, p: String): Result? {
        val i = ids.indexOf(p)
        if (i <= 0) return null
        val mine = parentOf(ids, parents, p)
        for (j in i - 1 downTo 0) {
            val c = ids[j]
            if (parentOf(ids, parents, c) == mine) return Result(ids, parents + (p to c))
            if (depth(ids, parents, c) < depth(ids, parents, p)) return null
        }
        return null
    }

    /** Page (with its sub-pages) moves up a level, to just after its old parent's branch. */
    fun outdent(ids: List<String>, parents: Map<String, String>, p: String): Result? {
        val parent = parentOf(ids, parents, p) ?: return null
        val i = ids.indexOf(p)
        val block = ids.subList(i, subtreeEnd(ids, parents, i)).toList()
        val rest = ids - block.toSet()
        val out = rest.toMutableList()
        out.addAll(subtreeEnd(rest, parents, rest.indexOf(parent)), block)
        val np = parents.toMutableMap()
        val grand = parentOf(ids, parents, parent)
        if (grand != null) np[p] = grand else np.remove(p)
        return Result(out, np)
    }

    /** Page removed; its sub-pages move up a level. */
    fun remove(ids: List<String>, parents: Map<String, String>, p: String): Result {
        val up = parentOf(ids, parents, p)
        val np = parents.toMutableMap()
        np.remove(p)
        for ((k, v) in parents) if (v == p) { if (up != null) np[k] = up else np.remove(k) }
        return Result(ids - p, np)
    }

    /** Swap the page's branch with the neighbouring branch at the same level. dir -1 = up, +1 = down. Null if it can't move. */
    fun move(ids: List<String>, parents: Map<String, String>, p: String, dir: Int): Result? {
        val i = ids.indexOf(p)
        if (i < 0) return null
        val mine = parentOf(ids, parents, p)
        val block = ids.subList(i, subtreeEnd(ids, parents, i)).toList()
        val rest = ids - block.toSet()
        val out = rest.toMutableList()
        if (dir < 0) {
            // nearest earlier page at the same level
            var target = -1
            for (j in i - 1 downTo 0) {
                if (parentOf(ids, parents, ids[j]) == mine) { target = j; break }
                if (parentOf(ids, parents, ids[j]) == null && mine != null) break
            }
            if (target < 0) return null
            out.addAll(rest.indexOf(ids[target]), block)
        } else {
            val end = subtreeEnd(ids, parents, i)
            if (end >= ids.size || parentOf(ids, parents, ids[end]) != mine) return null
            val nextAt = rest.indexOf(ids[end])
            out.addAll(subtreeEnd(rest, parents, nextAt), block)
        }
        return Result(out, parents)
    }
}
