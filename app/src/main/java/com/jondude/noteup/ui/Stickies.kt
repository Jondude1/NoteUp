package com.jondude.noteup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.STICKY_COLORS
import com.jondude.noteup.data.Sticky
import com.jondude.noteup.ink.InkView
import kotlin.math.roundToInt

/**
 * Sticky notes floating over the page. They follow the page's zoom and pan, drag by their top bar,
 * resize from the corner, and stay on the page until deleted.
 */
@Composable
fun StickyLayer(
    stickies: List<Sticky>,
    xf: InkView.Xf,
    onChange: (Sticky) -> Unit,
    onDelete: (String) -> Unit,
) {
    // no pointer handling on the layer itself, so pen and touch fall through to the page wherever there's no note
    Box(Modifier.fillMaxSize().clipToBounds()) {
        stickies.forEach { s -> key(s.id) { StickyNote(s, xf, onChange, onDelete) } }
    }
}

@Composable
private fun StickyNote(s: Sticky, xf: InkView.Xf, onChange: (Sticky) -> Unit, onDelete: (String) -> Unit) {
    val density = LocalDensity.current
    val sticky by rememberUpdatedState(s)
    val scale by rememberUpdatedState(xf.scale)
    var dx by remember { mutableFloatStateOf(0f) } // live drag, page units
    var dy by remember { mutableFloatStateOf(0f) }
    var dw by remember { mutableFloatStateOf(0f) }
    var dh by remember { mutableFloatStateOf(0f) }

    val leftPx = xf.offX + (s.x + dx) * xf.scale
    val topPx = xf.offY + (s.y + dy) * xf.scale
    val wPx = (s.w + dw) * xf.scale
    val hPx = (s.h + dh) * xf.scale
    val bar = 30.dp
    val textSize = with(density) { (17f * xf.scale).coerceAtLeast(10f * density.density).toSp() }
    val fill = Color(STICKY_COLORS[s.color.coerceIn(0, STICKY_COLORS.lastIndex)])

    Box(
        Modifier
            .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() }.coerceAtLeast(bar + 24.dp))
            .background(fill)
            .border(2.dp, Color.Black)
    ) {
        // top bar: drag to move
        Row(
            Modifier.fillMaxWidth().height(bar).background(Color(0x22000000))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x / scale; dy += amount.y / scale
                        },
                        onDragEnd = {
                            val cur = sticky
                            onChange(cur.copy(
                                x = (cur.x + dx).coerceIn(0f, PAGE_W - 60f),
                                y = (cur.y + dy).coerceIn(0f, PAGE_H - 40f)))
                            dx = 0f; dy = 0f
                        },
                        onDragCancel = { dx = 0f; dy = 0f },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 6.dp).size(22.dp).background(fill).border(1.5.dp, Color.Black)
                .inkClick { onChange(s.copy(color = (s.color + 1) % STICKY_COLORS.size)) })
            Spacer(Modifier.width(0.dp).weight(1f))
            Box(Modifier.size(bar).inkClick { onDelete(s.id) }, contentAlignment = Alignment.Center) {
                T("✕", sans = true, bold = true, size = 16.sp, color = Color.Black)
            }
        }
        BasicTextField(
            value = s.text,
            onValueChange = { onChange(s.copy(text = it)) },
            textStyle = TextStyle(fontSize = textSize, color = Color.Black),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = bar + 4.dp, bottom = 22.dp),
        )
        if (s.text.isEmpty()) {
            T("Type a note…", size = 14.sp, italic = true, color = Color(0x88000000),
                modifier = Modifier.padding(start = 8.dp, top = bar + 4.dp))
        }
        // bottom-right corner: drag to resize
        Box(
            Modifier.align(Alignment.BottomEnd).size(26.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            val cur = sticky
                            dw = (dw + amount.x / scale).coerceIn(140f - cur.w, 640f - cur.w)
                            dh = (dh + amount.y / scale).coerceIn(100f - cur.h, 640f - cur.h)
                        },
                        onDragEnd = {
                            val cur = sticky
                            onChange(cur.copy(w = cur.w + dw, h = cur.h + dh))
                            dw = 0f; dh = 0f
                        },
                        onDragCancel = { dw = 0f; dh = 0f },
                    )
                },
            contentAlignment = Alignment.BottomEnd,
        ) { T("◢", size = 16.sp, color = Color(0xAA000000), modifier = Modifier.padding(end = 3.dp, bottom = 1.dp)) }
    }
}
