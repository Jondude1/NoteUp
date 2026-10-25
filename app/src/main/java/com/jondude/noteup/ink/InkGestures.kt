package com.jondude.noteup.ink

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pen gestures recognised from a finished stroke (packed x,y,pressure triples in page units). */
object InkGestures {

    /**
     * Back-and-forth scribbling ("scratch out"). Returns the area scribbled over, or null if the
     * stroke is ordinary writing. Needs several sharp passes along one direction, so cursive and
     * circles don't trigger it.
     */
    fun scribble(pts: FloatArray): RectF? {
        val n = pts.size / 3
        if (n < 14) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var len = 0f
        var mx = 0f; var my = 0f
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            mx += x; my += y
            if (i > 0) len += hypot(x - pts[(i - 1) * 3], y - pts[(i - 1) * 3 + 1])
        }
        mx /= n; my /= n
        val diag = hypot(r - l, b - t)
        if (diag < 45f || len < diag * 2.6f) return null

        // main direction of the stroke (principal axis)
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until n) {
            val dx = pts[i * 3] - mx; val dy = pts[i * 3 + 1] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val ang = 0.5f * atan2(2f * sxy, sxx - syy)
        val ux = cos(ang); val uy = sin(ang)
        val s = FloatArray(n)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            s[i] = (pts[i * 3] - mx) * ux + (pts[i * 3 + 1] - my) * uy
            lo = min(lo, s[i]); hi = max(hi, s[i])
        }
        val extent = hi - lo
        if (extent < 40f) return null

        // count turnarounds along that axis; each must travel a good part of the way
        val thr = extent * 0.35f
        var dir = 0
        var top = s[0]; var bottom = s[0]
        var turns = 0
        for (i in 1 until n) {
            val v = s[i]
            when (dir) {
                0 -> {
                    top = max(top, v); bottom = min(bottom, v)
                    if (top - v > thr) { dir = -1; bottom = v }
                    else if (v - bottom > thr) { dir = 1; top = v }
                }
                1 -> if (v > top) top = v else if (top - v > thr) { turns++; dir = -1; bottom = v }
                else -> if (v < bottom) bottom = v else if (v - bottom > thr) { turns++; dir = 1; top = v }
            }
        }
        return if (turns >= 3) RectF(l, t, r, b) else null
    }

    /**
     * A small closed square drawn in one stroke. Returns its bounds, or null. Circles are rejected
     * (they don't reach the corners) and so are tall or wide shapes.
     */
    fun checkbox(pts: FloatArray): RectF? {
        val n = pts.size / 3
        if (n < 8) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var len = 0f
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            if (i > 0) len += hypot(x - pts[(i - 1) * 3], y - pts[(i - 1) * 3 + 1])
        }
        val w = r - l; val h = b - t
        if (w < 16f || h < 16f || w > 90f || h > 90f) return null
        val ratio = w / h
        if (ratio < 0.65f || ratio > 1.55f) return null
        val per = 2f * (w + h)
        if (len < per * 0.82f || len > per * 1.5f) return null
        val gap = hypot(pts[0] - pts[(n - 1) * 3], pts[1] - pts[(n - 1) * 3 + 1])
        if (gap > per * 0.28f) return null

        // the ink must come reasonably close to all four corners (a circle falls short of them) ...
        val m = min(w, h)
        val cornerTol = m * 0.30f
        val cx = floatArrayOf(l, r, r, l)
        val cy = floatArrayOf(t, t, b, b)
        for (c in 0 until 4) {
            var best = Float.MAX_VALUE
            for (i in 0 until n) best = min(best, hypot(pts[i * 3] - cx[c], pts[i * 3 + 1] - cy[c]))
            if (best > cornerTol) return null
        }
        // ... and fill most of its bounding box (a circle fills about 78%, a square 90-100%)
        var twice = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            // measured from the box corner so the sums stay small and precise
            twice += (pts[i * 3] - l) * (pts[j * 3 + 1] - t) - (pts[j * 3] - l) * (pts[i * 3 + 1] - t)
        }
        return if (abs(twice) / 2f >= w * h * 0.80f) RectF(l, t, r, b) else null
    }

    /** The two line segments of a hand-drawn tick inside a box: x1,y1,x2,y2 then x2,y2,x3,y3. */
    fun tick(b: RectF): FloatArray {
        fun x(f: Float) = b.left + b.width() * f
        fun y(f: Float) = b.top + b.height() * f
        return floatArrayOf(x(0.15f), y(0.55f), x(0.40f), y(0.85f), x(0.40f), y(0.85f), x(1.10f), y(0.05f))
    }
}
