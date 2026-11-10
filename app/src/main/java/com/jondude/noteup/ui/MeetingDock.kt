package com.jondude.noteup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.jondude.noteup.ai.NoteStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
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
import com.jondude.noteup.audio.AutoMinutes
import com.jondude.noteup.audio.Player
import com.jondude.noteup.audio.Recorder
import com.jondude.noteup.data.Documents
import com.jondude.noteup.data.Recording
import com.jondude.noteup.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The page's meeting tab, along the bottom: the notes, the transcript and the audio file of the
 * recording made on this page.  [tab]: -1 closed, 0 notes, 1 transcript, 2 recording.
 */
@Composable
fun MeetingDock(recs: List<Recording>, tab: Int, onTab: (Int) -> Unit, onOpenRecording: (String) -> Unit) {
    if (recs.isEmpty()) return
    val ctx = LocalContext.current
    var selId by remember { mutableStateOf<String?>(null) }
    val rec = recs.firstOrNull { it.id == selId } ?: recs.first()
    val live = Recorder.active?.id == rec.id
    val job = AutoMinutes.working[rec.id]

    Column(Modifier.fillMaxWidth().background(Ink.White)) {
        HRule()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(if (live) "● Meeting" else "Meeting", sans = true, bold = true, size = 14.sp, modifier = Modifier.padding(start = 14.dp, end = 6.dp))
            listOf("Notes", "Transcript", "Recording").forEachIndexed { i, label ->
                ToolItem(if (i == 0 && job != null) "Notes …" else label, on = tab == i) { onTab(if (tab == i) -1 else i) }
            }
            Spacer(Modifier.weight(1f))
            if (recs.size > 1) {
                T("${recs.indexOf(rec) + 1}/${recs.size}", sans = true, size = 13.sp, color = Ink.Gray)
                ToolItem("‹") { selId = recs[(recs.indexOf(rec) + 1) % recs.size].id }
            }
            if (tab >= 0) ToolItem("▾") { onTab(-1) }
        }
        if (tab >= 0) {
            HRule(thickness = 1.dp)
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                when (tab) {
                    0 -> NotesTab(rec, live, job)
                    1 -> TranscriptTab(rec, live)
                    else -> RecordingTab(rec, live, onOpenRecording)
                }
            }
        }
    }
}

@Composable
private fun NotesTab(rec: Recording, live: Boolean, job: String?) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T(rec.title, bold = true, size = 17.sp)
        rec.summaryStyle?.let { T(NoteStyle.from(it).label + " notes", size = 13.sp, sans = true, color = Ink.Gray) }
        Spacer(Modifier.height(8.dp))
        when {
            live -> T("Notes are written automatically when you stop recording.", italic = true, color = Ink.Gray)
            job != null -> T(job, italic = true, color = Ink.Gray)
            rec.summary != null -> {
                T(rec.summary, size = 16.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Copy", small = true) { copyText(ctx, rec.summary) }
                    InkButton("Rewrite", small = true) { AutoMinutes.redo(ctx, rec.id, NoteStyle.from(rec.summaryStyle)) }
                }
                Spacer(Modifier.height(14.dp))
                SectionLabel("Rewrite as")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteStyle.entries.forEach { st ->
                        InkButton(st.label, on = rec.summaryStyle == st.name, small = true) { AutoMinutes.redo(ctx, rec.id, st) }
                    }
                }
            }
            else -> {
                T("No notes yet for this recording.", italic = true, color = Ink.Gray)
                Spacer(Modifier.height(10.dp))
                InkButton("Write the notes", small = true) { AutoMinutes.redo(ctx, rec.id) }
            }
        }
    }
}

@Composable
private fun TranscriptTab(rec: Recording, live: Boolean) {
    val segs = if (live) Recorder.segments.toList() else rec.segments
    LaunchedEffect(Player.playing) { while (Player.playing) { Player.tick(); delay(250) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (segs.isEmpty() && !live) item { T("No transcript for this recording.", italic = true, color = Ink.Gray) }
        if (live) item {
            T("● Recording ${fmtTime(Recorder.elapsedMs)}", sans = true, bold = true, size = 14.sp)
            Recorder.status?.let { T(it, size = 13.sp, italic = true, color = Ink.Gray) }
        }
        items(segs) { s ->
            val current = !live && Player.currentId == rec.id && Player.positionMs in s.startMs until s.endMs.coerceAtLeast(s.startMs + 1)
            Column(
                Modifier.fillMaxWidth().background(if (current) Ink.Black else Ink.White)
                    .inkClick(enabled = !live) { Player.play(rec, s.startMs) }.padding(vertical = 5.dp, horizontal = 4.dp)
            ) {
                T("${fmtTime(s.startMs)}${s.speaker?.let { " · $it" } ?: ""}", size = 12.sp, sans = true, color = if (current) Ink.White else Ink.Gray)
                T(s.text, size = 16.sp, color = if (current) Ink.White else Ink.Black)
            }
        }
        if (live && Recorder.partial.isNotBlank()) item { T(Recorder.partial, italic = true, color = Ink.Gray) }
    }
}

@Composable
private fun RecordingTab(rec: Recording, live: Boolean, onOpenRecording: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Player.playing) { while (Player.playing) { Player.tick(); delay(250) } }
    val mine = Player.currentId == rec.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T(rec.title, bold = true, size = 17.sp)
        T("${rec.fileName} · ${fmtTime(if (live) Recorder.elapsedMs else rec.durationMs)}", size = 13.sp, sans = true, color = Ink.Gray)
        Spacer(Modifier.height(12.dp))
        if (live) {
            T("Recording… the file is saved when you stop.", italic = true, color = Ink.Gray)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolItem("−15s") { if (!mine) Player.play(rec, 0).also { Player.pause() }; Player.seekBy(-15_000) }
                ToolItem(if (mine && Player.playing) "❚❚ Pause" else "▶ Play", on = mine && Player.playing) { Player.toggle(rec) }
                ToolItem("+15s") { if (!mine) Player.play(rec, 0).also { Player.pause() }; Player.seekBy(15_000) }
                Spacer(Modifier.width(10.dp))
                T("${fmtTime(if (mine) Player.positionMs else 0)} / ${fmtTime(rec.durationMs)}", sans = true, size = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Share audio file", small = true) {
                    status = "Preparing…"
                    scope.launch {
                        status = try {
                            val f = withContext(Dispatchers.IO) {
                                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                                File(dir, rec.fileName).also { Store.audioFile(rec).copyTo(it, overwrite = true) }
                            }
                            Documents.share(ctx, f, "audio/wav")
                            null
                        } catch (e: Exception) { "Couldn't share: ${e.message}" }
                    }
                }
                InkButton("Open full screen", small = true) { onOpenRecording(rec.id) }
            }
            status?.let { T(it, italic = true, size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
