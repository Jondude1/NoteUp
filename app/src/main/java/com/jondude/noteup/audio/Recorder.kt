package com.jondude.noteup.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.data.Recording
import com.jondude.noteup.data.Segment
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.SttEngine
import com.jondude.noteup.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Records 16 kHz mono WAV and transcribes as it goes:
 *  - ON_DEVICE: streams the same audio into Android's speech recognizer (Android 13+).
 *  - WHISPER:   sends ~20 s chunks to your Whisper server.
 * One recording at a time; it keeps going while you move between pages.
 */
object Recorder {
    var active by mutableStateOf<Recording?>(null)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var partial by mutableStateOf("")
        private set
    var status by mutableStateOf<String?>(null)
        private set
    val segments = mutableStateListOf<Segment>()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bytesWritten = 0L

    /** Current position in the active recording, for linking pen strokes to audio. */
    fun clock(): Pair<String, Long>? = active?.let { it.id to (bytesWritten / BYTES_PER_MS) }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, title: String, notebookId: String?, pageId: String?): Boolean {
        if (active != null) return false
        val rec = Recording(title = title, fileName = "${System.currentTimeMillis()}.wav", notebookId = notebookId, pageId = pageId)
        val file = File(Store.audioDir, rec.fileName)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var found: AudioRecord? = null
        var why = ""
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            try {
                val a = AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
                if (a.state == AudioRecord.STATE_INITIALIZED) { found = a; break }
                a.release()
                why = "microphone didn't start"
            } catch (e: Throwable) { why = e.message ?: e.javaClass.simpleName }
        }
        val ar: AudioRecord = found ?: run {
            fail("Can't record: $why. Is another app using the microphone?", ctx); return false
        }

        segments.clear(); partial = ""; elapsedMs = 0; bytesWritten = 0; status = null
        active = rec
        lateTarget = null
        Store.upsertRecording(rec)
        running = true
        appCtx = ctx.applicationContext
        // a foreground service keeps the microphone alive when the screen locks or you switch apps
        try { ContextCompat.startForegroundService(appCtx!!, Intent(appCtx, RecordingService::class.java)) } catch (_: Exception) {}

        val useWhisper = Settings.sttEngine == SttEngine.WHISPER
        if (!useWhisper) main.post { OnDeviceStt.start(ctx.applicationContext) }

        thread = Thread {
            val buf = ByteArray(3200) // 100 ms
            val chunk = ByteArrayOutputStream()
            var chunkStartMs = 0L
            FileOutputStream(file).use { out ->
                out.write(Wav.header(0))
                try { ar.startRecording() } catch (e: Throwable) { running = false; main.post { fail("Can't record: ${e.message}", appCtx) } }
                while (running) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    out.write(buf, 0, n)
                    bytesWritten += n
                    if (useWhisper) {
                        chunk.write(buf, 0, n)
                        if (chunk.size() >= 20_000 * BYTES_PER_MS) {
                            sendChunk(chunk.toByteArray(), chunkStartMs, bytesWritten / BYTES_PER_MS)
                            chunk.reset(); chunkStartMs = bytesWritten / BYTES_PER_MS
                        }
                    } else {
                        OnDeviceStt.feed(buf, n)
                    }
                    val ms = bytesWritten / BYTES_PER_MS
                    if (ms - elapsedMs >= 250) main.post { elapsedMs = ms }
                }
                ar.stop(); ar.release()
                if (useWhisper && chunk.size() > BYTES_PER_MS * 800) {
                    sendChunk(chunk.toByteArray(), chunkStartMs, bytesWritten / BYTES_PER_MS)
                }
            }
            Wav.finalize(file)
        }.apply { name = "noteup-recorder"; start() }
        return true
    }

    private fun fail(msg: String, ctx: Context?) {
        status = msg
        ctx?.let { try { android.widget.Toast.makeText(it.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
    }

    fun stop(): Recording? {
        val rec = active ?: return null
        running = false
        thread?.join(2000)
        thread = null
        // late transcription results (the last spoken sentence, whisper chunks in flight) still land on the saved recording
        lateTarget = rec.id
        main.post { OnDeviceStt.stop() }
        val base = Store.recording(rec.id) ?: rec // keep a rename made while recording
        val done = base.copy(durationMs = bytesWritten / BYTES_PER_MS, segments = segments.toList())
        Store.upsertRecording(done)
        active = null
        partial = ""
        appCtx?.let { AutoMinutes.schedule(it, rec.id, Settings.sttEngine == SttEngine.WHISPER) }
        try { appCtx?.let { it.stopService(Intent(it, RecordingService::class.java)) } } catch (_: Exception) {}
        return done
    }

    @Volatile private var lateTarget: String? = null
    private var appCtx: Context? = null

    /** Where a transcript line belongs right now: the live recording, or one that just stopped. */
    internal fun targetId(): String? = active?.id ?: lateTarget

    /** After a crash or kill mid-recording the WAV header was never finished; make the file playable again. */
    fun recoverOrphans() {
        if (active != null) return
        for (r in Store.recordings.toList()) {
            if (r.durationMs > 0) continue
            val f = Store.audioFile(r)
            if (!f.exists() || f.length() <= 44) continue
            try {
                Wav.finalize(f)
                Store.upsertRecording(r.copy(durationMs = (f.length() - 44) / BYTES_PER_MS))
            } catch (_: Exception) {}
        }
    }
    private val order = Mutex()

    private fun sendChunk(pcm: ByteArray, startMs: Long, endMs: Long) {
        val recId = active?.id
        scope.launch {
            try {
                val text = order.withLock { AiClient.transcribe(Wav.wrap(pcm)) }
                if (text.isNotBlank()) main.post { addSegment(Segment(startMs, endMs, text), recId) }
            } catch (e: Exception) {
                main.post { status = e.message }
            }
        }
    }

    internal fun addSegment(seg: Segment, recId: String?) {
        if (recId == null) return
        if (active != null && active?.id == recId) {
            val i = segments.indexOfFirst { it.startMs > seg.startMs }
            if (i < 0) segments.add(seg) else segments.add(i, seg)
        } else if (recId == lateTarget) {
            Store.recording(recId)?.let { r ->
                Store.upsertRecording(r.copy(segments = (r.segments + seg).sortedBy { it.startMs }))
            }
        }
    }

    internal fun setPartial(p: String) { partial = p }
    internal fun setStatus(s: String?) { status = s }
    internal fun nowMs() = bytesWritten / BYTES_PER_MS
    internal fun activeId() = active?.id
    internal fun isRunning() = running
}

/**
 * Android's built-in recognizer, fed from our own mic stream through a pipe
 * (EXTRA_AUDIO_SOURCE, Android 13+) so it can share the microphone with the WAV recording.
 */
private object OnDeviceStt : RecognitionListener {
    private var sr: SpeechRecognizer? = null
    private var ctx: Context? = null
    @Volatile private var pipeOut: OutputStream? = null
    private var pipeIn: ParcelFileDescriptor? = null
    private var segStartMs = 0L
    private var failures = 0
    private val main = Handler(Looper.getMainLooper())

    fun start(c: Context) {
        ctx = c
        failures = 0
        if (Build.VERSION.SDK_INT < 33) {
            Recorder.setStatus("Live transcript needs Android 13+. Audio is still recording; use a Whisper server for transcripts.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(c)) {
            Recorder.setStatus("No speech recognizer on this tablet. Install Google app or use a Whisper server.")
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(c).also { it.setRecognitionListener(this) }
        startPump()
        listen()
    }

    private fun listen() {
        val r = sr ?: return
        if (Build.VERSION.SDK_INT < 33) return
        val pipe = ParcelFileDescriptor.createPipe()
        pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        pipeIn = pipe[0]
        segStartMs = Recorder.nowMs()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Settings.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Recorder.setStatus("Live transcript unavailable: ${e.message}")
        }
    }

    // The recognizer may stop reading between sessions; a full pipe would block the recorder,
    // so audio goes through a small queue that drops data instead of stalling.
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(60)
    @Volatile private var pumping = false

    fun feed(buf: ByteArray, n: Int) {
        if (pipeOut == null) return
        queue.offer(buf.copyOf(n))
    }

    private fun startPump() {
        if (pumping) return
        pumping = true
        Thread {
            while (pumping) {
                val b = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val o = pipeOut ?: continue
                try { o.write(b) } catch (e: IOException) { if (pipeOut === o) pipeOut = null }
            }
            queue.clear()
        }.apply { name = "noteup-stt-pump"; isDaemon = true; start() }
    }

    private fun closePipe() {
        try { pipeOut?.close() } catch (_: IOException) {}
        try { pipeIn?.close() } catch (_: IOException) {}
        pipeOut = null
        pipeIn = null
    }

    fun stop() {
        pumping = false
        closePipe() // end of audio: the recognizer finishes the sentence it was in the middle of
        val old = sr
        sr = null
        // give it a moment to deliver that last result before shutting it down
        main.postDelayed({ try { old?.destroy() } catch (_: Exception) {} }, 2500)
    }

    private fun restart(delayMs: Long) {
        closePipe()
        main.postDelayed({ if (Recorder.isRunning() && sr != null) listen() }, delayMs)
    }

    private fun texts(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun commit(text: String) {
        if (text.isBlank()) return
        Recorder.addSegment(Segment(segStartMs, Recorder.nowMs(), text.trim()), Recorder.targetId())
        segStartMs = Recorder.nowMs()
        Recorder.setPartial("")
        failures = 0
    }

    override fun onPartialResults(partialResults: Bundle?) { Recorder.setPartial(texts(partialResults)) }
    override fun onSegmentResults(segmentResults: Bundle) { commit(texts(segmentResults)) }
    override fun onResults(results: Bundle?) { commit(texts(results)); restart(50) }
    override fun onEndOfSegmentedSession() { restart(50) }

    override fun onError(error: Int) {
        if (!Recorder.isRunning()) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                Recorder.setStatus("Language ${Settings.language} isn't available for live transcript on this tablet.")
            }
            else -> {
                failures++
                if (failures > 5) Recorder.setStatus("Live transcript stopped (error $error). Audio is still recording.")
                else restart(400L * failures)
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) { Recorder.setStatus(null) }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
