package com.jondude.noteup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.KeyEvent
import com.jondude.noteup.data.PenMode
import com.jondude.noteup.data.PressureMode
import com.jondude.noteup.data.Settings
import kotlin.math.max

/** The "Pen" section of Settings: pen type, pressure, palm rejection, pen button, scratch-out. */
@Composable
fun PenSettingsSection() {
    var calibrating by remember { mutableStateOf(false) }
    fun s(block: () -> Unit) { block(); Settings.save() }

    SectionLabel("Pen")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton("Auto", on = Settings.penMode == PenMode.AUTO) { s { Settings.penMode = PenMode.AUTO } }
        InkButton("S Pen", on = Settings.penMode == PenMode.SPEN) { s { Settings.penMode = PenMode.SPEN } }
        InkButton("Any stylus", on = Settings.penMode == PenMode.ANY_STYLUS) { s { Settings.penMode = PenMode.ANY_STYLUS } }
    }
    T(
        when (Settings.penMode) {
            PenMode.AUTO -> if (Settings.stylusSeen) "An S Pen was detected on this tablet, so only the pen writes and fingers turn pages."
                else "No S Pen detected yet, so any touch writes with software palm rejection. Two fingers swipe pages; a two-finger tap undoes."
            PenMode.SPEN -> "Only a real S Pen writes. Fingers swipe pages and tap."
            PenMode.ANY_STYLUS -> "Any stylus or finger writes, with software palm rejection. Two fingers swipe pages; a two-finger tap undoes."
        },
        size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp),
    )

    SectionLabel("Pressure")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton("Auto", on = Settings.pressureMode == PressureMode.AUTO) { s { Settings.pressureMode = PressureMode.AUTO } }
        InkButton("Simulated", on = Settings.pressureMode == PressureMode.SIMULATED) { s { Settings.pressureMode = PressureMode.SIMULATED } }
        InkButton("Even line", on = Settings.pressureMode == PressureMode.OFF) { s { Settings.pressureMode = PressureMode.OFF } }
    }
    T("Simulated pressure works like a fountain pen: slow strokes come out heavier, quick ones lighter, with tapered ends. " +
        "Auto uses real pressure if your pen reports it.", size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp))

    SectionLabel("Palm rejection")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton("Gentle", small = true, on = Settings.palmMm >= 9f) { s { Settings.palmMm = 9f } }
        InkButton("Normal", small = true, on = Settings.palmMm in 6f..8.99f) { s { Settings.palmMm = 7f } }
        InkButton("Strict", small = true, on = Settings.palmMm < 6f) { s { Settings.palmMm = 5f } }
        InkButton("Calibrate to my pen", small = true) { calibrating = true }
    }
    T("Touches wider than %.1f mm are treated as your palm. Calibrate if your stylus has a wide rubber or disc tip.".format(Settings.palmMm),
        size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp))

    SectionLabel("Pen button → eraser")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton(if (Settings.learningPenKey) "Press your pen's button now…" else "Learn pen button", small = true, on = Settings.learningPenKey) {
            Settings.learningPenKey = !Settings.learningPenKey
        }
        if (Settings.penKey != -1) InkButton("Forget", small = true) { s { Settings.penKey = -1 } }
    }
    T(
        if (Settings.penKey != -1) "Learned: ${KeyEvent.keyCodeToString(Settings.penKey).removePrefix("KEYCODE_").lowercase()}. Press it to switch between pen and eraser."
        else "Bluetooth styluses usually send their button as a key. Plain rubber-tip styluses have no signal to catch — scribble over ink to erase instead.",
        size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp),
    )
    Toggle("Scribble to erase", Settings.scratchErase, { v -> s { Settings.scratchErase = v } },
        note = "Scratch back and forth over writing to delete it")

    if (calibrating) PalmCalibration(onDone = { calibrating = false })
}

@Composable
private fun PalmCalibration(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val mmPerPx = 25.4f / ctx.resources.displayMetrics.xdpi
    var tipMm by remember { mutableStateOf(0f) }
    var strokes by remember { mutableStateOf(0) }
    val view = remember {
        CalibrationPad(ctx) { sizePx, done ->
            tipMm = max(tipMm, sizePx * mmPerPx)
            if (done) strokes++
        }
    }
    InkDialog("Calibrate palm rejection", onDismiss = onDone) {
        T("Write a few words in the box with your stylus, the way you normally hold it. Keep your hand off the screen.", size = 15.sp)
        Spacer(Modifier.height(12.dp))
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(220.dp).border(Ink.Rule, Ink.Black))
        Spacer(Modifier.height(10.dp))
        val suggested = (tipMm * 1.8f).coerceAtLeast(tipMm + 2.5f).coerceIn(3.5f, 14f)
        T(if (strokes < 3) "Strokes: $strokes of 3" else "Pen tip measures %.1f mm → palm threshold %.1f mm".format(tipMm, suggested),
            sans = true, size = 14.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Use this", on = true, enabled = strokes >= 3 && tipMm > 0f) {
                Settings.palmMm = suggested; Settings.save(); onDone()
            }
            InkButton("Clear") { tipMm = 0f; strokes = 0; view.clear() }
            InkButton("Cancel", onClick = onDone)
        }
        if (strokes >= 3 && tipMm == 0f) {
            T("This tablet doesn't report contact size, so palm rejection will rely on position and timing instead.",
                size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Plain drawing box that reports the widest contact it sees. */
@SuppressLint("ViewConstructor")
private class CalibrationPad(ctx: Context, val report: (sizePx: Float, strokeDone: Boolean) -> Unit) : View(ctx) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }

    fun clear() { path.reset(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(path, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> path.moveTo(e.x, e.y)
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
        }
        if (e.pointerCount == 1) report(max(e.touchMajor, e.touchMinor), e.actionMasked == MotionEvent.ACTION_UP)
        invalidate()
        return true
    }
}
