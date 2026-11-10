package com.jondude.noteup.ai

/** Prompts behind the AI features. Kept in one place so they're easy to tune. */
object Twists {

    const val SYSTEM = """You live inside NoteUp, a paper-like note-taking app on a pen tablet.
You read the user's handwriting and meeting transcripts. Be brief and concrete: the reply is
shown on an e-ink style page, so use plain text, short lines, simple "-" bullets, no markdown headers,
no bold, no emoji."""

    /** Meeting minutes from transcript + handwritten notes, in the chosen [style]. Ends with a parsable ACTIONS block. */
    fun minutes(transcript: String, handwriting: String, style: NoteStyle = NoteStyle.GENERAL, marks: String = "") = """
Write meeting notes from the material below (style: ${style.label}).

Format exactly:
${style.format}

Today is ${java.text.SimpleDateFormat("EEEE yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}; turn "Friday", "next week" etc. into dates.

Use the handwritten notes to see what the note-taker thought mattered most; weight those topics.${if (marks.isNotBlank()) "\nThe note-taker starred these moments as important, so make sure they are covered:\n$marks" else ""}

TRANSCRIPT:
${transcript.ifBlank { "(no recording)" }}

HANDWRITTEN NOTES:
${handwriting.ifBlank { "(none)" }}
""".trimIndent()

    /** Pulls "- item" lines out of the ACTIONS section. */
    fun parseActions(minutes: String): List<String> {
        val after = minutes.substringAfter("ACTIONS", "")
        return after.lines().map { it.trim() }
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-").trim() }
            .filter { it.isNotBlank() && !it.equals("none", true) }
    }

    /** Ask about a lasso selection or a whole page (image attached). */
    fun askPage(question: String, recognized: String) = """
The image is handwriting from my page${if (recognized.isNotBlank()) ", roughly read by OCR as: <<$recognized>>" else "."}

${question.ifBlank { "If this is a question, answer it. If it's a problem, solve it. If it's notes, add the one most useful thing I'm missing." }}

Answer in at most 80 words — it will be written in the margin of the page.
""".trimIndent()

    /** Clean up OCR using the image as ground truth. */
    fun tidy(ocr: String) = """
The image is a handwritten page. A recognizer read it as:
<<
$ocr
>>
Return the corrected text only: fix misread words using the image, keep the author's wording,
keep line breaks and list structure, render math plainly. No commentary.
""".trimIndent()

    /** "Second brain" — answer from across all notes. */
    fun askNotebook(question: String, corpus: String) = """
Here are my notes and transcripts (each starts with a ### header naming where it came from):

$corpus

Question: $question

Answer from the notes. Say which note each point comes from. If the notes don't cover it, say so.
""".trimIndent()

    /** Turns a margin answer or note into flashcards. */
    fun flashcards(text: String) = """
Make 3-6 study flashcards from these notes. Format each as:
Q: ...
A: ...

NOTES:
$text
""".trimIndent()

    /** Page title + tags, as JSON. */
    fun titleTags(text: String) = """
Give this handwritten page a short title (max 6 words) and 1-4 lowercase topic tags (one or two words each).
Reply with JSON only: {"title": "...", "tags": ["...", "..."]}

PAGE:
${text.take(4000)}
""".trimIndent()

    /** Commands written next to a circled word/phrase. */
    val COMMANDS = listOf("define", "translate", "explain", "expand", "summarize", "summarise", "solve", "rewrite", "simplify", "ask", "?")

    fun command(cmd: String, extra: String, ocr: String) = when (cmd) {
        "define" -> "Define the circled word or phrase in one or two plain sentences."
        "translate" -> "Translate the circled text ${if (extra.isNotBlank()) "into $extra" else "into English (or into Spanish if it's already English)"}. Give only the translation, then a one-line pronunciation hint if useful."
        "explain" -> "Explain the circled item simply, in at most 3 sentences."
        "expand" -> "Expand on the circled idea with 3-4 short bullet points I could add to my notes."
        "summarize", "summarise" -> "Summarize the circled notes in 2-3 sentences."
        "solve" -> "Solve the circled problem. Show the key steps briefly, then the answer."
        "rewrite" -> "Rewrite the circled text more clearly, keeping the meaning."
        "simplify" -> "Simplify the circled expression or sentence. Give just the result and one line of how."
        else -> extra.ifBlank { "Answer or respond to the circled item." }
    } + (if (ocr.isNotBlank()) "\n\nOCR reading of the circled ink (may have errors): <<$ocr>>" else "") +
        "\n\nAnswer in at most 80 words — it will be written in the margin."

    /** Parses "translate to french" → ("translate", "french"); null when it's not a command. */
    fun parseCommand(written: String): Pair<String, String>? {
        val t = written.trim().lowercase().replace("\n", " ").trim('.', ':', '!', ' ')
        if (t == "?" || t == "??") return "ask" to ""
        val first = t.substringBefore(' ')
        val rest = t.substringAfter(' ', "").removePrefix("to ").removePrefix("into ").trim()
        val cmd = COMMANDS.firstOrNull { it == first } ?: return null
        return cmd to rest
    }

    /** Sketched diagram → clean nodes and edges in image pixel coordinates. */
    fun diagram(w: Int, h: Int) = """
The image ($w x $h pixels) is a hand-sketched diagram. Describe it so it can be redrawn neatly.
Reply with JSON only, using pixel coordinates of this image:
{"nodes":[{"id":"a","shape":"rect|ellipse|diamond|none","x":0,"y":0,"w":0,"h":0,"label":"text inside"}],
 "edges":[{"from":"a","to":"b","label":"","arrow":true}]}
- x,y is the top-left of each shape; keep shapes where they are, but align ones that are almost in a row or column
- use "none" for free-standing text labels
- read handwritten labels carefully; keep them short
""".trimIndent()

    /** Extracts the first {...} JSON object from a model reply. */
    fun jsonObject(reply: String): String? {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        return if (a >= 0 && b > a) reply.substring(a, b + 1) else null
    }

    /** ACTIONS lines with an optional [due: yyyy-MM-dd]. */
    fun parseActionsWithDue(minutes: String): List<Pair<String, String?>> = parseActions(minutes).map { line ->
        val m = Regex("""\[due:\s*(\d{4}-\d{2}-\d{2})\]""", RegexOption.IGNORE_CASE).find(line)
        val text = line.replace(Regex("""\s*\[due:[^\]]*\]""", RegexOption.IGNORE_CASE), "").trim()
        text to m?.groupValues?.get(1)
    }

    /** Morning brief. */
    fun brief(material: String) = """
Write my daily brief from the material below. Plain text, short lines.

Format:
TODAY
(one or two sentences: what kind of day it is and the single most important thing)

SCHEDULE
- time — event   (skip this section if nothing is scheduled)

FOCUS
- the 3 most important open tasks, overdue first

FROM YESTERDAY
- 2-4 bullets of what was decided, learned or left unfinished in my notes and meetings

$material
""".trimIndent()

    /** Split a transcript into speaker turns. Input lines are "index: text". */
    fun speakers(numbered: String) = """
This is a meeting transcript, split into numbered chunks that may contain more than one speaker.
Work out who is speaking. Use real names when people introduce themselves or are addressed by name;
otherwise "Speaker 1", "Speaker 2", ... Keep the same label for the same person throughout.

Reply with JSON only:
{"turns":[{"chunk":0,"speaker":"Speaker 1","text":"exact words from that chunk"}, ...]}
Every word of every chunk must appear exactly once, in order. Split a chunk into several turns when the speaker changes inside it.

TRANSCRIPT:
$numbered
""".trimIndent()

    /** Fixes what a speech recogniser got wrong, using what the notes say about the topic. */
    fun cleanTranscript(numbered: String, context: String) = """
Below are numbered lines from an automatic speech-to-text transcript of a meeting. They contain recognition mistakes.
Fix them: wrong homophones ("their/there"), misheard names, product names and jargon, numbers, dates, missing or wrong
capital letters and punctuation, and obvious run-together or split words. Use the context to decide what was really said.
Do NOT summarise, reword, add, remove or merge lines. Keep each line's meaning and order. If a line is already right, return it unchanged.
Never invent content you cannot infer from the line itself.

CONTEXT (names and terms that are likely to come up):
$context

Reply with JSON only: {"lines":[{"i":0,"text":"corrected line"}, ...]} with one entry for every input line.

LINES:
$numbered
""".trimIndent()
}
