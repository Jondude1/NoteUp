package com.jondude.noteup.ai

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Drawing primitives in page units (1000 x 1414), so generated diagrams can be put on a page as ink and text. */
class Box(val x: Float, val y: Float, val w: Float, val h: Float, val text: String, val size: Float, val bordered: Boolean = true, val centered: Boolean = true, val bold: Boolean = false)
class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
class Drawing(val boxes: List<Box>, val lines: List<Line>)

class Tree(val text: String, val kids: List<Tree> = emptyList())

private fun linesFor(text: String, widthUnits: Float, size: Float): Int {
    val perLine = max(4, (widthUnits / (size * 0.52f)).toInt())
    return max(1, text.split('\n').sumOf { ceil(it.length / perLine.toFloat()).toInt().coerceAtLeast(1) })
}

/** Mind map: root on the left, branches fanning right, leaves stacked evenly. */
object MindLayout {
    const val PAGE_W = 1000f
    const val PAGE_H = 1414f

    fun depthOf(t: Tree, cap: Int = 3): Int = if (t.kids.isEmpty() || cap == 0) 0 else 1 + t.kids.maxOf { depthOf(it, cap - 1) }
    private fun leavesOf(t: Tree, cap: Int): Int = if (t.kids.isEmpty() || cap == 0) 1 else t.kids.sumOf { leavesOf(it, cap - 1) }

    fun layout(root: Tree): Drawing {
        val d = min(3, depthOf(root))
        val gap = 50f
        val margin = 40f
        val boxW = min(260f, (PAGE_W - 2 * margin - d * gap) / (d + 1))
        val leaves = leavesOf(root, d)
        val top = 90f
        val slot = min(110f, (PAGE_H - top - 60f) / leaves)
        val boxes = ArrayList<Box>()
        val lines = ArrayList<Line>()
        var next = 0

        // returns the vertical centre of the placed node
        fun place(t: Tree, depth: Int): Float {
            val kids = if (depth >= d) emptyList() else t.kids
            val cy = if (kids.isEmpty()) top + slot * (next++ + 0.5f) else {
                val ys = kids.map { place(it, depth + 1) }
                ys.average().toFloat()
            }
            val size = if (depth == 0) 22f else 18f
            val h = min(slot - 6f, size * 1.25f * linesFor(t.text, boxW - 16f, size) + 14f).coerceAtLeast(34f)
            val x = margin + depth * (boxW + gap)
            boxes.add(Box(x, cy - h / 2, boxW, h, t.text, size, bordered = true, bold = depth == 0))
            for (k in kids) {
                val kb = boxes.first { it.text == k.text && it.x == x + boxW + gap && kotlin.math.abs((it.y + it.h / 2) - cyOf(boxes, k, x + boxW + gap)) < 0.01f }
                lines.add(Line(x + boxW, cy, kb.x, kb.y + kb.h / 2))
            }
            return cy
        }
        place(root, 0)
        return Drawing(boxes, lines)
    }

    private fun cyOf(boxes: List<Box>, t: Tree, x: Float): Float =
        boxes.filter { it.text == t.text && it.x == x }.firstOrNull()?.let { it.y + it.h / 2 } ?: 0f
}

/** A table as grid lines and cell text, split across pages when it is too tall. */
object TableLayout {
    fun layout(columns: List<String>, rows: List<List<String>>, pageH: Float = 1414f): List<Drawing> {
        val cols = columns.take(6)
        if (cols.isEmpty()) return emptyList()
        val n = cols.size
        val left = 40f
        val width = 920f
        val colW = width / n
        val size = if (n <= 3) 18f else 15f
        fun rowH(cells: List<String>, bold: Boolean): Float =
            max(34f, size * 1.25f * (0 until n).maxOf { linesFor(cells.getOrElse(it) { "" }, colW - 14f, size) } + 14f)

        val pages = ArrayList<Drawing>()
        var boxes = ArrayList<Box>()
        var lines = ArrayList<Line>()
        var y = 70f
        var rowCount = 0

        fun closePage(top: Float) {
            // vertical rules for the page's rows
            for (i in 0..n) lines.add(Line(left + i * colW, top, left + i * colW, y))
            pages.add(Drawing(boxes, lines)); boxes = ArrayList(); lines = ArrayList()
        }
        fun addRow(cells: List<String>, bold: Boolean) {
            val h = rowH(cells, bold)
            for (c in 0 until n) boxes.add(Box(left + c * colW + 6f, y + 6f, colW - 12f, h - 10f, cells.getOrElse(c) { "" }, size, bordered = false, centered = false, bold = bold))
            lines.add(Line(left, y, left + width, y))
            y += h
            rowCount++
        }
        var pageTop = y
        addRow(cols, true)
        for (r in rows) {
            val h = rowH(r, false)
            if (y + h > pageH - 60f) {
                lines.add(Line(left, y, left + width, y))
                closePage(pageTop)
                y = 70f; pageTop = y
                addRow(cols, true)
            }
            addRow(r, false)
        }
        lines.add(Line(left, y, left + width, y))
        closePage(pageTop)
        return pages
    }
}

object Csv {
    private fun cell(s: String): String = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    fun encode(columns: List<String>, rows: List<List<String>>): String =
        (listOf(columns) + rows).joinToString("\n") { r -> r.joinToString(",") { cell(it) } }
}

/** A one-page visual summary: title, a row of big numbers, boxed sections and a takeaway. */
object InfoLayout {
    class Stat(val value: String, val label: String)
    class Section(val heading: String, val points: List<String>)

    fun layout(title: String, subtitle: String, stats: List<Stat>, sections: List<Section>, takeaway: String): Drawing {
        val boxes = ArrayList<Box>()
        val lines = ArrayList<Line>()
        var y = 60f
        boxes.add(Box(40f, y, 920f, 50f, title, 34f, bordered = false, bold = true)); y += 56f
        if (subtitle.isNotBlank()) { boxes.add(Box(40f, y, 920f, 30f, subtitle, 18f, bordered = false)); y += 44f }
        y += 14f
        val st = stats.take(4)
        if (st.isNotEmpty()) {
            val gap = 16f
            val w = (920f - gap * (st.size - 1)) / st.size
            st.forEachIndexed { i, s ->
                val x = 40f + i * (w + gap)
                boxes.add(Box(x, y, w, 150f, "", 10f, bordered = true))
                boxes.add(Box(x + 8f, y + 18f, w - 16f, 56f, s.value, 40f, bordered = false, bold = true))
                boxes.add(Box(x + 8f, y + 84f, w - 16f, 56f, s.label, 16f, bordered = false))
            }
            y += 150f + 30f
        }
        val secs = sections.take(4)
        val colW = (920f - 20f) / 2f
        var i = 0
        while (i < secs.size) {
            val pair = secs.drop(i).take(2)
            val hs = pair.map { s ->
                val bullets = s.points.take(5).joinToString("\n") { "• $it" }
                28f + 22f * 1.3f + 17f * 1.3f * linesFor(bullets, colW - 24f, 17f) + 20f
            }
            val rowH = hs.max()
            pair.forEachIndexed { k, s ->
                val x = 40f + k * (colW + 20f)
                val bullets = s.points.take(5).joinToString("\n") { "• $it" }
                boxes.add(Box(x, y, colW, rowH, "", 10f, bordered = true))
                boxes.add(Box(x + 12f, y + 10f, colW - 24f, 30f, s.heading, 22f, bordered = false, centered = false, bold = true))
                boxes.add(Box(x + 12f, y + 48f, colW - 24f, rowH - 56f, bullets, 17f, bordered = false, centered = false))
            }
            y += rowH + 20f
            i += 2
        }
        if (takeaway.isNotBlank()) {
            val h = 40f + 22f * 1.3f * linesFor(takeaway, 880f, 22f)
            boxes.add(Box(40f, y, 920f, h, takeaway, 22f, bordered = true, bold = true))
            y += h
        }
        return Drawing(boxes, lines)
    }
}

/** A flashcard or quiz run: missed items come back later until each is answered right. */
class StudyRun(private val total: Int, private val maxRepeats: Int = 2) {
    private val queue = ArrayDeque((0 until total).toList())
    private val repeats = HashMap<Int, Int>()
    val missed = LinkedHashSet<Int>()
    var firstTryRight = 0
        private set
    private val seen = HashSet<Int>()

    val current: Int? get() = queue.firstOrNull()
    val done: Boolean get() = queue.isEmpty()
    val remaining: Int get() = queue.size

    fun answer(correct: Boolean) {
        val i = queue.removeFirstOrNull() ?: return
        val first = seen.add(i)
        if (correct) { if (first) firstTryRight++ }
        else {
            missed.add(i)
            val r = repeats.getOrDefault(i, 0)
            if (r < maxRepeats) { repeats[i] = r + 1; queue.addLast(i) }
        }
    }
}
