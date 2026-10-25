package com.jondude.noteup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.audio.BYTES_PER_MS
import com.jondude.noteup.audio.Player
import com.jondude.noteup.audio.Recorder
import com.jondude.noteup.audio.Wav
import com.jondude.noteup.data.Segment
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.ink.HandwritingRecognizer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

@Composable
fun RecordingScreen(id: String, onBack: () -> Unit, onOpenNotebook: (nbId: String, pageId: String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec = Store.recordings.firstOrNull { it.id == id }
    if (rec == null) { LaunchedEffect(Unit) { onBack() }; return }
    val isLive = Recorder.active?.id == id
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renamingSpeaker by remember { mutableStateOf<String?>(null) }
    val segments = if (isLive) Recorder.segments.toList() else rec.segments
    val listState = rememberLazyListState()

    LaunchedEffect(Player.playing) { while (Player.playing) { Player.tick(); delay(250) } }
    LaunchedEffect(segments.size, isLive) { if (isLive && segments.isNotEmpty()) listState.scrollToItem(segments.size - 1) }

    fun minutes() {
        busy = "Writing minutes…"; error = null
        scope.launch {
            try {
                val transcript = rec.segments.joinToString("\n") { "[${fmtTime(it.startMs)}] ${it.text}" }
                val notes = rec.notebookId?.let { nbId ->
                    val nb = Store.notebook(nbId) ?: return@let ""
                    val strokes = nb.pageIds.flatMap { pid -> Store.page(pid, nbId).strokes.filter { it.recId == rec.id } }
                    try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
                }.orEmpty()
                val out = AiClient.ask(Twists.SYSTEM, Twists.minutes(transcript, notes), null, 1800)
                Store.upsertRecording(rec.copy(summary = out))
                Store.addTasksWithDue(Twists.parseActionsWithDue(out), rec.title)
            } catch (e: Exception) { error = e.message }
            busy = null
        }
    }

    /** Re-run the whole file through Whisper in 30 s slices. */
    /** AI splits the transcript into speaker turns, using names when people introduce themselves. */
    fun labelSpeakers() {
        busy = "Working out who's speaking…"; error = null
        scope.launch {
            try {
                val segs = rec.segments
                val numbered = segs.mapIndexed { i, s -> "$i: ${s.text}" }.joinToString("\n").take(90_000)
                val reply = AiClient.ask(Twists.SYSTEM, Twists.speakers(numbered), null, 8000)
                val json = Twists.jsonObject(reply) ?: throw IllegalStateException("The AI didn't return speaker turns.")
                val turns = Store.json.parseToJsonElement(json).jsonObject["turns"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val o = el.jsonObject
                    val chunk = o["chunk"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                    Triple(chunk, o["speaker"]?.jsonPrimitive?.content ?: "Speaker", o["text"]?.jsonPrimitive?.content.orEmpty())
                }
                if (turns.isEmpty()) throw IllegalStateException("No speaker turns found.")
                val out = ArrayList<Segment>()
                for ((i, seg) in segs.withIndex()) {
                    val mine = turns.filter { it.first == i && it.third.isNotBlank() }
                    if (mine.isEmpty()) { out += seg; continue }
                    val total = mine.sumOf { it.third.length }.coerceAtLeast(1)
                    var t = seg.startMs
                    for (turn in mine) {
                        val len = (seg.endMs - seg.startMs) * turn.third.length / total
                        out += Segment(t, t + len, turn.third.trim(), turn.second.trim())
                        t += len
                    }
                }
                Store.recording(rec.id)?.let { Store.upsertRecording(it.copy(segments = out)) }
            } catch (e: Exception) { error = e.message }
            busy = null
        }
    }

    fun renameSpeaker(old: String, new: String) {
        val r = Store.recording(rec.id) ?: return
        Store.upsertRecording(r.copy(segments = r.segments.map { if (it.speaker == old) it.copy(speaker = new) else it }))
    }

    fun retranscribe() {
        busy = "Transcribing…"; error = null
        scope.launch {
            try {
                val file = Store.audioFile(rec)
                val segs = mutableListOf<Segment>()
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { raf ->
                        val total = raf.length() - 44
                        val step = 30_000L * BYTES_PER_MS
                        var off = 0L
                        while (off < total) {
                            val len = minOf(step, total - off).toInt()
                            val buf = ByteArray(len)
                            raf.seek(44 + off); raf.readFully(buf)
                            busy = "Transcribing… ${fmtTime(off / BYTES_PER_MS)} / ${fmtTime(total / BYTES_PER_MS)}"
                            val text = AiClient.transcribe(Wav.wrap(buf))
                            if (text.isNotBlank()) segs += Segment(off / BYTES_PER_MS, (off + len) / BYTES_PER_MS, text)
                            off += len
                        }
                    }
                }
                Store.upsertRecording(rec.copy(segments = segs))
            } catch (e: Exception) { error = e.message }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.White)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolItem("‹ Back") { onBack() }
            Box(Modifier.inkClick { renaming = true }.padding(horizontal = 10.dp)) { T(rec.title, size = 18.sp, bold = true, maxLines = 1) }
            Spacer(Modifier.width(16.dp))
            if (isLive) {
                ToolItem("■ Stop ${fmtTime(Recorder.elapsedMs)}", on = true) { Recorder.stop() }
            } else {
                ToolItem("−15s") { Player.seekBy(-15_000) }
                ToolItem(if (Player.currentId == rec.id && Player.playing) "❚❚ Pause" else "▶ Play", on = Player.currentId == rec.id && Player.playing) { Player.toggle(rec) }
                ToolItem("+15s") { Player.seekBy(15_000) }
                T(if (Player.currentId == rec.id) "${fmtTime(Player.positionMs)} / ${fmtTime(rec.durationMs)}" else fmtTime(rec.durationMs),
                    sans = true, size = 14.sp, modifier = Modifier.padding(horizontal = 10.dp))
                ToolItem("✦ Minutes", enabled = busy == null) { minutes() }
                ToolItem("✦ Speakers", enabled = busy == null && rec.segments.isNotEmpty()) { labelSpeakers() }
                if (Settings.whisperUrl.isNotBlank()) ToolItem("Re-transcribe", enabled = busy == null) { retranscribe() }
                rec.notebookId?.let { nbId -> ToolItem("Open notes") { onOpenNotebook(nbId, rec.pageId) } }
                ToolItem(if (confirmDelete) "Tap to confirm" else "Delete") {
                    if (confirmDelete) { Player.release(); Store.deleteRecording(rec.id); onBack() } else confirmDelete = true
                }
            }
        }
        HRule()
        busy?.let { T(it, italic = true, color = Ink.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        error?.let { T(it, italic = true, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        if (isLive) Recorder.status?.let { T(it, italic = true, size = 14.sp, color = Ink.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp), state = listState) {
                item { SectionLabel(if (isLive) "Live transcript" else "Transcript — tap a line to play from there") }
                if (segments.isEmpty() && !isLive) item { T("No transcript yet.", italic = true, color = Ink.Gray) }
                items(segments.indices.toList()) { idx ->
                    val s = segments[idx]
                    val prevSpeaker = segments.getOrNull(idx - 1)?.speaker
                    if (s.speaker != null && s.speaker != prevSpeaker) {
                        Box(Modifier.padding(top = 12.dp, bottom = 2.dp).inkClick(enabled = !isLive) { renamingSpeaker = s.speaker }) {
                            T(s.speaker.uppercase(), size = 12.sp, sans = true, bold = true)
                        }
                    }
                    val current = !isLive && Player.currentId == rec.id && Player.positionMs >= s.startMs && Player.positionMs < s.endMs
                    Row(
                        Modifier.fillMaxWidth().background(if (current) Ink.Black else Ink.White)
                            .inkClick(enabled = !isLive) { Player.play(rec, s.startMs) }.padding(vertical = 8.dp, horizontal = 6.dp)
                    ) {
                        T(fmtTime(s.startMs), size = 12.sp, sans = true, color = if (current) Ink.White else Ink.Gray, modifier = Modifier.width(56.dp))
                        T(s.text, size = 17.sp, color = if (current) Ink.White else Ink.Black)
                    }
                }
                if (isLive && Recorder.partial.isNotBlank()) item {
                    T(Recorder.partial, italic = true, color = Ink.Gray, modifier = Modifier.padding(start = 62.dp, top = 6.dp))
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
            rec.summary?.let { summary ->
                VRule(Modifier.fillMaxHeight())
                Column(Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    T("Minutes", size = 20.sp, bold = true)
                    Spacer(Modifier.height(10.dp))
                    T(summary, size = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("Copy", small = true) { copyText(ctx, summary) }
                    }
                }
            }
        }
    }

    renamingSpeaker?.let { old ->
        var name by remember(old) { mutableStateOf(old) }
        InkDialog("Rename speaker", onDismiss = { renamingSpeaker = null }) {
            T("Every line by \"$old\" will use the new name.", size = 14.sp, color = Ink.Gray)
            Spacer(Modifier.height(10.dp))
            InkField(name, { name = it })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Save", on = true) { if (name.isNotBlank()) renameSpeaker(old, name.trim()); renamingSpeaker = null }
                InkButton("Cancel") { renamingSpeaker = null }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(rec.title) }
        InkDialog("Rename recording", onDismiss = { renaming = false }) {
            InkField(name, { name = it })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Save", on = true) {
                    val latest = Store.recording(rec.id)
                    if (latest != null && name.isNotBlank()) Store.upsertRecording(latest.copy(title = name.trim()))
                    renaming = false
                }
                InkButton("Cancel") { renaming = false }
            }
        }
    }
}
