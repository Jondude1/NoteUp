package com.jondude.noteup.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Cites
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.audio.Speaker
import com.jondude.noteup.data.CiteRef
import com.jondude.noteup.data.Corpus
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Source
import com.jondude.noteup.data.SourceReader
import com.jondude.noteup.data.Store
import com.jondude.noteup.data.StudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A notebook's workroom: ask questions across its pages, recordings and added sources (answers cite where
 * they came from), manage sources, and make reports from it.
 */
@Composable
fun StudioScreen(nbId: String, onBack: () -> Unit, onOpenPage: (String, Int) -> Unit, onOpenRecording: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nb = Store.notebooks.firstOrNull { it.id == nbId }
    if (nb == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }

    var tab by remember { mutableStateOf(0) }
    var everything by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewSource by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<com.jondude.noteup.data.Discoverer.Found>>(emptyList()) }
    var topicText by remember { mutableStateOf(nb.title) }
    var wanted by remember { mutableStateOf(15) }
    var studying by remember { mutableStateOf<StudioItem?>(null) }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    fun aiReady(): Boolean {
        if (AiClient.configured(Settings.backend)) return true
        error = "Set up an AI in Settings first."; return false
    }

    fun openRef(r: CiteRef) {
        when (r.kind) {
            "page" -> onOpenPage(r.id, r.index)
            "rec" -> onOpenRecording(r.id)
            "src" -> viewSource = r.id
        }
    }

    fun ask(q: String) {
        if (q.isBlank() || busy != null || !aiReady()) return
        busy = "Reading your notebook…"; error = null
        scope.launch {
            try {
                val chunks = withContext(Dispatchers.Default) { Corpus.build(if (everything) null else nbId, q, 45_000) }
                val history = Store.studioOf(nbId).filter { it.kind == "chat" }.takeLast(2).map { it.title to it.body.take(500) }
                busy = "Thinking…"
                val ans = AiClient.ask(Twists.SYSTEM, Twists.askCited(q, Corpus.prompt(chunks), history), null, 1500)
                val refs = Cites.numbers(ans).mapNotNull { n -> chunks.firstOrNull { it.ref.n == n }?.ref }
                Store.upsertStudio(StudioItem(notebookId = nbId, kind = "chat", title = q, body = ans.trim(), refs = refs))
                question = ""
            } catch (e: Exception) { error = e.message ?: "Something went wrong." }
            busy = null
        }
    }

    fun parseGuide(reply: String): Triple<String, List<String>, List<String>>? = try {
        val o = Store.json.parseToJsonElement(Twists.jsonObject(reply) ?: return null).jsonObject
        Triple(
            o["summary"]?.jsonPrimitive?.content.orEmpty(),
            o["topics"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            o["questions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    } catch (e: Exception) { null }

    suspend fun guideFor(s: Source) {
        if (!AiClient.configured(Settings.backend)) return
        val g = parseGuide(AiClient.ask(Twists.SYSTEM, Twists.guide("source", s.text), null, 450, light = true)) ?: return
        Store.upsertSource((Store.sources.firstOrNull { it.id == s.id } ?: s).copy(summary = g.first, topics = g.second, questions = g.third))
    }

    fun addSource(title: String, kind: String, text: String, url: String? = null) {
        val s = Source(notebookId = nbId, title = title.ifBlank { "Untitled source" }, kind = kind, text = text, url = url)
        Store.upsertSource(s)
        scope.launch { try { guideFor(s) } catch (_: Exception) {} }
    }

    fun makeReport(kind: String, title: String, custom: String = "") {
        if (busy != null || !aiReady()) return
        busy = "Writing $title…"; error = null
        scope.launch {
            try {
                val chunks = withContext(Dispatchers.Default) { Corpus.build(nbId, custom.ifBlank { null }, 70_000) }
                if (chunks.isEmpty()) throw IllegalStateException("There's nothing in this notebook to work from yet.")
                val text = AiClient.ask(Twists.SYSTEM, Twists.report(kind, Corpus.prompt(chunks), custom), null, 3500)
                val refs = Cites.numbers(text).mapNotNull { n -> chunks.firstOrNull { it.ref.n == n }?.ref }
                Store.upsertStudio(StudioItem(notebookId = nbId, kind = kind, title = title, body = text.trim(), refs = refs))
            } catch (e: Exception) { error = e.message ?: "Something went wrong." }
            busy = null
        }
    }

    fun overview() {
        if (busy != null || !aiReady()) return
        busy = "Reading the whole notebook…"; error = null
        scope.launch {
            try {
                val chunks = withContext(Dispatchers.Default) { Corpus.build(nbId, null, 60_000) }
                if (chunks.isEmpty()) throw IllegalStateException("There's nothing in this notebook to summarise yet.")
                val g = parseGuide(AiClient.ask(Twists.SYSTEM, Twists.guide("notebook", Corpus.prompt(chunks)), null, 500))
                    ?: throw IllegalStateException("The AI didn't return an overview. Try again.")
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("summary", g.first)
                    putJsonArray("topics") { g.second.forEach { add(it) } }
                    putJsonArray("questions") { g.third.forEach { add(it) } }
                }.toString()
                Store.studioOf(nbId).filter { it.kind == "guide" }.forEach { Store.deleteStudio(it.id) }
                Store.upsertStudio(StudioItem(notebookId = nbId, kind = "guide", title = "Notebook overview", body = body))
            } catch (e: Exception) { error = e.message ?: "Something went wrong." }
            busy = null
        }
    }

    fun makeStudy(kind: String, title: String, count: Int, diff: String, ask: String) {
        if (busy != null || !aiReady()) return
        busy = "Making $title…"; error = null
        scope.launch {
            try {
                val chunks = withContext(Dispatchers.Default) { Corpus.build(nbId, ask.ifBlank { null }, 50_000) }
                if (chunks.isEmpty()) throw IllegalStateException("There's nothing in this notebook to work from yet.")
                val ex = Corpus.prompt(chunks)
                val prompt = when (kind) {
                    "cards" -> Twists.cardsJson(ex, count, diff)
                    "quiz" -> Twists.quizJson(ex, count, diff)
                    "map" -> Twists.mapJson(ex)
                    else -> Twists.tableJson(ex, ask)
                }
                var json: String? = null
                for (attempt in 0..1) {
                    val reply = AiClient.ask(Twists.SYSTEM, prompt, null, if (kind == "table") 2500 else 2000)
                    val ok = when (kind) {
                        "cards" -> com.jondude.noteup.ai.Study.cards(reply).size >= 2
                        "quiz" -> com.jondude.noteup.ai.Study.quiz(reply).size >= 2
                        "map" -> com.jondude.noteup.ai.Study.tree(reply) != null
                        else -> com.jondude.noteup.ai.Study.table(reply) != null
                    }
                    if (ok) { json = com.jondude.noteup.ai.MiniJson.extract(reply); break }
                }
                if (json == null) throw IllegalStateException("The AI's answer wasn't usable. Try again, or add more material first.")
                Store.upsertStudio(StudioItem(notebookId = nbId, kind = kind, title = title, body = json))
            } catch (e: Exception) { error = e.message ?: "Something went wrong." }
            busy = null
        }
    }

    fun putOnPages(item: StudioItem) {
        val drawings = when (item.kind) {
            "map" -> com.jondude.noteup.ai.Study.tree(item.body)?.let { listOf(com.jondude.noteup.ai.MindLayout.layout(it)) }
            else -> com.jondude.noteup.ai.Study.table(item.body)?.let { (c, r) -> com.jondude.noteup.ai.TableLayout.layout(c, r) }
        }
        if (drawings.isNullOrEmpty()) { error = "Couldn't lay that out."; return }
        val idx = Store.addDrawingPages(nbId, item.title, drawings)
        toast("Added ${drawings.size} page(s).")
        onOpenPage(nbId, idx)
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = "Reading the PDF…"; error = null
            scope.launch {
                try {
                    val text = SourceReader.pdf(ctx, uri) { i, n -> busy = "Reading the PDF… page $i of $n" }
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "PDF"
                    addSource(name, "pdf", text)
                    tab = 1
                } catch (e: Exception) { error = e.message ?: "Couldn't read that PDF." }
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.White)) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolItem("‹ Notebook") { Speaker.stop(); onBack() }
            T("${nb.title} · Studio", bold = true, size = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Chat", "Sources", "Make").forEachIndexed { i, label -> ToolItem(label, on = tab == i) { tab = i } }
            Spacer(Modifier.weight(1f))
        }
        HRule()
        busy?.let { T(it, italic = true, color = Ink.Gray, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        error?.let { T(it, italic = true, color = Ink.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }

        when (tab) {
            0 -> ChatTab(nbId, everything, { everything = it }, question, { question = it }, busy != null, ::ask, ::openRef, onSaved = { idx -> toast("Saved as pages."); onOpenPage(nbId, idx) },
                onReadPages = {
                    busy = "Reading handwriting…"
                    scope.launch {
                        try { Corpus.readPages(if (everything) null else nbId) { i, n -> busy = "Reading handwriting… $i of $n" } } catch (e: Exception) { error = e.message }
                        busy = null
                    }
                })
            1 -> SourcesTab(nbId, busy != null, onPaste = { dialog = "paste" }, onWeb = { dialog = "web" }, onPdf = { pickPdf.launch(arrayOf("application/pdf")) }, onDiscover = { dialog = "discover" },
                onOverview = ::overview, onView = { viewSource = it }, onAsk = { q -> tab = 0; ask(q) },
                onGuide = { s -> busy = "Reading ${s.title}…"; scope.launch { try { guideFor(s) } catch (e: Exception) { error = e.message }; busy = null } })
            else -> MakeTab(nbId, busy != null, ::makeReport, ::makeStudy, { studying = it }, ::putOnPages, ::openRef, onSaved = { idx -> toast("Saved as pages."); onOpenPage(nbId, idx) })
        }
    }

    // ---- dialogs ----
    when (dialog) {
        "discover" -> {
            val picks = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
            InkDialog("Discover sources", onDismiss = { if (busy == null) dialog = null }) {
                if (found.isEmpty()) {
                    T("The AI searches the web for pages on your subject, reads them and lets you choose which to add.", size = 14.sp, color = Ink.Gray)
                    Spacer(Modifier.height(10.dp))
                    InkField(topicText, { topicText = it }, hint = "Subject to research", singleLine = false, minLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("How many", size = 14.sp)
                        listOf(10, 15, 20).forEach { n -> InkButton("$n", small = true, on = wanted == n) { wanted = n } }
                    }
                    busy?.let { T(it, italic = true, color = Ink.Gray, modifier = Modifier.padding(top = 10.dp)) }
                    error?.let { T(it, italic = true, modifier = Modifier.padding(top = 10.dp)) }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton(if (busy == null) "Find sources" else "Working…", on = true, enabled = busy == null && topicText.isNotBlank()) {
                            error = null; busy = "Searching the web…"
                            scope.launch {
                                try {
                                    val have = Store.sourcesOf(nbId).mapNotNull { it.url }.toSet()
                                    found = com.jondude.noteup.data.Discoverer.discover(topicText.trim(), wanted, have) { busy = it }
                                    picks.clear(); picks.addAll(found.indices)
                                } catch (e: Exception) { error = e.message ?: "Search failed." }
                                busy = null
                            }
                        }
                        InkButton("Cancel", enabled = busy == null) { dialog = null }
                    }
                } else {
                    T("Found ${found.size}. Untick any you don't want.", size = 14.sp, color = Ink.Gray)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        found.forEachIndexed { i, f ->
                            val on = i in picks
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { if (on) picks.remove(i) else picks.add(i) }, verticalAlignment = Alignment.Top) {
                                T(if (on) "☑" else "☐", size = 20.sp, modifier = Modifier.padding(end = 10.dp))
                                Column(Modifier.weight(1f)) {
                                    T(f.title, bold = true, size = 15.sp, maxLines = 2)
                                    T(com.jondude.noteup.ai.Discover.domain(f.url) + " · ${f.text.length / 1000}k chars", size = 12.sp, sans = true, color = Ink.Gray)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("Add ${picks.size}", on = true, enabled = picks.isNotEmpty()) {
                            val chosen = picks.sorted().map { found[it] }
                            chosen.forEach { f -> Store.upsertSource(Source(notebookId = nbId, title = f.title, kind = "web", text = f.text, url = f.url)) }
                            found = emptyList(); dialog = null; tab = 1
                            toast("Added ${chosen.size} sources. Writing guides…")
                            scope.launch { Store.sourcesOf(nbId).filter { it.summary == null }.forEach { try { guideFor(it) } catch (_: Exception) {} } }
                        }
                        InkButton("Search again") { found = emptyList() }
                        InkButton("Close") { found = emptyList(); dialog = null }
                    }
                }
            }
        }
        "paste" -> {
            var title by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            InkDialog("Paste text as a source", onDismiss = { dialog = null }) {
                InkField(title, { title = it }, hint = "Title")
                Spacer(Modifier.height(10.dp))
                InkField(text, { text = it }, hint = "Paste or type the text", singleLine = false, minLines = 6)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkButton("Add", on = true, enabled = text.length >= 20) { addSource(title.ifBlank { text.take(40) }, "text", text.trim()); dialog = null }
                    InkButton("Cancel") { dialog = null }
                }
            }
        }
        "web" -> {
            var url by remember { mutableStateOf("") }
            InkDialog("Add a web page", onDismiss = { dialog = null }) {
                InkField(url, { url = it }, hint = "https://…")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InkButton("Add", on = true, enabled = url.length > 3) {
                        val u = url; dialog = null; busy = "Reading the page…"; error = null
                        scope.launch {
                            try { val (t, body) = SourceReader.web(u); addSource(t, "web", body, u) } catch (e: Exception) { error = e.message ?: "Couldn't read that page." }
                            busy = null
                        }
                    }
                    InkButton("Cancel") { dialog = null }
                }
            }
        }
    }

    studying?.let { StudyDialog(it) { studying = null } }

    viewSource?.let { id ->
        val s = Store.sources.firstOrNull { it.id == id }
        if (s != null) InkDialog(s.title, onDismiss = { viewSource = null }) {
            s.url?.let { T(it, size = 12.sp, sans = true, color = Ink.Gray, maxLines = 1) }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) { T(s.text.take(30_000), size = 15.sp) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Copy", small = true) { copyText(ctx, s.text) }
                InkButton("Close", small = true) { viewSource = null }
            }
        }
    }
}

private fun dateOf(ms: Long) = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

@Composable
private fun CiteChips(refs: List<CiteRef>, onCite: (CiteRef) -> Unit) {
    if (refs.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        refs.forEach { r -> InkButton("[${r.n}] ${r.label.take(28)}", small = true) { onCite(r) } }
    }
}

@Composable
private fun ChatTab(
    nbId: String, everything: Boolean, setEverything: (Boolean) -> Unit,
    question: String, setQuestion: (String) -> Unit, busy: Boolean,
    ask: (String) -> Unit, onCite: (CiteRef) -> Unit, onSaved: (Int) -> Unit, onReadPages: () -> Unit,
) {
    val ctx = LocalContext.current
    val chats = Store.studioOf(nbId).filter { it.kind == "chat" }
    val unread = Corpus.unreadPages(if (everything) null else nbId)
    val suggestions = (Store.sourcesOf(nbId).flatMap { it.questions }).take(4)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), reverseScrolling = true).padding(horizontal = 20.dp, vertical = 10.dp)) {
            if (chats.isEmpty()) {
                T("Ask anything about this notebook. Answers use your pages, recordings and sources, and every point shows where it came from. Tap a source chip to open it.",
                    color = Ink.Gray, size = 15.sp)
                suggestions.forEach { q ->
                    Spacer(Modifier.height(8.dp))
                    InkButton(q, small = true, enabled = !busy) { ask(q) }
                }
            }
            chats.forEach { c ->
                Spacer(Modifier.height(14.dp))
                T(c.title, bold = true, size = 17.sp)
                Spacer(Modifier.height(4.dp))
                T(c.body, size = 16.sp)
                CiteChips(c.refs, onCite)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    InkButton("Save as page", small = true) { onSaved(Store.addTextPages(nbId, c.title.take(60), c.body)) }
                    InkButton("Copy", small = true) { copyText(ctx, c.body) }
                    InkButton(if (Speaker.speaking) "■ Stop" else "🔊 Read", small = true, on = Speaker.speaking) { if (Speaker.speaking) Speaker.stop() else Speaker.speak(ctx, c.body) }
                    InkButton("Delete", small = true) { Store.deleteStudio(c.id) }
                }
                HRule(thickness = 1.dp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        HRule()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton("This notebook", small = true, on = !everything) { setEverything(false) }
                InkButton("All notebooks", small = true, on = everything) { setEverything(true) }
                if (unread > 0) InkButton("Read $unread handwritten page${if (unread == 1) "" else "s"} first", small = true, enabled = !busy, onClick = onReadPages)
                Spacer(Modifier.weight(1f))
                if (chats.isNotEmpty()) InkButton("Clear chat", small = true) { chats.forEach { Store.deleteStudio(it.id) } }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                InkField(question, setQuestion, Modifier.weight(1f), hint = "Ask about this notebook…", singleLine = false)
                InkButton("Ask", on = true, enabled = !busy && question.isNotBlank()) { ask(question) }
            }
        }
    }
}

@Composable
private fun SourcesTab(
    nbId: String, busy: Boolean,
    onPaste: () -> Unit, onWeb: () -> Unit, onPdf: () -> Unit, onOverview: () -> Unit, onDiscover: () -> Unit,
    onView: (String) -> Unit, onAsk: (String) -> Unit, onGuide: (Source) -> Unit,
) {
    val ctx = LocalContext.current
    val sources = Store.sourcesOf(nbId)
    val overview = Store.studioOf(nbId).firstOrNull { it.kind == "guide" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        T("Your pages, handwriting and recordings in this notebook are always included. Add anything else you want the AI to read here.", color = Ink.Gray, size = 15.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Discover sources", small = true, on = true, enabled = !busy, onClick = onDiscover)
            InkButton("+ Paste text", small = true, enabled = !busy, onClick = onPaste)
            InkButton("+ Web link", small = true, enabled = !busy, onClick = onWeb)
            InkButton("+ PDF", small = true, enabled = !busy, onClick = onPdf)
            InkButton(if (overview == null) "Notebook overview" else "Refresh overview", small = true, enabled = !busy, onClick = onOverview)
        }
        overview?.let { o ->
            Spacer(Modifier.height(16.dp))
            val obj = try { Store.json.parseToJsonElement(o.body).jsonObject } catch (e: Exception) { null }
            val summary = obj?.get("summary")?.jsonPrimitive?.content.orEmpty()
            val topics = obj?.get("topics")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val qs = obj?.get("questions")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            SectionLabel("Notebook overview")
            T(summary, size = 16.sp)
            if (topics.isNotEmpty()) T(topics.joinToString(" · "), size = 13.sp, sans = true, color = Ink.Gray, modifier = Modifier.padding(top = 4.dp))
            qs.forEach { q -> Spacer(Modifier.height(6.dp)); InkButton(q, small = true, enabled = !busy) { onAsk(q) } }
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("Sources (${sources.size})")
        if (sources.isEmpty()) T("None yet.", italic = true, color = Ink.Gray)
        sources.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(s.title, bold = true, size = 17.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    T("${s.kind} · ${s.text.length / 1000}k chars", size = 12.sp, sans = true, color = Ink.Gray)
                }
                if (s.summary != null) {
                    T(s.summary, size = 15.sp, modifier = Modifier.padding(top = 2.dp))
                    if (s.topics.isNotEmpty()) T(s.topics.joinToString(" · "), size = 13.sp, sans = true, color = Ink.Gray, modifier = Modifier.padding(top = 2.dp))
                    s.questions.forEach { q -> Spacer(Modifier.height(5.dp)); InkButton(q, small = true, enabled = !busy) { onAsk(q) } }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    InkButton("View", small = true) { onView(s.id) }
                    if (s.summary == null) InkButton("Write guide", small = true, enabled = !busy) { onGuide(s) }
                    InkButton("Delete", small = true) { Store.deleteSource(s.id) }
                }
            }
            HRule(thickness = 1.dp)
        }
    }
}

@Composable
private fun MakeTab(nbId: String, busy: Boolean, make: (String, String, String) -> Unit, makeStudy: (String, String, Int, String, String) -> Unit, onStudy: (StudioItem) -> Unit, onPut: (StudioItem) -> Unit, onCite: (CiteRef) -> Unit, onSaved: (Int) -> Unit) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val items = Store.studioOf(nbId).filter { it.kind !in setOf("chat", "guide") }.reversed()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        T("Make something from this notebook's pages, recordings and sources. Each result is kept here until you delete it.", color = Ink.Gray, size = 15.sp)
        Spacer(Modifier.height(12.dp))
        SectionLabel("Reports")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Briefing doc", small = true, enabled = !busy) { make("briefing", "Briefing doc", "") }
            InkButton("Study guide", small = true, enabled = !busy) { make("study", "Study guide", "") }
            InkButton("Blog post", small = true, enabled = !busy) { make("blog", "Blog post", "") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            InkField(custom, { custom = it }, Modifier.weight(1f), hint = "Or describe your own: a one-page FAQ, an email to the team…", singleLine = false)
            InkButton("Create", on = true, enabled = !busy && custom.length > 5) { make("custom", custom.take(40), custom); custom = "" }
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("Study and visuals")
        var count by remember { mutableStateOf(10) }
        var diff by remember { mutableStateOf("medium") }
        var tableAsk by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            T("How many", size = 13.sp, sans = true, color = Ink.Gray)
            listOf(5, 10, 20).forEach { n -> InkButton("$n", small = true, on = count == n) { count = n } }
            Spacer(Modifier.width(8.dp))
            listOf("easy", "medium", "hard").forEach { d -> InkButton(d, small = true, on = diff == d) { diff = d } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Flashcards", small = true, enabled = !busy) { makeStudy("cards", "Flashcards", count, diff, "") }
            InkButton("Quiz", small = true, enabled = !busy) { makeStudy("quiz", "Quiz", count, diff, "") }
            InkButton("Mind map", small = true, enabled = !busy) { makeStudy("map", "Mind map", 0, "", "") }
            InkButton("Data table", small = true, enabled = !busy) { makeStudy("table", tableAsk.take(40).ifBlank { "Data table" }, 0, "", tableAsk) }
        }
        Spacer(Modifier.height(8.dp))
        InkField(tableAsk, { tableAsk = it }, hint = "Optional: what the data table should compare (e.g. sensors, price and range)")
        Spacer(Modifier.height(18.dp))
        SectionLabel("Made so far (${items.size})")
        if (items.isEmpty()) T("Nothing yet.", italic = true, color = Ink.Gray)
        items.forEach { it0 ->
            val expanded = open == it0.id
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().inkClick { open = if (expanded) null else it0.id }, verticalAlignment = Alignment.CenterVertically) {
                    T(it0.title, bold = true, size = 17.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    T(dateOf(it0.createdAt), size = 12.sp, sans = true, color = Ink.Gray)
                }
                val special = it0.kind in setOf("cards", "quiz", "map", "table")
                val shown = if (!special) it0.body else when (it0.kind) {
                    "cards" -> "${com.jondude.noteup.ai.Study.cards(it0.body).size} flashcards"
                    "quiz" -> "${com.jondude.noteup.ai.Study.quiz(it0.body).size} multiple-choice questions"
                    "map" -> com.jondude.noteup.ai.Study.tree(it0.body)?.let { t -> if (expanded) com.jondude.noteup.ai.Study.outline(t) else "${t.text} · ${t.kids.size} branches" } ?: "Couldn't read this one."
                    else -> com.jondude.noteup.ai.Study.table(it0.body)?.let { (c, r) -> if (expanded) (listOf(c) + r).joinToString("\n") { row -> row.joinToString("  |  ") } else "${c.joinToString(" · ")} · ${r.size} rows" } ?: "Couldn't read this one."
                }
                T(if (expanded || special) shown else it0.body.take(220).let { t -> if (it0.body.length > 220) "$t…" else t }, size = 15.sp, modifier = Modifier.padding(top = 4.dp))
                if (special) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        if (it0.kind == "cards" || it0.kind == "quiz") InkButton("Study", small = true, on = true) { onStudy(it0) }
                        else InkButton("Put on pages", small = true, on = true) { onPut(it0) }
                        if (it0.kind == "table") com.jondude.noteup.ai.Study.table(it0.body)?.let { (c, r) ->
                            InkButton("Copy CSV", small = true) { copyText(ctx, com.jondude.noteup.ai.Csv.encode(c, r)) }
                            InkButton("Share CSV", small = true) { shareText(ctx, it0.title, com.jondude.noteup.ai.Csv.encode(c, r)) }
                        }
                        if (it0.kind == "map") InkButton(if (expanded) "Hide outline" else "Outline", small = true) { open = if (expanded) null else it0.id }
                        if (it0.kind == "table") InkButton(if (expanded) "Hide rows" else "Show rows", small = true) { open = if (expanded) null else it0.id }
                        InkButton("Delete", small = true) { Store.deleteStudio(it0.id) }
                    }
                } else if (expanded) {
                    CiteChips(it0.refs, onCite)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        InkButton("Save as pages", small = true) { onSaved(Store.addTextPages(nbId, it0.title, it0.body)) }
                        InkButton("Copy", small = true) { copyText(ctx, it0.body) }
                        InkButton("Share", small = true) { shareText(ctx, it0.title, it0.body) }
                        InkButton(if (Speaker.speaking) "■ Stop" else "🔊 Read", small = true, on = Speaker.speaking) { if (Speaker.speaking) Speaker.stop() else Speaker.speak(ctx, it0.body) }
                        InkButton("Delete", small = true) { Store.deleteStudio(it0.id) }
                    }
                }
            }
            HRule(thickness = 1.dp)
        }
    }
}


/** Flashcards or quiz, one at a time. Missed ones come back later (twice at most) until you get them. */
@Composable
private fun StudyDialog(item: StudioItem, onClose: () -> Unit) {
    val cards = remember(item.id) { if (item.kind == "cards") com.jondude.noteup.ai.Study.cards(item.body) else emptyList() }
    val quiz = remember(item.id) { if (item.kind == "quiz") com.jondude.noteup.ai.Study.quiz(item.body) else emptyList() }
    val total = maxOf(cards.size, quiz.size)
    var run by remember(item.id) { mutableStateOf(com.jondude.noteup.ai.StudyRun(total)) }
    var tick by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    @Suppress("UNUSED_EXPRESSION") tick
    InkDialog(item.title, onDismiss = onClose) {
        val cur = run.current
        if (total == 0) {
            T("Nothing to study here.", italic = true); Spacer(Modifier.height(12.dp)); InkButton("Close", small = true, onClick = onClose)
        } else if (cur == null) {
            T("Done. ${run.firstTryRight} of $total right the first time.", bold = true, size = 18.sp)
            if (run.missed.isNotEmpty()) {
                Spacer(Modifier.height(8.dp)); T("Worth another look:", size = 13.sp, sans = true, color = Ink.Gray)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    run.missed.forEach { i ->
                        T("- " + (if (cards.isNotEmpty()) "${cards[i].q} → ${cards[i].a}" else "${quiz[i].q} → ${quiz[i].options[quiz[i].answer]}"), size = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Again", small = true, on = true) { run = com.jondude.noteup.ai.StudyRun(total); revealed = false; picked = null }
                InkButton("Close", small = true, onClick = onClose)
            }
        } else {
            T("${total - run.remaining + 1} of $total", size = 12.sp, sans = true, color = Ink.Gray)
            Spacer(Modifier.height(6.dp))
            if (cards.isNotEmpty()) {
                val c = cards[cur]
                T(c.q, bold = true, size = 19.sp)
                Spacer(Modifier.height(10.dp))
                if (!revealed) InkButton("Show answer", small = true, on = true) { revealed = true }
                else {
                    T(c.a, size = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("Missed it", small = true) { run.answer(false); revealed = false; tick++ }
                        InkButton("Got it", small = true, on = true) { run.answer(true); revealed = false; tick++ }
                    }
                }
            } else {
                val q = quiz[cur]
                T(q.q, bold = true, size = 19.sp)
                Spacer(Modifier.height(10.dp))
                q.options.forEachIndexed { i, o ->
                    val mark = if (picked == null) "" else if (i == q.answer) "✓ " else if (i == picked) "✗ " else ""
                    Box(Modifier.padding(vertical = 3.dp)) { InkButton(mark + o, small = true, on = picked == i, enabled = picked == null) { picked = i } }
                }
                picked?.let { pk ->
                    Spacer(Modifier.height(8.dp))
                    if (q.why.isNotBlank()) T(q.why, size = 14.sp, italic = true, color = Ink.Gray)
                    Spacer(Modifier.height(10.dp))
                    InkButton("Next ›", small = true, on = true) { run.answer(pk == q.answer); picked = null; tick++ }
                }
            }
            Spacer(Modifier.height(12.dp))
            InkButton("Stop", small = true, onClick = onClose)
        }
    }
}
