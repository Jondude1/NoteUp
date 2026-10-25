package com.jondude.noteup.ai

/** Prompts behind the AI features. Kept in one place so they're easy to tune. */
object Twists {

    const val SYSTEM = """You live inside NoteUp, a paper-like note-taking app on a pen tablet.
You read the user's handwriting and meeting transcripts. Be brief and concrete: the reply is
shown on an e-ink style page, so use plain text, short lines, simple "-" bullets, no markdown headers,
no bold, no emoji."""

    /** Meeting minutes from transcript + handwritten notes. Ends with a parsable ACTIONS block. */
    fun minutes(transcript: String, handwriting: String) = """
Write meeting minutes from the material below.

Format exactly:
SUMMARY
(3-5 sentences)

KEY POINTS
- ...

DECISIONS
- ... (or "- none")

ACTIONS
- [owner if known] task (due date if mentioned)

Use the handwritten notes to see what the note-taker thought mattered most; weight those topics.

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
}
