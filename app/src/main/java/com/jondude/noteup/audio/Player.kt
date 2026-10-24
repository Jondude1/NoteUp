package com.jondude.noteup.audio

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jondude.noteup.data.Recording
import com.jondude.noteup.data.Store

/** Single shared audio player. Call [tick] from a UI loop to refresh [positionMs]. */
object Player {
    var currentId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    private var mp: MediaPlayer? = null

    private fun load(r: Recording): Boolean {
        if (currentId == r.id && mp != null) return true
        release()
        val f = Store.audioFile(r)
        if (!f.exists()) return false
        return try {
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.prepare()
            p.setOnCompletionListener { playing = false; positionMs = durationMs }
            mp = p
            currentId = r.id
            durationMs = p.duration.toLong().coerceAtLeast(r.durationMs)
            positionMs = 0
            true
        } catch (e: Exception) {
            release(); false
        }
    }

    fun play(r: Recording, fromMs: Long? = null) {
        if (!load(r)) return
        val p = mp ?: return
        if (fromMs != null) { p.seekTo(fromMs.toInt()); positionMs = fromMs }
        p.start()
        playing = true
    }

    fun toggle(r: Recording) {
        if (currentId == r.id && playing) pause() else play(r)
    }

    fun pause() {
        mp?.let { if (it.isPlaying) it.pause() }
        playing = false
    }

    fun seekBy(deltaMs: Long) {
        val p = mp ?: return
        val to = (p.currentPosition + deltaMs).coerceIn(0, durationMs)
        p.seekTo(to.toInt()); positionMs = to
    }

    fun tick() {
        mp?.let { if (playing) positionMs = it.currentPosition.toLong() }
    }

    fun release() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        currentId = null
        playing = false
        positionMs = 0
    }
}
