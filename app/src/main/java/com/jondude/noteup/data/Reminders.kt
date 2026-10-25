package com.jondude.noteup.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads the word(s) written next to a circled item: "Fri", "tomorrow", "10/30", "oct 30", "in 3 days",
 * "remind me friday", "due 3/5"... and turns them into a due date. Works offline, no AI needed.
 */
object Reminders {
    class Reminder(val due: String?)

    private val dayNames = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday") // Calendar.DAY_OF_WEEK - 1
    private val monthNames = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** A reminder if [written] is one, else null (so ordinary notes beside a circle are left alone). */
    fun parse(written: String, now: Calendar = Calendar.getInstance()): Reminder? {
        val text = written.lowercase().replace('\n', ' ')
            .replace(Regex("[^a-z0-9/\\-. ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        val words = text.split(' ').map { it.trim('.', '-') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val first = words.first()
        val strong = first.startsWith("remind") || first == "due" || first == "todo" || first == "task" ||
            (first == "to" && words.getOrNull(1) == "do")
        val weak = first == "by" || first == "on"
        val date = findDate(words, now)
        return when {
            strong -> Reminder(date)
            weak && date != null -> Reminder(date)
            date != null && words.size <= 3 -> Reminder(date)
            else -> null
        }
    }

    private fun midnight(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun out(c: Calendar) = fmt.format(c.time)

    private fun dayIndex(w: String): Int? {
        if (w.length < 3) return null
        val i = dayNames.indexOfFirst { it.startsWith(w) }
        return if (i >= 0) i else null
    }

    private fun monthIndex(w: String): Int? {
        if (w.length < 3) return null
        val i = monthNames.indexOfFirst { it.startsWith(w) }
        return if (i >= 0) i else if (w == "sept") 8 else null
    }

    private fun dayOfMonth(w: String): Int? {
        val n = w.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull() ?: return null
        return if (n in 1..31) n else null
    }

    private fun findDate(words: List<String>, now: Calendar): String? {
        val today = midnight(now)
        fun plus(days: Int) = out((today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days) })

        // written-out dates: 2026-10-30, 10/30, 10/30/26
        for (w in words) {
            Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(w)?.let { m ->
                val (y, mo, d) = m.destructured
                return "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
            }
            Regex("""^(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?$""").find(w)?.let { m ->
                val mo = m.groupValues[1].toInt()
                val d = m.groupValues[2].toInt()
                if (mo in 1..12 && d in 1..31) return resolve(today, mo - 1, d, m.groupValues[3])
            }
        }
        for ((i, w) in words.withIndex()) {
            when (w) {
                "today", "tonight" -> return plus(0)
                "tomorrow", "tmrw", "tmr", "tomorow" -> return plus(1)
            }
            // "in 3 days / 2 weeks"
            if (w == "in") {
                val n = words.getOrNull(i + 1)?.toIntOrNull()
                val unit = words.getOrNull(i + 2).orEmpty()
                if (n != null && n in 1..365) {
                    if (unit.startsWith("day") || unit == "d") return plus(n)
                    if (unit.startsWith("week") || unit == "w" || unit == "wk" || unit == "wks") return plus(n * 7)
                }
            }
            if (w == "next" && words.getOrNull(i + 1)?.startsWith("week") == true) {
                val toMonday = (Calendar.MONDAY - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
                return plus(if (toMonday == 0) 7 else toMonday)
            }
            // a weekday: the next one coming up
            dayIndex(w)?.let { target ->
                val ahead = (target + 1 - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
                return plus(if (ahead == 0) 7 else ahead)
            }
            // "oct 30", "october 30th", "30 oct"
            monthIndex(w)?.let { mo ->
                val d = words.getOrNull(i + 1)?.let { dayOfMonth(it) } ?: words.getOrNull(i - 1)?.let { dayOfMonth(it) }
                if (d != null) return resolve(today, mo, d, "")
            }
        }
        return null
    }

    /** Month/day (and maybe a year) to a date; with no year, this year or next if it already passed. */
    private fun resolve(today: Calendar, month: Int, day: Int, yearText: String): String {
        val c = midnight(today)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.MONTH, month)
        val explicit = yearText.toIntOrNull()
        if (explicit != null) c.set(Calendar.YEAR, if (explicit < 100) 2000 + explicit else explicit)
        c.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        if (explicit == null && c.before(today)) {
            c.add(Calendar.YEAR, 1)
            c.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return out(c)
    }
}
