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
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.ink.HandwritingRecognizer
import com.jondude.noteup.ui.fmtTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When a recording stops, waits for the last words to be transcribed, then writes the meeting minutes by itself. */
object AutoMinutes {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun schedule(ctx: Context, recId: String, whisper: Boolean) {
        if (!Settings.autoMinutes) return
        scope.launch {
            try {
                // the last spoken sentence arrives a moment after Stop; Whisper chunks can take longer
                delay(if (whisper) 12_000L else 4_000L)
                run(ctx.applicationContext, recId)
            } catch (_: Exception) {}
        }
    }

    private suspend fun run(ctx: Context, recId: String) {
        val rec = Store.recording(recId) ?: return
        if (rec.summary != null) return
        if (!AiClient.configured(Settings.backend)) return
        val transcript = rec.segments.joinToString("\n") { "[${fmtTime(it.startMs)}] ${it.text}" }
        if (transcript.length < 40) return // nothing worth summarising
        val notes = rec.notebookId?.let { nbId ->
            val nb = Store.notebook(nbId) ?: return@let ""
            val strokes = nb.pageIds.flatMap { pid -> Store.page(pid, nbId).strokes.filter { it.recId == rec.id } }
            try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
        }.orEmpty()
        val out = try {
            AiClient.ask(Twists.SYSTEM, Twists.minutes(transcript, notes), null, 1800)
        } catch (e: Exception) {
            toast(ctx, "Couldn't write meeting notes: ${e.message}")
            return
        }
        val latest = Store.recording(recId) ?: return
        if (latest.summary != null) return // you ran Minutes yourself in the meantime
        Store.upsertRecording(latest.copy(summary = out))
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
