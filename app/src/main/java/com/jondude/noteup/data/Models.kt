package com.jondude.noteup.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(12)

/** Page coordinates are in a fixed virtual space so notes look identical on both tablets. */
const val PAGE_W = 1000f
const val PAGE_H = 1414f // A4 ratio

@Serializable
enum class Template { BLANK, LINED, GRID, DOTS, CORNELL }

@Serializable
data class Notebook(
    val id: String = newId(),
    val title: String,
    val template: Template = Template.LINED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pageIds: List<String> = emptyList(),
)

/**
 * One pen stroke. [pts] is packed x,y,pressure triples in page space.
 * [times] are ms offsets from [t0] (needed for handwriting recognition).
 * If the stroke was written while recording, [recId]/[audioMs] link it to that moment in the audio.
 */
@Serializable
data class Stroke(
    val id: String = newId(),
    val pts: FloatArray,
    val times: LongArray,
    val t0: Long,
    val width: Float,
    val recId: String? = null,
    val audioMs: Long = -1,
    /** 0 = black, 1 = dark gray, 2 = light gray */
    val color: Int = 0,
    /** Highlighter: wide, translucent, drawn under the ink. */
    val marker: Boolean = false,
    /** A drawn check box (clean square). Tapping it ticks it; it is linked to a task. */
    val box: Boolean = false,
    val checked: Boolean = false,
    /** Task this check box stands for. */
    val taskId: String? = null,
)

/** Typed text placed on the page (diagram labels from "Clean up diagram"). */
@Serializable
data class PageText(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val w: Float,
    val text: String,
    val size: Float = 20f,
    val centered: Boolean = true,
)

/** Something Claude wrote back onto the page, in the margin or next to a selection. */
@Serializable
data class MarginNote(
    val id: String = newId(),
    val x: Float,
    val y: Float,
    val w: Float,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Page(
    val id: String = newId(),
    val notebookId: String,
    val strokes: List<Stroke> = emptyList(),
    val annotations: List<MarginNote> = emptyList(),
    val convertedText: String? = null,
    /** File name of an imported PDF page image drawn behind the ink. */
    val background: String? = null,
    val texts: List<PageText> = emptyList(),
    /** AI-generated title and topic tags. */
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

@Serializable
data class Recording(
    val id: String = newId(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val fileName: String,
    val notebookId: String? = null,
    val pageId: String? = null,
    val segments: List<Segment> = emptyList(),
    val summary: String? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class TaskItem(
    val id: String = newId(),
    val text: String,
    val done: Boolean = false,
    val source: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Due date as yyyy-MM-dd, if known. */
    val due: String? = null,
    val updatedAt: Long = 0,
)
