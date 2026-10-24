package com.jondude.noteup.ink

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.jondude.noteup.data.Stroke
import kotlinx.coroutines.tasks.await

/**
 * On-device handwriting recognition (ML Kit Digital Ink). The language model (~20 MB)
 * downloads once on first use, then everything runs offline.
 */
object HandwritingRecognizer {

    private fun model(lang: String): DigitalInkRecognitionModel {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang)
            ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!
        return DigitalInkRecognitionModel.builder(id).build()
    }

    suspend fun ensureModel(lang: String) {
        val m = model(lang)
        val mgr = RemoteModelManager.getInstance()
        if (!mgr.isModelDownloaded(m).await()) {
            mgr.download(m, DownloadConditions.Builder().build()).await()
        }
    }

    /** Groups strokes into lines top-to-bottom, recognizes each line, joins with newlines. */
    suspend fun recognize(strokes: List<Stroke>, lang: String): String {
        if (strokes.isEmpty()) return ""
        ensureModel(lang)
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model(lang)).build())
        try {
            val lines = groupIntoLines(strokes)
            val out = StringBuilder()
            var preContext = ""
            for (line in lines) {
                val ink = Ink.builder()
                for (s in line.sortedBy { it.t0 }) {
                    val sb = Ink.Stroke.builder()
                    val n = s.pts.size / 3
                    for (i in 0 until n) {
                        val t = s.t0 + (s.times.getOrNull(i) ?: (i * 8L))
                        sb.addPoint(Ink.Point.create(s.pts[i * 3], s.pts[i * 3 + 1], t))
                    }
                    ink.addStroke(sb.build())
                }
                val b = InkView.boundsOf(line)
                val ctx = RecognitionContext.builder()
                    .setPreContext(preContext.takeLast(20))
                    .setWritingArea(WritingArea(b.width().coerceAtLeast(10f) + 40f, b.height().coerceAtLeast(10f) + 20f))
                    .build()
                val result = recognizer.recognize(ink.build(), ctx).await()
                val text = result.candidates.firstOrNull()?.text.orEmpty()
                if (text.isNotBlank()) {
                    out.append(text).append('\n')
                    preContext = text
                }
            }
            return out.toString().trim()
        } finally {
            recognizer.close()
        }
    }

    private fun groupIntoLines(strokes: List<Stroke>): List<List<Stroke>> {
        data class Box(val s: Stroke, val top: Float, val bottom: Float, val cy: Float, val left: Float)
        val boxes = strokes.map {
            val b = InkView.boundsOf(listOf(it))
            Box(it, b.top, b.bottom, b.centerY(), b.left)
        }.sortedBy { it.cy }
        val heights = boxes.map { (it.bottom - it.top).coerceAtLeast(6f) }.sorted()
        val typical = heights[heights.size / 2].coerceIn(14f, 60f)

        val lines = mutableListOf<MutableList<Box>>()
        for (b in boxes) {
            val line = lines.lastOrNull()
            val lineCy = line?.map { it.cy }?.average()?.toFloat()
            if (line != null && lineCy != null && kotlin.math.abs(b.cy - lineCy) < typical * 0.9f) line.add(b)
            else lines.add(mutableListOf(b))
        }
        return lines.map { l -> l.sortedBy { it.left }.map { it.s } }
    }
}
