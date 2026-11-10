package com.jondude.noteup.data

import kotlinx.serialization.Serializable

/** Reference material added to a notebook: pasted text, a web page, a PDF, a research report. */
@Serializable
data class Source(
    val id: String = newId(),
    val notebookId: String,
    val title: String,
    /** text | web | pdf | research */
    val kind: String,
    val text: String,
    val url: String? = null,
    /** Source guide, written by the AI. */
    val summary: String? = null,
    val topics: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Where a numbered citation points. */
@Serializable
data class CiteRef(val n: Int, val kind: String, val id: String, val label: String, val index: Int = 0)

/**
 * Something the AI made for a notebook and that is kept until deleted: a chat answer, a report,
 * a study set, a deck and so on. [body] is text or JSON, depending on [kind].
 */
@Serializable
data class StudioItem(
    val id: String = newId(),
    val notebookId: String,
    /** chat | guide | briefing | study | blog | custom | cards | quiz | map | table | deck | infographic | audio | research */
    val kind: String,
    /** The question, for a chat answer; otherwise a title. */
    val title: String,
    val body: String,
    val refs: List<CiteRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
