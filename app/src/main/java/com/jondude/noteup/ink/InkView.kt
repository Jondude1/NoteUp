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
import com.jondude.noteup.data.PenMode
import com.jondude.noteup.data.PressureMode
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

    // ---- pen input settings (see Settings) ----
    var penMode = PenMode.AUTO
    var pressureMode = PressureMode.AUTO
    /** Contact width above which a touch counts as a palm, in px. */
    var palmPx = 60f
    var scratchErase = true
    /** Set from outside when a stylus button (learned key) toggles the eraser. */
    var eraserLatched = false
    var stylusSeen = false
    var onStylusDetected: (() -> Unit)? = null
    /** Two-finger tap. */
    var onTwoFingerTap: (() -> Unit)? = null

    /** Hand rest: touches below this line (fraction of view height, or -1 = off) never write. */
    var handRestFrac = -1f
        set(v) { field = v; invalidate() }
    var onHandRestMoved: ((Float) -> Unit)? = null
    private val handRestY: Float get() = if (handRestFrac < 0f) -1f else handRestFrac * height
    private var draggingRest = false
    private val restPaint = Paint().apply { color = Color.argb(28, 0, 0, 0) }
    private val restLine = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
    private val restText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 90); textSize = 28f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

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
        val ry = handRestY
        if (ry >= 0f) {
            canvas.drawRect(0f, ry, width.toFloat(), height.toFloat(), restPaint)
            canvas.drawLine(0f, ry, width.toFloat(), ry, restLine)
            canvas.drawRect(width - 150f, ry - 22f, width - 30f, ry + 22f, restLine)
            canvas.drawText("HAND REST — drag the tab", 24f, ry + 40f, restText)
        }
    }

    // ---- input ----
    private val live = ArrayList<Float>(512)
    private val liveTimes = ArrayList<Long>(256)
    private var liveT0 = 0L
    private var liveErasing = false
    private var liveSimulated = false
    private var liveAudio: Pair<String, Long>? = null
    private var erasedThisGesture = false
    private var downX = 0f
    private var downY = 0f
    private var selection: RectF? = null
    private var selectionIds: Set<String> = emptySet()

    // pressure simulation
    private var speedEma = 0f
    private var pMin = 1f
    private var pMax = 0f

    // touch-pen state (capacitive stylus or finger writing)
    private var penId = -1
    private var twoFinger = false
    private var tfStartX = 0f
    private var tfStartY = 0f
    private var tfLastX = 0f
    private var tfLastY = 0f
    private var tfStartTime = 0L
    private var liveTravel = 0f
    private class Palm(val x: Float, val y: Float, val t: Long)
    private val palms = ArrayList<Palm>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // drag the hand-rest tab (right edge of its line)
        val ry = handRestY
        if (ry >= 0f && (draggingRest || (e.actionMasked == MotionEvent.ACTION_DOWN && e.x > width - 190f && abs(e.y - ry) < 60f))) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { draggingRest = true; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> handRestFrac = (e.y / height).coerceIn(0.25f, 0.95f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { draggingRest = false; onHandRestMoved?.invoke(handRestFrac) }
            }
            return true
        }
        var stylusEvent = false
        for (i in 0 until e.pointerCount) {
            val tt = e.getToolType(i)
            if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) stylusEvent = true
        }
        if (stylusEvent && !stylusSeen) { stylusSeen = true; onStylusDetected?.invoke() }

        if (tool == Tool.PLAY) return handleTap(e)
        if (stylusEvent || stylusId != -1) return handleStylus(e)
        // pen is hovering or just lifted: this touch is the hand holding it
        if (stylusSeen && e.eventTime - penActiveAt < 700) { ignoringHand = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL; return true }
        if (ignoringHand) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) ignoringHand = false; return true }
        // a palm (wide contact) or anything starting in the hand rest is ignored for the whole touch
        if (e.actionMasked == MotionEvent.ACTION_DOWN && (contactSize(e, 0) > palmPx || (handRestY >= 0f && e.y > handRestY))) {
            ignoringHand = true; return true
        }
        val fingersWrite = when (penMode) {
            PenMode.SPEN -> false
            PenMode.ANY_STYLUS -> true
            PenMode.AUTO -> !stylusSeen
        }
        return if (fingersWrite) handleTouchPen(e) else handleTap(e)
    }

    /**
     * Real active pen (S Pen / Wacom EMR). The palm can be touching the glass at the same time,
     * so the pen is found by tool type in every event and all finger contacts are ignored.
     */
    private var stylusId = -1
    private var ignoringHand = false

    private fun stylusIndex(e: MotionEvent): Int {
        for (i in 0 until e.pointerCount) {
            val tt = e.getToolType(i)
            if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return i
        }
        return -1
    }

    private fun handleStylus(e: MotionEvent): Boolean {
        penActiveAt = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val tt = e.getToolType(i)
                if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) return true // palm landing
                if (stylusId != -1) return true
                // the palm may have started a stroke a moment ago; drop it
                if (penId != -1) { cancelLive(); penId = -1 }
                stylusId = e.getPointerId(i)
                val buttonErase = tt == MotionEvent.TOOL_TYPE_ERASER ||
                    (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY)) != 0
                beginStroke(e, buttonErase, simulate = pressureMode == PressureMode.SIMULATED ||
                    (pressureMode == PressureMode.AUTO && !realPressureSeen))
                addPoint(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = if (stylusId >= 0) e.findPointerIndex(stylusId) else -1
                if (idx < 0) return true
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h), e.getHistoricalEventTime(h))
                }
                addPoint(e.getX(idx), e.getY(idx), e.getPressure(idx), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) != stylusId) return true
                stylusId = -1
                // the hand is still on the glass: ignore it until it lifts
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) ignoringHand = true
                addPoint(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
                // the pen reports genuinely varying pressure: trust it from now on
                if (pMax - pMin > 0.12f) realPressureSeen = true
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> { stylusId = -1; cancelLive() }
        }
        return true
    }

    /** Last time the S Pen touched or hovered; fingers are ignored around that (EMR palm rejection). */
    private var penActiveAt = 0L

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
            penActiveAt = e.eventTime
            if (!stylusSeen) { stylusSeen = true; onStylusDetected?.invoke() }
        }
        return super.onHoverEvent(e)
    }

    /**
     * Passive stylus or finger, with software palm rejection. The pen is assumed to be the
     * small contact that is highest on the screen (your hand rests below the tip):
     *  - every time a contact lands, the pen is re-chosen; if it changes, the stroke in progress
     *    (which came from the palm) is thrown away
     *  - contacts below the pen, wider than the palm threshold, or inside the hand rest are palm
     *  - a contact that sits still for a while (a resting palm) never becomes ink
     *  - marks a palm left in the last moment are erased when the palm is recognised
     *  - two small contacts landing together side by side = two-finger swipe / tap
     */
    private fun handleTouchPen(e: MotionEvent): Boolean {
        val now = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { firstDownTime = now; twoFinger = false }
                if (contactSize(e, i) > palmPx) notePalm(x, y, now)
                if (twoFinger) return true

                // two fingers landing together, side by side, not moving yet = gesture
                if (e.pointerCount == 2 && now - firstDownTime < 150 && liveTravel < 25f &&
                    abs(e.getY(0) - e.getY(1)) < 200f && contactSize(e, 0) <= palmPx && contactSize(e, 1) <= palmPx
                ) {
                    cancelLive(); penId = -1
                    twoFinger = true
                    tfStartTime = now
                    tfStartX = centroidX(e); tfStartY = centroidY(e)
                    tfLastX = tfStartX; tfLastY = tfStartY
                    return true
                }
                choosePen(e, now)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger) {
                    if (e.pointerCount >= 2) { tfLastX = centroidX(e); tfLastY = centroidY(e) }
                    return true
                }
                var palmNow = false
                for (i in 0 until e.pointerCount) {
                    if (contactSize(e, i) > palmPx) { notePalm(e.getX(i), e.getY(i), now); palmNow = true }
                }
                if (palmNow) choosePen(e, now)
                val idx = if (penId >= 0) e.findPointerIndex(penId) else -1
                if (idx < 0) return true
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), 0.6f, e.getHistoricalEventTime(h))
                }
                addPoint(e.getX(idx), e.getY(idx), 0.6f, now)
                liveTravel = hypot(e.getX(idx) - downX, e.getY(idx) - downY).coerceAtLeast(liveTravel)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val canceled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
                if (twoFinger) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = tfLastX - tfStartX
                        val dy = tfLastY - tfStartY
                        when {
                            abs(dx) > 140f && abs(dx) > abs(dy) * 1.5f -> onSwipe?.invoke(if (dx < 0) 1 else -1)
                            now - tfStartTime < 300 && hypot(dx, dy) < 30f -> onTwoFingerTap?.invoke()
                        }
                        twoFinger = false
                    }
                    return true
                }
                if (id == penId) {
                    penId = -1
                    val duration = now - liveT0
                    when {
                        canceled -> cancelLive()
                        // quick tap on a margin note opens it
                        liveTravel < 12f && duration < 250 && tapAnnotation(e.getX(i), e.getY(i)) -> cancelLive()
                        // something that sat nearly still for a while is a resting hand, not a dot
                        liveTravel < 10f && duration > 220 -> cancelLive()
                        else -> { addPoint(e.getX(i), e.getY(i), 0.6f, now); finishGesture() }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { cancelLive(); penId = -1; twoFinger = false }
        }
        return true
    }

    private var firstDownTime = 0L

    /** Picks which contact is the pen; drops the in-progress stroke if it belonged to a palm. */
    private fun choosePen(e: MotionEvent, now: Long) {
        var best = -1
        var bestY = Float.MAX_VALUE
        for (i in 0 until e.pointerCount) {
            val x = e.getX(i)
            val y = e.getY(i)
            if (contactSize(e, i) > palmPx) continue
            if (handRestY >= 0f && y > handRestY) continue
            if (nearPalm(x, y, now)) continue
            if (y < bestY) { bestY = y; best = i }
        }
        // everything that isn't the pen and sits below it is palm
        if (best >= 0) {
            for (i in 0 until e.pointerCount) {
                if (i != best && e.getY(i) > bestY + 20f) notePalm(e.getX(i), e.getY(i), now)
            }
        }
        val newId = if (best >= 0) e.getPointerId(best) else -1
        if (newId == penId) return
        if (penId != -1) cancelLive() // the old "pen" was really a palm
        penId = newId
        if (best < 0) return
        val x = e.getX(best)
        val y = e.getY(best)
        liveTravel = 0f
        downX = x; downY = y
        beginStroke(e, false, simulate = pressureMode != PressureMode.OFF)
        addPoint(x, y, 0.6f, now)
    }

    private fun beginStroke(e: MotionEvent, buttonErase: Boolean, simulate: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        live.clear(); liveTimes.clear()
        liveT0 = e.eventTime
        liveErasing = tool == Tool.ERASER || buttonErase || (eraserLatched && tool == Tool.PEN)
        liveSimulated = simulate
        speedEma = 0f
        pMin = 1f; pMax = 0f
        erasedThisGesture = false
        liveAudio = recordingClock?.invoke()
        if (tool != Tool.LASSO) clearSelection()
    }

    private fun cancelLive() {
        live.clear(); liveTimes.clear()
        if (liveErasing && erasedThisGesture) changed()
        liveErasing = false
        invalidate()
    }

    private fun contactSize(e: MotionEvent, i: Int): Float = max(e.getTouchMajor(i), e.getTouchMinor(i))

    private fun centroidX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun centroidY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun nearPalm(x: Float, y: Float, now: Long): Boolean {
        palms.removeAll { now - it.t > 700 }
        return palms.any { hypot(it.x - x, it.y - y) < palmPx * 2.5f }
    }

    private fun notePalm(x: Float, y: Float, now: Long) {
        val fresh = palms.none { now - it.t < 400 && hypot(it.x - x, it.y - y) < palmPx * 2f }
        palms.add(Palm(x, y, now))
        if (palms.size > 30) palms.removeAt(0)
        if (!fresh) return
        // remove the marks a palm leaves just before it's recognised
        val cutoff = System.currentTimeMillis() - 700
        val px = toPageX(x)
        val py = toPageY(y)
        val radius = max(palmPx * 5f, 160f) / scale
        val stray = strokes.filter { s ->
            s.t0 >= cutoff && s.pts.size <= 3 * 40 && run {
                val b = boundsOf(listOf(s))
                hypot(b.centerX() - px, b.centerY() - py) < radius && max(b.width(), b.height()) < 60f
            }
        }
        if (stray.isNotEmpty()) {
            strokes.removeAll(stray.toSet())
            changed()
        }
    }

    private fun tapAnnotation(x: Float, y: Float): Boolean {
        val hit = annotationRects.entries.firstOrNull { it.value.contains(x, y) } ?: return false
        annotations.firstOrNull { it.id == hit.key }?.let { onAnnotationTap?.invoke(it) }
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
        val pressure = when {
            pressureMode == PressureMode.OFF && liveSimulated -> 0.6f
            liveSimulated -> simulatedPressure(px, py, t - liveT0)
            else -> if (p <= 0f) 0.5f else p
        }
        if (p > 0f) { pMin = min(pMin, p); pMax = max(pMax, p) }
        live.add(px); live.add(py); live.add(pressure)
        liveTimes.add(t - liveT0)
        invalidate()
    }

    /**
     * Fake pen pressure from speed, like a fountain pen: slow = heavier line, fast = lighter,
     * plus a short taper in at the start (the end taper is added when the stroke finishes).
     */
    private fun simulatedPressure(px: Float, py: Float, tMs: Long): Float {
        val n = live.size / 3
        if (n == 0) return 0.45f
        val lx = live[live.size - 3]
        val ly = live[live.size - 2]
        val dt = (tMs - liveTimes.last()).coerceAtLeast(1L).toFloat()
        val v = hypot(px - lx, py - ly) / dt // page units per ms (~5 units per mm)
        speedEma = if (n == 1) v else speedEma * 0.65f + v * 0.35f
        var p = (1.0f - speedEma * 0.55f).coerceIn(0.32f, 1f)
        val prev = live[live.size - 1]
        p = prev * 0.6f + p * 0.4f
        if (n < 4) p *= 0.6f + n * 0.1f
        return p
    }

    private fun taperEnd() {
        val n = live.size / 3
        val k = min(5, n - 1)
        for (j in 0 until k) {
            val idx = (n - 1 - j) * 3 + 2
            live[idx] = live[idx] * (0.35f + 0.65f * j / k)
        }
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
            if (scratchErase && tool == Tool.PEN && scratchOut()) {
                live.clear(); liveTimes.clear(); invalidate()
                return
            }
            if (liveSimulated) taperEnd()
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

    /**
     * Scribble back and forth over ink to erase it (no eraser button needed).
     * Needs several direction reversals in a compact area and must actually cover existing ink.
     */
    private fun scratchOut(): Boolean {
        val n = live.size / 3
        if (n < 14) return false
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var length = 0f
        var reversals = 0
        var lastSign = 0
        for (i in 0 until n) {
            val x = live[i * 3]; val y = live[i * 3 + 1]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            if (i > 0) {
                val dx = x - live[(i - 1) * 3]
                val dy = y - live[(i - 1) * 3 + 1]
                length += hypot(dx, dy)
                val main = if (maxX - minX >= maxY - minY) dx else dy
                if (abs(main) > 1.5f) {
                    val sign = if (main > 0) 1 else -1
                    if (lastSign != 0 && sign != lastSign) reversals++
                    lastSign = sign
                }
            }
        }
        val w = maxX - minX
        val h = maxY - minY
        val diag = hypot(w, h)
        if (reversals < 5 || diag > 320f || diag < 12f || length < diag * 3.5f) return false
        val box = RectF(minX - 6f, minY - 6f, maxX + 6f, maxY + 6f)
        val covered = strokes.filter { s ->
            val m = s.pts.size / 3
            var inside = 0
            var j = 0
            while (j < s.pts.size) { if (box.contains(s.pts[j], s.pts[j + 1])) inside++; j += 3 }
            m > 0 && inside * 2 >= m
        }
        if (covered.isEmpty()) return false
        snapshot()
        strokes.removeAll(covered.toSet())
        changed()
        return true
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
                if (tapAnnotation(e.x, e.y)) return true
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
        /** Once a pen reports real, varying pressure we stop simulating it (AUTO mode). */
        @Volatile var realPressureSeen = false

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
