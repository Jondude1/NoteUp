package com.jondude.noteup.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Twists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Serializable
data class DailyBrief(val date: String, val text: String, val createdAt: Long = System.currentTimeMillis())

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

/** The morning page: calendar, what's due, and what happened yesterday. */
object Brief {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val niceFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US)
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

    fun today(): String = dayFmt.format(Date())

    fun cached(): DailyBrief? = Store.read<DailyBrief>("brief.json")?.takeIf { it.date == today() }

    fun hasCalendarPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Today's events from every calendar on the tablet (needs calendar permission). */
    fun eventsToday(ctx: Context): List<CalEvent> {
        if (!hasCalendarPermission(ctx)) return emptyList()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val end = start + 24 * 3600_000L
        val out = ArrayList<CalEvent>()
        try {
            CalendarContract.Instances.query(
                ctx.contentResolver,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                start, end,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CalEvent(c.getString(0) ?: "(no title)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4))
                }
            }
        } catch (_: Exception) {}
        return out.sortedBy { if (it.allDay) 0L else it.begin }
    }

    fun eventLine(e: CalEvent) = if (e.allDay) "All day — ${e.title}" else "${timeFmt.format(Date(e.begin))} — ${e.title}" +
        (e.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

    /** Tasks due today or earlier that aren't done. */
    fun dueTasks(): List<TaskItem> = Store.tasks.filter { !it.done && it.due != null && it.due <= today() }.sortedBy { it.due }

    suspend fun generate(ctx: Context): DailyBrief {
        val events = withContext(Dispatchers.IO) { if (Settings.briefCalendar) eventsToday(ctx) else emptyList() }
        val since = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        }.timeInMillis
        val pages = Store.allPages().filter { it.third.updatedAt >= since }
        val recs = Store.recordings.filter { it.createdAt >= since }
        val open = Store.tasks.filter { !it.done }

        val material = buildString {
            append("TODAY: ${niceFmt.format(Date())}\n\n")
            append("CALENDAR:\n")
            if (events.isEmpty()) append("(nothing scheduled)\n") else events.forEach { append("- ${eventLine(it)}\n") }
            append("\nOPEN TASKS:\n")
            if (open.isEmpty()) append("(none)\n") else open.take(40).forEach { append("- ${it.text}${it.due?.let { d -> " [due $d]" } ?: ""}\n") }
            append("\nNOTES SINCE YESTERDAY:\n")
            if (pages.isEmpty()) append("(none)\n")
            pages.take(15).forEach { (nb, i, p) ->
                append("### ${nb.title} p${i + 1}${p.title?.let { " — $it" } ?: ""}\n${p.convertedText.orEmpty().take(600)}\n")
                p.annotations.forEach { append("(Claude noted: ${it.text.take(200)})\n") }
            }
            append("\nRECORDINGS SINCE YESTERDAY:\n")
            if (recs.isEmpty()) append("(none)\n")
            recs.take(8).forEach { r ->
                append("### ${r.title}\n${r.summary ?: r.segments.joinToString(" ") { it.text }.take(800)}\n")
            }
        }
        val text = AiClient.ask(Twists.SYSTEM, Twists.brief(material), null, 900)
        val b = DailyBrief(today(), text)
        withContext(Dispatchers.IO) { Store.writeNow("brief.json", Store.json.encodeToString(b)) }
        return b
    }

    /** Opens the calendar app's "new event" screen for a task (all-day on its due date). */
    fun addToCalendar(ctx: Context, t: TaskItem) {
        val day = t.due?.let { try { dayFmt.parse(it) } catch (e: Exception) { null } } ?: Date()
        val cal = Calendar.getInstance().apply { time = day; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, t.text)
            putExtra(CalendarContract.Events.DESCRIPTION, "From NoteUp${t.source?.let { " — $it" } ?: ""}")
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cal.timeInMillis + 24 * 3600_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
