package com.jondude.noteup.data

import com.jondude.noteup.ai.Splitter
import com.jondude.noteup.ink.HandwritingRecognizer

/** Everything the AI is allowed to read for a question, cut into numbered excerpts so answers can cite them. */
object Corpus {
    class Chunk(val ref: CiteRef, val text: String)

    /** [nbId] = one notebook (its pages, recordings made on it, and its sources); null = everything. */
    fun build(nbId: String?, query: String? = null, maxChars: Int = 110_000): List<Chunk> {
        val items = ArrayList<Pair<CiteRef, String>>() // ref.n filled in below
        val notebooks = Store.notebooks.filter { nbId == null || it.id == nbId }
        fun ref(kind: String, id: String, label: String, index: Int = 0) = CiteRef(0, kind, id, label, index)

        // 1. handwritten (converted), typed, sticky and margin text, page by page
        for (nb in notebooks) nb.pageIds.forEachIndexed { i, pid ->
            val p = Store.page(pid, nb.id)
            val text = buildString {
                p.convertedText?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
                p.texts.forEach { append(it.text).append('\n') }
                p.stickies.filter { it.text.isNotBlank() }.forEach { append("(sticky note) ").append(it.text).append('\n') }
                p.annotations.forEach { append("(AI margin note) ").append(it.text).append('\n') }
            }.trim()
            if (text.isNotEmpty()) {
                val label = if (nbId == null) "${nb.title}, page ${i + 1}" else "Page ${i + 1}${p.title?.let { " ($it)" } ?: ""}"
                Splitter.split(text, 3000).forEach { items.add(ref("page", nb.id, label, i) to it) }
            }
        }
        val recs = Store.recordings.filter { nbId == null || it.notebookId == nbId }
        // 2. meeting notes
        for (r in recs) r.summary?.takeIf { it.isNotBlank() }?.let { items.add(ref("rec", r.id, "Meeting notes: ${r.title}") to it) }
        // 3. sources
        for (s in Store.sources.filter { nbId == null || it.notebookId == nbId }) {
            val parts = Splitter.split(s.text, 3000)
            parts.forEachIndexed { k, part ->
                items.add(ref("src", s.id, "Source: ${s.title}${if (parts.size > 1) " (part ${k + 1})" else ""}") to part)
            }
        }
        // 4. transcripts
        for (r in recs) if (r.segments.isNotEmpty()) {
            val full = r.segments.joinToString(" ") { it.text }
            val parts = Splitter.split(full, 3000)
            parts.forEachIndexed { k, part ->
                items.add(ref("rec", r.id, "Transcript: ${r.title}${if (parts.size > 1) " (part ${k + 1})" else ""}") to part)
            }
        }
        val picked = com.jondude.noteup.ai.Retrieve.select(items.map { (r, t) -> com.jondude.noteup.ai.Retrieve.Item(r.kind + r.id, t) }, query, maxChars)
        return picked.mapIndexed { n, (i, text) -> Chunk(items[i].first.copy(n = n + 1), text) }
    }

    fun prompt(chunks: List<Chunk>): String =
        com.jondude.noteup.ai.Cites.format(chunks.map { Triple(it.ref.n, it.ref.label, it.text) })

    /** Handwritten pages not yet turned into text. */
    fun unreadPages(nbId: String?): Int = Store.notebooks.filter { nbId == null || it.id == nbId }.sumOf { nb ->
        nb.pageIds.count { pid -> Store.page(pid, nb.id).let { it.convertedText == null && it.strokes.any { s -> !s.marker && !s.box } } }
    }

    suspend fun readPages(nbId: String?, progress: (Int, Int) -> Unit) {
        val total = unreadPages(nbId)
        var done = 0
        for (nb in Store.notebooks.toList().filter { nbId == null || it.id == nbId }) for (pid in nb.pageIds) {
            val p = Store.page(pid, nb.id)
            if (p.convertedText == null && p.strokes.any { !it.marker && !it.box }) {
                progress(++done, total)
                val t = try { HandwritingRecognizer.recognize(p.strokes.filter { !it.marker && !it.box }, Settings.language) } catch (e: Exception) { continue }
                Store.savePage(p.copy(convertedText = t), touch = false)
            }
        }
    }
}
