package com.jondude.noteup.ink

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.PageText
import com.jondude.noteup.data.Store
import com.jondude.noteup.data.Stroke
import com.jondude.noteup.ai.Twists
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Circle-and-write commands: a closed loop drawn around existing ink. */
object InkCommands {

    /** If [loop] is a closed loop around other ink, returns the ids it encloses; else empty. */
    fun enclosedBy(loop: Stroke, all: List<Stroke>): Set<String> {
        if (loop.marker) return emptySet()
        val n = loop.pts.size / 3
        if (n < 12) return emptySet()
        var len = 0f
        for (i in 1 until n) len += hypot(loop.pts[i * 3] - loop.pts[(i - 1) * 3], loop.pts[i * 3 + 1] - loop.pts[(i - 1) * 3 + 1])
        val gap = hypot(loop.pts[0] - loop.pts[(n - 1) * 3], loop.pts[1] - loop.pts[(n - 1) * 3 + 1])
        val b = InkView.boundsOf(listOf(loop))
        if (len < 80f || gap > max(45f, len * 0.22f) || b.width() < 20f || b.height() < 14f) return emptySet()
        val path = Path()
        path.moveTo(loop.pts[0], loop.pts[1])
        for (i in 1 until n) path.lineTo(loop.pts[i * 3], loop.pts[i * 3 + 1])
        path.close()
        val region = Region()
        region.setPath(path, Region(0, 0, PAGE_W.toInt() + 1, PAGE_H.toInt() + 1))
        return all.filter { s ->
            if (s.id == loop.id || s.marker) return@filter false
            val m = s.pts.size / 3
            var inside = 0
            var j = 0
            while (j < s.pts.size) { if (region.contains(s.pts[j].toInt(), s.pts[j + 1].toInt())) inside++; j += 3 }
            m > 0 && inside * 10 >= m * 7
        }.map { it.id }.toSet()
    }

    /** Gap between two rectangles (0 if they overlap). */
    fun gap(a: RectF, b: RectF): Float {
        val dx = max(0f, max(a.left - b.right, b.left - a.right))
        val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
        return hypot(dx, dy)
    }
}

/** Builds clean strokes and labels from the AI's JSON description of a sketched diagram. */
object DiagramBuilder {
    private class Node(val id: String, val shape: String, val r: RectF, val label: String)

    fun build(reply: String, src: RectF, scale: Float, penWidth: Float): Pair<List<Stroke>, List<PageText>> {
        val json = Twists.jsonObject(reply) ?: throw IllegalStateException("The AI didn't describe the diagram. Try a clearer selection.")
        val root = Store.json.parseToJsonElement(json).jsonObject
        fun f(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.floatOrNull ?: 0f
        fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content.orEmpty()

        val nodes = root["nodes"]?.jsonArray.orEmpty().map { el ->
            val o = el.jsonObject
            val l = src.left + f(o, "x") / scale
            val t = src.top + f(o, "y") / scale
            Node(str(o, "id"), str(o, "shape").lowercase(), RectF(l, t, l + max(10f, f(o, "w") / scale), t + max(10f, f(o, "h") / scale)), str(o, "label"))
        }
        if (nodes.isEmpty()) throw IllegalStateException("No shapes found in the selection.")
        val byId = nodes.associateBy { it.id }
        val strokes = ArrayList<Stroke>()
        val texts = ArrayList<PageText>()
        fun stroke(pts: FloatArray) = Stroke(pts = pts, times = LongArray(pts.size / 3) { it * 8L }, t0 = System.currentTimeMillis(), width = penWidth)

        for (n in nodes) {
            val r = n.r
            when (n.shape) {
                "rect", "rectangle", "box" -> strokes += stroke(ShapeSnap.rect(r.left, r.top, r.right, r.bottom))
                "ellipse", "circle", "oval" -> strokes += stroke(ShapeSnap.ellipse(r.centerX(), r.centerY(), r.width() / 2, r.height() / 2))
                "diamond" -> strokes += stroke(ShapeSnap.diamond(r.centerX(), r.centerY(), r.width() / 2, r.height() / 2))
            }
            if (n.label.isNotBlank()) {
                val size = (r.height() * 0.32f).coerceIn(15f, 26f)
                texts += PageText(x = r.left + r.width() * 0.05f, y = r.centerY(), w = max(60f, r.width() * 0.9f), text = n.label, size = size)
            }
        }
        for (el in root["edges"]?.jsonArray.orEmpty()) {
            val o = el.jsonObject
            val a = byId[str(o, "from")] ?: continue
            val b = byId[str(o, "to")] ?: continue
            val (x1, y1) = edgePoint(a, b.r.centerX(), b.r.centerY())
            val (x2, y2) = edgePoint(b, a.r.centerX(), a.r.centerY())
            strokes += stroke(ShapeSnap.line(x1, y1, x2, y2))
            if (o["arrow"]?.jsonPrimitive?.booleanOrNull != false) strokes += stroke(ShapeSnap.arrowHead(x1, y1, x2, y2))
            val label = str(o, "label")
            if (label.isNotBlank()) texts += PageText(x = (x1 + x2) / 2 - 60f, y = (y1 + y2) / 2 - 14f, w = 120f, text = label, size = 15f)
        }
        return strokes to texts
    }

    /** Where the line toward (tx,ty) leaves the node's outline. */
    private fun edgePoint(n: Node, tx: Float, ty: Float): Pair<Float, Float> {
        val cx = n.r.centerX(); val cy = n.r.centerY()
        val dx = tx - cx; val dy = ty - cy
        if (abs(dx) < 1e-3f && abs(dy) < 1e-3f) return cx to cy
        val hw = n.r.width() / 2 + 4f; val hh = n.r.height() / 2 + 4f
        val t = when (n.shape) {
            "ellipse", "circle", "oval" -> 1f / hypot(dx / hw, dy / hh)
            "diamond" -> 1f / (abs(dx) / hw + abs(dy) / hh)
            else -> min(if (dx != 0f) hw / abs(dx) else Float.MAX_VALUE, if (dy != 0f) hh / abs(dy) else Float.MAX_VALUE)
        }
        return (cx + dx * t) to (cy + dy * t)
    }
}
