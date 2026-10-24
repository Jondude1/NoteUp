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
}
