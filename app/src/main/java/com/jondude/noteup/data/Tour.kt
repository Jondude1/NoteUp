package com.jondude.noteup.data

/**
 * The in-app walkthrough. EVERY time a feature is added, append a step here with `since` = the current highest + 1.
 * People who haven't seen it yet get a "NEW" marker and can open just the new steps.
 */
object Tour {
    class Step(val since: Int, val title: String, val body: String, val where: String)

    val steps = listOf(
        Step(1, "Record a meeting", "Tap Record on a notebook page (or the Recordings tab). NoteUp listens, shows live words, and keeps the audio file. When you stop, it waits a few seconds for your last words, tidies the transcript and writes the meeting notes by itself.", "Notebook → Meeting, or Recordings tab"),
        Step(2, "Choose a note style", "Meeting notes can be written as general notes, stand-up, 1:1, support call, customer call, interview, lecture or brainstorm. Each ends with a list of action items that become tasks. You can rewrite any recording in a different style.", "Settings → note style chips; any recording → rewrite"),
        Step(3, "Sub-pages", "Nest pages inside pages. Open the page list to indent, outdent, move up or down, or add a sub-page under the current one.", "Notebook → Pages (outline)"),
        Step(4, "Sticky notes", "Add sticky notes in six styles (five colours plus a plain text box) for temporary info. Drag them anywhere, they follow zoom and pan, and they stay on the page until you delete them.", "Notebook toolbar → sticky"),
        Step(5, "Meeting tab on the page", "After a recording, the meeting notes attach to the page you had open. A tab along the bottom shows the notes, the transcript and the recording file.", "Bottom of the notebook page"),
        Step(6, "Mark a moment ★", "While recording, tap ★ to flag an important moment. The AI is told about your marks, so what was said around them gets pulled into the notes.", "Notebook toolbar ★ during a recording"),
        Step(7, "Today's page", "One tap opens (or creates) a page for today, ready for daily notes.", "Home → Today"),
        Step(8, "Studio: chat with your notebook", "Ask questions about everything in a notebook. Answers carry numbered citations that open the page, recording or source they came from.", "Notebook → Studio → Chat"),
        Step(9, "Sources", "Add pasted text, web links and PDFs (scans are read on the device) to a notebook. Each gets a short summary, topics and suggested questions.", "Studio → Sources"),
        Step(10, "Discover sources", "Give the AI a subject and it searches the web, reads 10 to 20 pages and lets you tick the ones to add.", "Studio → Sources → Discover sources"),
        Step(11, "Reports", "Make a briefing, study guide, blog post or your own custom report from a notebook. Save any result as pages.", "Studio → Make"),
        Step(12, "Read aloud", "Have meeting notes read to you.", "Meeting tab → Read aloud"),
        Step(13, "Updates", "NoteUp checks for new versions itself. When one is ready, tap the banner to install.", "Banner at the top of Home; Settings → Updates"),
        Step(14, "This walkthrough", "Open it any time from the Tour button. It's optional, and it gains a step whenever a new feature is added.", "Home → Tour"),
    )

    val latest get() = steps.maxOf { it.since }

    fun visible(newOnly: Boolean, seen: Int): List<Step> = if (newOnly) steps.filter { it.since > seen } else steps

    fun unseen(seen: Int) = steps.count { it.since > seen }
}
