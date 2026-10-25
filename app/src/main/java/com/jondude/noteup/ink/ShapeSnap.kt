package com.jondude.noteup.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/**
 * Turns a hand-drawn stroke into a clean shape: line, triangle, rectangle, ellipse,
 * or a straightened polyline (arrows, brackets). Input/output are packed x,y,pressure triples.
 * Returns null when the stroke doesn't look like a shape.
 */
object ShapeSnap {
    private data class P(val x: Float, val y: Float)

    fun snap(packed: FloatArray): FloatArray? {
        val pts = (0 until packed.size / 3).map { P(packed[it * 3], packed[it * 3 + 1]) }
        if (pts.size < 6) return null
        var length = 0f
        for (i in 1 until pts.size) length += dist(pts[i - 1], pts[i])
        if (length < 30f) return null
        val first = pts.first()
        val last = pts.last()
        val gap = dist(first, last)

        // straight line
        if (gap / length > 0.93f) {
            var end = last
            val ang = atan2(end.y - first.y, end.x - first.x)
            val deg = Math.toDegrees(ang.toDouble())
            // snap to horizontal / vertical / 45° when close
            for (target in listOf(0.0, 45.0, 90.0, 135.0, 180.0, -45.0, -90.0, -135.0, -180.0)) {
                if (abs(deg - target) < 6) {
                    val r = Math.toRadians(target)
                    end = P(first.x + gap * cos(r).toFloat(), first.y + gap * sin(r).toFloat())
                    break
                }
            }
            return pack(densify(listOf(first, end)))
        }

        val closed = gap < length * 0.18f
        val corners = simplify(pts, length * 0.035f)

        if (closed) {
            val poly = corners.dropLast(1).let { if (it.size >= 2 && dist(it.first(), it.last()) < length * 0.08f) it.dropLast(1) else it }
            val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
            return when {
                poly.size == 3 -> pack(densify(poly + poly.first()))
                poly.size == 4 -> {
                    // mostly axis-aligned → clean rectangle; otherwise keep the four corners
                    val axis = poly.zipWithNext().all { (a, b) -> abs(a.x - b.x) < 0.25f * abs(a.y - b.y) + 8f || abs(a.y - b.y) < 0.25f * abs(a.x - b.x) + 8f }
                    val q = if (axis) listOf(P(minX, minY), P(maxX, minY), P(maxX, maxY), P(minX, maxY)) else poly
                    pack(densify(q + q.first()))
                }
                else -> {
                    if (!looksElliptical(pts, minX, maxX, minY, maxY)) return null
                    val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
                    var rx = (maxX - minX) / 2; var ry = (maxY - minY) / 2
                    if (abs(rx - ry) < max(rx, ry) * 0.12f) { val r = (rx + ry) / 2; rx = r; ry = r } // near-circle → circle
                    val ring = (0..72).map { k ->
                        val t = 2 * PI * k / 72
                        P(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat())
                    }
                    pack(ring)
                }
            }
        }

        // open polyline with a few clear segments (arrow, bracket, zig)
        if (corners.size in 3..5) return pack(densify(corners))
        return null
    }

    private fun looksElliptical(pts: List<P>, minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean {
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val rx = max(1f, (maxX - minX) / 2); val ry = max(1f, (maxY - minY) / 2)
        val err = pts.map { abs(hypot((it.x - cx) / rx, (it.y - cy) / ry) - 1f) }.average()
        return err < 0.16
    }

    /** Ramer–Douglas–Peucker. */
    private fun simplify(pts: List<P>, eps: Float): List<P> {
        if (pts.size < 3) return pts
        var idx = 0
        var dmax = 0f
        for (i in 1 until pts.size - 1) {
            val d = lineDist(pts[i], pts.first(), pts.last())
            if (d > dmax) { dmax = d; idx = i }
        }
        if (dmax <= eps) {
            // closed loops have first≈last, so split at the farthest point instead
            if (dist(pts.first(), pts.last()) < eps) {
                var far = 0; var fd = 0f
                for (i in pts.indices) { val d = dist(pts[i], pts.first()); if (d > fd) { fd = d; far = i } }
                if (far in 1 until pts.size - 1) {
                    val a = simplify(pts.subList(0, far + 1), eps)
                    val b = simplify(pts.subList(far, pts.size), eps)
                    return a.dropLast(1) + b
                }
            }
            return listOf(pts.first(), pts.last())
        }
        val a = simplify(pts.subList(0, idx + 1), eps)
        val b = simplify(pts.subList(idx, pts.size), eps)
        return a.dropLast(1) + b
    }

    private fun lineDist(p: P, a: P, b: P): Float {
        val len = dist(a, b)
        if (len < 1e-3f) return dist(p, a)
        return abs((b.y - a.y) * p.x - (b.x - a.x) * p.y + b.x * a.y - b.y * a.x) / len
    }

    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    /** Adds points every few units so the eraser and lasso can hit long straight edges. */
    private fun densify(poly: List<P>): List<P> {
        val out = ArrayList<P>()
        for (i in 0 until poly.size - 1) {
            val a = poly[i]; val b = poly[i + 1]
            val n = max(1, (dist(a, b) / 8f).toInt())
            for (k in 0 until n) out.add(P(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n))
        }
        out.add(poly.last())
        return out
    }

    private fun pack(poly: List<P>): FloatArray {
        val f = FloatArray(poly.size * 3)
        poly.forEachIndexed { i, p -> f[i * 3] = p.x; f[i * 3 + 1] = p.y; f[i * 3 + 2] = 0.6f }
        return f
    }

    /** Used by diagram cleanup to build clean strokes. */
    fun rect(l: Float, t: Float, r: Float, b: Float): FloatArray =
        pack(densify(listOf(P(l, t), P(r, t), P(r, b), P(l, b), P(l, t))))

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray =
        pack((0..72).map { k -> val t = 2 * PI * k / 72; P(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat()) })

    fun diamond(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray =
        pack(densify(listOf(P(cx, cy - ry), P(cx + rx, cy), P(cx, cy + ry), P(cx - rx, cy), P(cx, cy - ry))))

    fun line(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray = pack(densify(listOf(P(x1, y1), P(x2, y2))))

    /** Arrow head as its own small stroke. */
    fun arrowHead(x1: Float, y1: Float, x2: Float, y2: Float, size: Float = 16f): FloatArray {
        val a = atan2(y2 - y1, x2 - x1)
        val l = P(x2 - size * cos(a - 0.5f), y2 - size * sin(a - 0.5f))
        val r = P(x2 - size * cos(a + 0.5f), y2 - size * sin(a + 0.5f))
        return pack(densify(listOf(l, P(x2, y2), r)))
    }

    @Suppress("unused") private fun clamp(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
}
