package com.jondude.noteup.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.Stroke
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Renders ink to a clean black-on-white PNG for the vision model. */
object PageRenderer {
    fun render(strokes: List<Stroke>, area: RectF? = null, maxSide: Int = 1400): Bitmap {
        val pad = 24f
        val src = area?.let { RectF(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
            ?: RectF(0f, 0f, PAGE_W, PAGE_H)
        val s = min(maxSide / src.width(), maxSide / src.height()).coerceAtMost(2.5f)
        val w = max(1, (src.width() * s).toInt())
        val h = max(1, (src.height() * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (st in strokes) {
            val n = st.pts.size / 3
            p.strokeWidth = max(2f, st.width * s)
            for (i in 1 until n) {
                c.drawLine(
                    (st.pts[(i - 1) * 3] - src.left) * s, (st.pts[(i - 1) * 3 + 1] - src.top) * s,
                    (st.pts[i * 3] - src.left) * s, (st.pts[i * 3 + 1] - src.top) * s, p,
                )
            }
        }
        return bmp
    }

    fun toPngBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
