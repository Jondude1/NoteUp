package com.jondude.noteup.data

import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Discover
import com.jondude.noteup.ai.Twists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Finds and reads web pages on a topic: Claude's web search when available, otherwise AI-written queries run on DuckDuckGo. */
object Discoverer {
    class Found(val title: String, val url: String, val text: String)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun ddg(query: String): List<Discover.Candidate> {
        val req = Request.Builder().url("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NoteUp").build()
        return http.newCall(req).execute().use { r -> if (r.isSuccessful) Discover.parseDuckDuckGo(r.body?.string().orEmpty()) else emptyList() }
    }

    private suspend fun queries(topic: String): List<String> {
        if (!AiClient.configured(Settings.backend)) return listOf(topic)
        return try {
            val reply = AiClient.ask("You write web search queries. Reply only with JSON.",
                "Write 6 varied web search queries that together cover this subject from different angles (basics, recent developments, data and statistics, expert analysis, criticism, practical guides). " +
                    "Reply as {\"queries\":[\"...\"]}.\n\nSubject: $topic", null, 400)
            val arr = Store.json.parseToJsonElement(Twists.jsonObject(reply) ?: return listOf(topic)).jsonObject["queries"]?.jsonArray
            (arr?.map { it.jsonPrimitive.content }.orEmpty().filter { it.isNotBlank() } + topic).distinct()
        } catch (e: Exception) { listOf(topic) }
    }

    /**
     * Returns up to [count] readable pages, calling [progress] as it goes. [existing] are URLs already in the notebook.
     */
    suspend fun discover(topic: String, count: Int, existing: Set<String>, progress: (String) -> Unit): List<Found> {
        progress("Searching the web…")
        var candidates: List<Discover.Candidate> = emptyList()
        if (Settings.backend == AiBackend.CLAUDE && AiClient.configured(AiBackend.CLAUDE)) {
            try { candidates = AiClient.searchClaude(topic, 10) } catch (e: Exception) { /* fall back below */ }
        }
        if (candidates.size < count) {
            val qs = queries(topic)
            val more = withContext(Dispatchers.IO) { qs.map { q -> try { ddg(q) } catch (e: Exception) { emptyList() } } }
            candidates = candidates + Discover.interleave(more)
        }
        val picked = Discover.pick(candidates, count * 2 + 4, perDomain = 2, exclude = existing)
        if (picked.isEmpty()) throw IllegalStateException("I couldn't find any web results. Check your internet connection.")

        val out = ArrayList<Found>()
        var done = 0
        val gate = Semaphore(4)
        coroutineScope {
            picked.map { c ->
                async {
                    gate.withPermit {
                        val f = try { SourceReader.web(c.url).let { (t, body) -> Found(if (t.length < 4) c.title else t, c.url, body) } } catch (e: Exception) { null }
                        synchronized(out) { if (f != null && f.text.length >= 600) out.add(f); done++; progress("Reading pages… $done of ${picked.size} · ${minOf(out.size, count)} usable") }
                    }
                }
            }.awaitAll()
        }
        if (out.isEmpty()) throw IllegalStateException("None of the pages I found could be read.")
        // keep search order
        val order = picked.map { Discover.normalize(it.url) }
        return out.sortedBy { order.indexOf(Discover.normalize(it.url)) }.take(count)
    }
}
