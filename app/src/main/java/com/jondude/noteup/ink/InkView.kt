package com.jondude.noteup.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.jondude.noteup.data.MarginNote
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.Stroke
import com.jondude.noteup.data.Template
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, ERASER, LASSO, PLAY }

/**
 * The writing surface. Plain View (not Compose) so S Pen input goes straight to a Canvas
 * with unbuffered dispatch. Everything is stored in page units (see PAGE_W/PAGE_H).
 */
@SuppressLint("ViewConstructor")
class InkView(context: Context) : View(context) {

    // ---- public state ----
    var template: Template = Template.LINED
        set(v) { field = v; invalidateCache() }
    var tool: Tool = Tool.PEN
        set(v) { field = v; if (v != Tool.LASSO) clearSelection(); invalidate() }
    var penWidth = 3.5f
    var stylusOnly = true

    /** While recording, returns (recordingId, current audio ms) so strokes get linked to the audio. */
    var recordingClock: (() -> Pair<String, Long>?)? = null

    /** During playback: strokes written after this point are drawn faded ("ink replay"). */
    var playhead: Pair<String, Long>? = null
        set(v) { field = v; invalidateCache() }

    var onStrokesChanged: ((List<Stroke>) -> Unit)? = null
    var onLasso: ((ids: Set<String>, bounds: RectF) -> Unit)? = null
    var onSeek: ((recId: String, ms: Long) -> Unit)? = null
    var onAnnotationTap: ((MarginNote) -> Unit)? = null
    /** Finger swipe: +1 = next page, -1 = previous. */
    var onSwipe: ((Int) -> Unit)? = null

    private var strokes: MutableList<Stroke> = mutableListOf()
    private var annotations: List<MarginNote> = emptyList()
    private val undoStack = ArrayDeque<List<Stroke>>()
    private val redoStack = ArrayDeque<List<Stroke>>()

    fun setContent(s: List<Stroke>, a: List<MarginNote>) {
        strokes = s.toMutableList()
        annotations = a
        undoStack.clear(); redoStack.clear()
        clearSelection()
        invalidateCache()
    }

    fun setAnnotations(a: List<MarginNote>) { annotations = a; invalidateCache() }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(strokes.toList())
        strokes = prev.toMutableList()
        changed()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(strokes.toList())
        strokes = next.toMutableList()
        changed()
    }

    fun deleteStrokes(ids: Set<String>) {
        if (ids.isEmpty()) return
        snapshot()
        strokes.removeAll { it.id in ids }
        clearSelection()
        changed()
    }

    fun clearSelection() { selection = null; selectionIds = emptySet(); invalidate() }

    // ---- geometry ----
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w / PAGE_W, h / PAGE_H)
        offX = (w - PAGE_W * scale) / 2f
        offY = (h - PAGE_H * scale) / 2f
        cache?.recycle(); cache = null
        invalidateCache()
    }

    private fun toPageX(x: Float) = (x - offX) / scale
    private fun toPageY(y: Float) = (y - offY) / scale
    private fun vx(x: Float) = offX + x * scale
    private fun vy(y: Float) = offY + y * scale

    // ---- paints ----
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fadedPaint = Paint(inkPaint).apply { color = Color.rgb(190, 190, 190) }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 200, 200); strokeWidth = 1.5f }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150); strokeWidth = 2f }
    private val edgePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val outsidePaint = Paint().apply { color = Color.rgb(236, 236, 232) }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val noteLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); letterSpacing = 0.15f
    }
    private val noteBarPaint = Paint().apply { color = Color.BLACK }

    // ---- committed-ink cache ----
    private var cache: Bitmap? = null
    private var cacheDirty = true
    private val annotationRects = HashMap<String, RectF>()

    private fun invalidateCache() { cacheDirty = true; invalidate() }

    private fun rebuildCache() {
        if (width == 0 || height == 0) return
        val bmp = cache ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cache = it }
        val c = Canvas(bmp)
        c.drawColor(outsidePaint.color)
        val page = RectF(vx(0f), vy(0f), vx(PAGE_W), vy(PAGE_H))
        c.drawRect(page, Paint().apply { color = Color.WHITE })
        drawTemplate(c)
        c.drawRect(page, edgePaint)
        val ph = playhead
        for (s in strokes) {
            val faded = ph != null && (s.recId != ph.first || s.audioMs > ph.second)
            drawStroke(c, s.pts, s.width, if (faded) fadedPaint else inkPaint)
        }
        drawAnnotations(c)
        cacheDirty = false
    }

    private fun drawTemplate(c: Canvas) {
        when (template) {
            Template.BLANK -> {}
            Template.LINED -> {
                var y = 120f
                while (y < PAGE_H - 40) { c.drawLine(vx(40f), vy(y), vx(PAGE_W - 40), vy(y), rulePaint); y += 48f }
                c.drawLine(vx(110f), vy(60f), vx(110f), vy(PAGE_H - 40), marginPaint)
            }
            Template.GRID -> {
                var x = 40f
                while (x <= PAGE_W - 40) { c.drawLine(vx(x), vy(40f), vx(x), vy(PAGE_H - 40), rulePaint); x += 40f }
                var y = 40f
                while (y <= PAGE_H - 40) { c.drawLine(vx(40f), vy(y), vx(PAGE_W - 40), vy(y), rulePaint); y += 40f }
            }
            Template.DOTS -> {
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 150, 150) }
                var y = 40f
                while (y <= PAGE_H - 40) {
                    var x = 40f
                    while (x <= PAGE_W - 40) { c.drawCircle(vx(x), vy(y), max(1.5f, 2f * scale), dot); x += 40f }
                    y += 40f
                }
            }
            Template.CORNELL -> {
                var y = 160f
                while (y < 1150f) { c.drawLine(vx(300f), vy(y), vx(PAGE_W - 40), vy(y), rulePaint); y += 48f }
                c.drawLine(vx(40f), vy(110f), vx(PAGE_W - 40), vy(110f), marginPaint)
                c.drawLine(vx(280f), vy(110f), vx(280f), vy(1170f), marginPaint)
                c.drawLine(vx(40f), vy(1170f), vx(PAGE_W - 40), vy(1170f), marginPaint)
            }
        }
    }

    private fun drawStroke(c: Canvas, pts: FloatArray, width: Float, paint: Paint) {
        val n = pts.size / 3
        if (n == 0) return
        if (n == 1) {
            paint.strokeWidth = width * scale
            c.drawPoint(vx(pts[0]), vy(pts[1]), paint)
            return
        }
        for (i in 1 until n) {
            val p = (pts[i * 3 + 2] + pts[(i - 1) * 3 + 2]) / 2f
            paint.strokeWidth = max(1f, width * scale * (0.45f + 0.9f * p.coerceIn(0f, 1f)))
            c.drawLine(vx(pts[(i - 1) * 3]), vy(pts[(i - 1) * 3 + 1]), vx(pts[i * 3]), vy(pts[i * 3 + 1]), paint)
        }
    }

    private fun drawAnnotations(c: Canvas) {
        annotationRects.clear()
        notePaint.textSize = 22f * scale
        noteLabelPaint.textSize = 11f * scale
        for (a in annotations) {
            val w = max(80f, a.w * scale).toInt()
            val layout = StaticLayout.Builder.obtain(a.text, 0, a.text.length, notePaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            val left = vx(a.x)
            val top = vy(a.y)
            c.drawText("CLAUDE", left + 10f * scale, top, noteLabelPaint)
            c.drawRect(left, top + 6f * scale, left + 3f * scale, top + 10f * scale + layout.height, noteBarPaint)
            c.save()
            c.translate(left + 10f * scale, top + 8f * scale)
            layout.draw(c)
            c.restore()
            annotationRects[a.id] = RectF(left, top - 14f * scale, left + 10f * scale + w, top + 10f * scale + layout.height)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cacheDirty || cache == null) rebuildCache()
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // live stroke
        if (live.size >= 3) {
            if (tool == Tool.LASSO && !liveErasing) {
                val p = Path()
                p.moveTo(vx(live[0]), vy(live[1]))
                var i = 3
                while (i < live.size) { p.lineTo(vx(live[i]), vy(live[i + 1])); i += 3 }
                canvas.drawPath(p, lassoPaint)
            } else if (!liveErasing) {
                drawStroke(canvas, live.toFloatArray(), penWidth, inkPaint)
            }
        }
        selection?.let {
            canvas.drawRect(vx(it.left) - 8, vy(it.top) - 8, vx(it.right) + 8, vy(it.bottom) + 8, lassoPaint)
        }
    }

    // ---- input ----
    private val live = ArrayList<Float>(512)
    private val liveTimes = ArrayList<Long>(256)
    private var liveT0 = 0L
    private var liveErasing = false
    private var liveAudio: Pair<String, Long>? = null
    private var erasedThisGesture = false
    private var downX = 0f
    private var downY = 0f
    private var selection: RectF? = null
    private var selectionIds: Set<String> = emptySet()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val toolType = e.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val writes = (isPen || !stylusOnly) && tool != Tool.PLAY

        if (!writes) return handleTap(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                live.clear(); liveTimes.clear()
                liveT0 = e.eventTime
                liveErasing = tool == Tool.ERASER ||
                    toolType == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                erasedThisGesture = false
                liveAudio = recordingClock?.invoke()
                if (tool != Tool.LASSO) clearSelection()
                addPoint(e.x, e.y, e.pressure, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), e.getHistoricalEventTime(h))
                }
                addPoint(e.x, e.y, e.pressure, e.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                addPoint(e.x, e.y, e.pressure, e.eventTime)
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> { live.clear(); liveTimes.clear(); invalidate() }
        }
        return true
    }

    private fun addPoint(x: Float, y: Float, p: Float, t: Long) {
        val px = toPageX(x)
        val py = toPageY(y)
        if (liveErasing) {
            eraseAt(px, py)
            return
        }
        val n = live.size
        if (n >= 3 && abs(live[n - 3] - px) < 0.4f && abs(live[n - 2] - py) < 0.4f) return
        live.add(px); live.add(py); live.add(if (p <= 0f) 0.5f else p)
        liveTimes.add(t - liveT0)
        invalidate()
    }

    private fun eraseAt(px: Float, py: Float) {
        val r = 14f
        val hit = strokes.filter { s ->
            var hitIt = false
            var i = 0
            while (i < s.pts.size) {
                if (hypot(s.pts[i] - px, s.pts[i + 1] - py) < r + s.width) { hitIt = true; break }
                i += 3
            }
            hitIt
        }
        if (hit.isNotEmpty()) {
            if (!erasedThisGesture) { snapshot(); erasedThisGesture = true }
            strokes.removeAll(hit.toSet())
            invalidateCache()
        }
    }

    private fun finishGesture() {
        if (liveErasing) {
            if (erasedThisGesture) changed()
            liveErasing = false
            return
        }
        if (tool == Tool.LASSO) {
            finishLasso()
        } else if (live.size >= 3) {
            snapshot()
            val audio = liveAudio
            val s = Stroke(
                pts = live.toFloatArray(),
                times = liveTimes.toLongArray(),
                t0 = System.currentTimeMillis(),
                width = penWidth,
                recId = audio?.first,
                audioMs = audio?.second ?: -1,
            )
            strokes.add(s)
            // draw onto the cache directly; no full rebuild needed
            cache?.let { if (!cacheDirty) drawStroke(Canvas(it), s.pts, s.width, inkPaint) }
            onStrokesChanged?.invoke(strokes.toList())
        }
        live.clear(); liveTimes.clear()
        invalidate()
    }

    private fun finishLasso() {
        if (live.size < 9) { live.clear(); return }
        val path = Path()
        path.moveTo(live[0], live[1])
        var i = 3
        while (i < live.size) { path.lineTo(live[i], live[i + 1]); i += 3 }
        path.close()
        val region = Region()
        region.setPath(path, Region(0, 0, PAGE_W.toInt() + 1, PAGE_H.toInt() + 1))
        val picked = strokes.filter { s ->
            val n = s.pts.size / 3
            var inside = 0
            var j = 0
            while (j < s.pts.size) { if (region.contains(s.pts[j].toInt(), s.pts[j + 1].toInt())) inside++; j += 3 }
            n > 0 && inside * 2 >= n
        }
        live.clear()
        if (picked.isEmpty()) { clearSelection(); return }
        val b = boundsOf(picked)
        selection = b
        selectionIds = picked.map { it.id }.toSet()
        invalidate()
        onLasso?.invoke(selectionIds, b)
    }

    private fun handleTap(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > 160f && abs(dx) > abs(dy) * 2f) {
                    onSwipe?.invoke(if (dx < 0) 1 else -1)
                    return true
                }
                if (hypot(dx, dy) > 24f) return true
                annotationRects.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { hit ->
                    annotations.firstOrNull { it.id == hit.key }?.let { onAnnotationTap?.invoke(it) }
                    return true
                }
                if (tool == Tool.PLAY) {
                    val px = toPageX(e.x)
                    val py = toPageY(e.y)
                    val near = strokes.filter { it.recId != null && it.audioMs >= 0 }
                        .minByOrNull { distanceTo(it, px, py) }
                    if (near != null && distanceTo(near, px, py) < 40f) onSeek?.invoke(near.recId!!, near.audioMs)
                }
            }
        }
        return true
    }

    private fun distanceTo(s: Stroke, px: Float, py: Float): Float {
        var best = Float.MAX_VALUE
        var i = 0
        while (i < s.pts.size) { best = min(best, hypot(s.pts[i] - px, s.pts[i + 1] - py)); i += 3 }
        return best
    }

    private fun snapshot() {
        undoStack.addLast(strokes.toList())
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun changed() {
        invalidateCache()
        onStrokesChanged?.invoke(strokes.toList())
    }

    companion object {
        fun boundsOf(list: List<Stroke>): RectF {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (s in list) {
                var i = 0
                while (i < s.pts.size) {
                    l = min(l, s.pts[i]); r = max(r, s.pts[i])
                    t = min(t, s.pts[i + 1]); b = max(b, s.pts[i + 1])
                    i += 3
                }
            }
            return RectF(l, t, r, b)
        }
    }
}
