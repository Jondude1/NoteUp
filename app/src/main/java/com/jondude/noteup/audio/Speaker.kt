package com.jondude.noteup.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jondude.noteup.data.Settings
import java.util.Locale

/** Reads text aloud with the tablet's own voice, so notes can be listened to with the screen off. */
object Speaker {
    var speaking by mutableStateOf(false)
        private set
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: List<String>? = null

    /** Splits long text on line and sentence breaks so each piece fits the engine's input limit. */
    fun chunks(text: String, max: Int = 3500): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (line in text.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() }) {
            val sentence = if (line.last() in ".!?:") line else "$line."
            if (cur.length + sentence.length + 1 > max && cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
            if (sentence.length > max) {
                sentence.chunked(max).forEach { out.add(it) }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(sentence)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    fun speak(ctx: Context, text: String) {
        val parts = chunks(text)
        if (parts.isEmpty()) return
        val engine = tts
        if (engine == null) {
            pending = parts
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    val loc = Locale.forLanguageTag(Settings.language)
                    tts?.language = loc
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { if (utteranceId == "last") speaking = false }
                        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { speaking = false }
                    })
                    pending?.let { play(it) }
                    pending = null
                } else speaking = false
            }
            speaking = true
        } else if (ready) play(parts)
    }

    private fun play(parts: List<String>) {
        val e = tts ?: return
        speaking = true
        parts.forEachIndexed { i, p ->
            e.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) "last" else "p$i")
        }
    }

    fun stop() {
        tts?.stop()
        speaking = false
    }
}
