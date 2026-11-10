package com.jondude.noteup.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jondude.noteup.MainActivity
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.NoteStyle
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.ink.HandwritingRecognizer
import com.jondude.noteup.ui.fmtTime
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When a recording stops, waits for the last words to be transcribed, then writes the meeting minutes by itself. */
object AutoMinutes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** Recordings being worked on right now -> what step they're at (shown in the page's meeting tab). */
    val working = mutableStateMapOf<String, String>()

    fun schedule(ctx: Context, recId: String, whisper: Boolean) {
        if (!Settings.autoMinutes) return
        working[recId] = "Finishing the transcript…"
        scope.launch {
            try {
                // the last spoken sentence arrives a moment after Stop; Whisper chunks can take longer
                delay(if (whisper) 12_000L else 4_000L)
                run(ctx.applicationContext, recId, force = false, style = Settings.noteStyle)
            } catch (_: Exception) {
            } finally { working.remove(recId) }
        }
    }

    /** From the meeting tab: fix the wording and write the notes again, even if notes already exist. */
    fun redo(ctx: Context, recId: String, style: NoteStyle = Settings.noteStyle) {
        if (working.containsKey(recId)) return
        working[recId] = "Working…"
        scope.launch {
            try { run(ctx.applicationContext, recId, force = true, style = style) } catch (_: Exception) {} finally { working.remove(recId) }
        }
    }

    /** Fixes misheard words in the saved transcript using the notebook's names and terms. Keeps the original wording in [Segment.raw]. */
    suspend fun cleanTranscript(recId: String, notes: String) {
        val rec = Store.recording(recId) ?: return
        if (rec.segments.isEmpty() || !AiClient.configured(Settings.backend)) return
        val context = buildString {
            Vocab.build(rec.notebookId).takeIf { it.isNotEmpty() }?.let { append("Names/terms: ${it.joinToString(", ")}\n") }
            if (notes.isNotBlank()) append("The notes written during the meeting:\n${notes.take(2500)}")
        }.ifBlank { "(none)" }
        val fixed = HashMap<Int, String>()
        val segs = rec.segments
        var start = 0
        while (start < segs.size) {
            val end = minOf(segs.size, start + 60)
            val numbered = (start until end).joinToString("\n") { "$it: ${segs[it].text}" }
            try {
                val reply = AiClient.ask(Twists.SYSTEM, Twists.cleanTranscript(numbered, context), null, 6000)
                val json = Twists.jsonObject(reply)
                if (json != null) {
                    Store.json.parseToJsonElement(json).jsonObject["lines"]?.jsonArray?.forEach { el ->
                        val o = el.jsonObject
                        val i = o["i"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                        val t = o["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val orig = segs.getOrNull(i)?.text ?: return@forEach
                        // ignore answers that are empty or wildly different in length (the AI summarised or rambled)
                        if (t.isNotEmpty() && t.length >= orig.length * 0.5 && t.length <= orig.length * 2 + 20) fixed[i] = t
                    }
                }
            } catch (_: Exception) { /* keep this batch as heard */ }
            start = end
        }
        if (fixed.isEmpty()) return
        val latest = Store.recording(recId) ?: return
        val merged = latest.segments.mapIndexed { i, s ->
            val t = fixed[i]
            if (t != null && t != s.text) s.copy(text = t, raw = s.raw ?: s.text) else s
        }
        Store.upsertRecording(latest.copy(segments = merged))
    }

    private suspend fun run(ctx: Context, recId: String, force: Boolean, style: NoteStyle) {
        var rec = Store.recording(recId) ?: return
        if (!force && rec.summary != null) return
        if (!AiClient.configured(Settings.backend)) {
            if (force) toast(ctx, "Set up an AI in Settings first.")
            return
        }
        if (rec.segments.joinToString(" ") { it.text }.length < 40) {
            if (force) toast(ctx, "There isn't enough transcript to summarise.")
            return
        }
        val notes = rec.notebookId?.let { nbId ->
            val nb = Store.notebook(nbId) ?: return@let ""
            val strokes = nb.pageIds.flatMap { pid -> Store.page(pid, nbId).strokes.filter { it.recId == rec.id } }
            try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
        }.orEmpty()
        working[recId] = "Fixing misheard words…"
        cleanTranscript(recId, notes)
        rec = Store.recording(recId) ?: return
        working[recId] = "Writing the meeting notes…"
        val transcript = rec.segments.joinToString("\n") { "[${fmtTime(it.startMs)}] ${it.text}" }
        val out = try {
            AiClient.ask(Twists.SYSTEM, Twists.minutes(transcript, notes, style), null, 1800)
        } catch (e: Exception) {
            toast(ctx, "Couldn't write meeting notes: ${e.message}")
            return
        }
        val latest = Store.recording(recId) ?: return
        if (!force && latest.summary != null) return // you ran Minutes yourself in the meantime
        Store.upsertRecording(latest.copy(summary = out, summaryStyle = style.name))
        Store.addTasksWithDue(Twists.parseActionsWithDue(out), rec.title)
        toast(ctx, "Meeting notes ready: ${rec.title}")
        notify(ctx, rec.title)
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
    }

    private fun notify(ctx: Context, title: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("noteup_minutes", "Meeting notes", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(4343, Notification.Builder(ctx, "noteup_minutes")
                .setContentTitle("Meeting notes ready").setContentText(title)
                .setSmallIcon(android.R.drawable.ic_menu_agenda).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }
}
