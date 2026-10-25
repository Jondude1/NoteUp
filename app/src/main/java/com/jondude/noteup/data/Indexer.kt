package com.jondude.noteup.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.ink.HandwritingRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Background handwriting reader. Pages you leave get read on-device so search works,
 * then (if enabled) the AI gives them a title and tags. One page at a time, oldest first.
 */
object Indexer {
    var pending by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val queued = HashSet<String>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { for ((pid, nbId) in queue) process(pid, nbId) }
        // catch up on anything not read yet
        scope.launch {
            val todo = withContext(Dispatchers.Main) {
                Store.allPages().filter { (_, _, p) -> needsText(p) }.map { (nb, _, p) -> p.id to nb.id }
            }
            todo.forEach { schedule(it.first, it.second) }
        }
    }

    private fun needsText(p: Page) = p.convertedText == null && p.strokes.any { !it.marker && !it.box }

    fun schedule(pageId: String, nbId: String) {
        synchronized(queued) { if (!queued.add(pageId)) return }
        scope.launch(Dispatchers.Main) { pending++ }
        queue.trySend(pageId to nbId)
    }

    private suspend fun process(pageId: String, nbId: String) {
        try {
            val page = withContext(Dispatchers.Main) { Store.page(pageId, nbId) }
            var updated = page
            if (needsText(page)) {
                val text = HandwritingRecognizer.recognize(page.strokes.filter { !it.marker }, Settings.language)
                updated = updated.copy(convertedText = text)
            }
            val text = updated.convertedText.orEmpty()
            val wantTags = Settings.autoTag && text.length >= 25 && AiClient.configured(Settings.backend) &&
                (updated.title == null || updated.tags.isEmpty())
            if (wantTags) {
                try {
                    val reply = AiClient.ask(Twists.SYSTEM, Twists.titleTags(text), null, 200)
                    Twists.jsonObject(reply)?.let { j ->
                        val o = Store.json.parseToJsonElement(j).jsonObject
                        val title = o["title"]?.jsonPrimitive?.content?.trim()?.take(60)
                        val tags = o["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().lowercase().takeIf { t -> t.isNotBlank() } }?.take(4)
                        updated = updated.copy(title = title ?: updated.title, tags = tags ?: updated.tags)
                    }
                } catch (_: Exception) { /* AI is optional; text search still works */ }
            }
            if (updated != page) withContext(Dispatchers.Main) {
                // only apply if the page wasn't edited meanwhile
                val now = Store.page(pageId, nbId)
                if (now.strokes.size == page.strokes.size && now.updatedAt == page.updatedAt) {
                    Store.savePage(now.copy(convertedText = updated.convertedText, title = updated.title, tags = updated.tags), touch = false)
                    // a notebook still called "Untitled" takes its first page's title
                    val nb = Store.notebook(nbId)
                    if (nb != null && nb.title == "Untitled" && nb.pageIds.firstOrNull() == pageId && updated.title != null) {
                        Store.updateNotebook(nb.copy(title = updated.title!!))
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(queued) { queued.remove(pageId) }
            withContext(Dispatchers.Main) { pending = (pending - 1).coerceAtLeast(0) }
        }
    }

    // ---------------- search ----------------

    class Hit(val nb: Notebook, val index: Int, val page: Page?, val recording: Recording?, val snippet: String)

    fun search(query: String, tag: String? = null): List<Hit> {
        val q = query.trim().lowercase()
        val hits = ArrayList<Hit>()
        for ((nb, i, p) in Store.allPages()) {
            if (tag != null && tag !in p.tags) continue
            if (q.isEmpty()) { if (tag != null) hits += Hit(nb, i, p, null, p.title ?: firstLine(p)); continue }
            val fields = listOfNotNull(p.title, p.convertedText, p.tags.joinToString(" "), nb.title) +
                p.annotations.map { it.text } + p.texts.map { it.text }
            val match = fields.firstOrNull { it.lowercase().contains(q) } ?: continue
            hits += Hit(nb, i, p, null, snippet(match, q))
        }
        if (q.isNotEmpty() && tag == null) for (r in Store.recordings) {
            val nb = r.notebookId?.let { Store.notebook(it) } ?: Notebook(id = "", title = "Recordings")
            val text = (listOf(r.title, r.summary.orEmpty()) + r.segments.map { it.text }).firstOrNull { it.lowercase().contains(q) } ?: continue
            hits += Hit(nb, 0, null, r, snippet(text, q))
        }
        return hits
    }

    fun allTags(): List<String> =
        Store.allPages().flatMap { it.third.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(16)

    private fun firstLine(p: Page) = p.convertedText?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun snippet(text: String, q: String): String {
        val flat = text.replace('\n', ' ')
        val at = flat.lowercase().indexOf(q).coerceAtLeast(0)
        val start = (at - 50).coerceAtLeast(0)
        val end = (at + q.length + 70).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }
}
