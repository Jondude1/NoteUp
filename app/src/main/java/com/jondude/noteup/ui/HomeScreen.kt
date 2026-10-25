package com.jondude.noteup.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.audio.Recorder
import com.jondude.noteup.data.Brief
import com.jondude.noteup.data.Documents
import com.jondude.noteup.data.Sync
import com.jondude.noteup.data.TaskItem
import com.jondude.noteup.data.Indexer
import com.jondude.noteup.data.Notebook
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.data.Template
import com.jondude.noteup.ink.HandwritingRecognizer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String) { TODAY("Today"), NOTES("Notes"), RECORDINGS("Recordings"), TASKS("Tasks"), ASK("Ask"), SETTINGS("Settings") }

private val dateFmt = SimpleDateFormat("MMM d", Locale.US)
private val longDate = SimpleDateFormat("EEEE, MMMM d", Locale.US)

@Composable
fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onOpenNotebook: (String, Int) -> Unit,
    onOpenRecording: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Ink.White)) {
        // left rail, like an e-reader's side menu
        Column(Modifier.width(150.dp).fillMaxHeight().padding(top = 20.dp)) {
            T("NoteUp", size = 26.sp, bold = true, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
            T(longDate.format(Date()), size = 12.sp, color = Ink.Gray, sans = true, modifier = Modifier.padding(start = 18.dp, bottom = 20.dp))
            Tab.entries.forEach { t ->
                Box(
                    Modifier.fillMaxWidth().background(if (t == tab) Ink.Black else Ink.White)
                        .inkClick { onTab(t) }.padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    T(if (t == Tab.ASK) "✦ Ask" else t.label, size = 17.sp, sans = true, bold = t == tab,
                        color = if (t == tab) Ink.White else Ink.Black)
                }
            }
            Spacer(Modifier.weight(1f))
            Recorder.active?.let {
                Box(Modifier.fillMaxWidth().background(Ink.Black).inkClick { onTab(Tab.RECORDINGS) }.padding(14.dp)) {
                    T("● ${fmtTime(Recorder.elapsedMs)}", color = Ink.White, sans = true, bold = true)
                }
            }
        }
        VRule(Modifier.fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (tab) {
                Tab.TODAY -> TodayTab(onOpenNotebook)
                Tab.NOTES -> Shelf(onOpenNotebook, onOpenRecording)
                Tab.RECORDINGS -> RecordingsList(onOpenRecording)
                Tab.TASKS -> TasksTab()
                Tab.ASK -> AskTab()
                Tab.SETTINGS -> SettingsTab()
            }
        }
    }
}

@Composable
private fun Header(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, size = 30.sp, bold = true, modifier = Modifier.weight(1f))
        action?.invoke()
    }
    HRule()
}

fun dueLabel(iso: String): String = try {
    SimpleDateFormat("EEE MMM d", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!).let { "due $it" }
} catch (e: Exception) { "due $iso" }

// ---------------- Today (daily brief) ----------------

@Composable
private fun TodayTab(onOpenPage: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var brief by remember { mutableStateOf(Brief.cached()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var calOk by remember { mutableStateOf(Brief.hasCalendarPermission(ctx)) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calOk = it }
    val events = remember(calOk) { if (Settings.briefCalendar) Brief.eventsToday(ctx) else emptyList() }

    fun make() {
        busy = true; error = null
        scope.launch {
            try { brief = Brief.generate(ctx) } catch (e: Exception) { error = e.message }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        if (brief == null && AiClient.configured(Settings.backend)) make()
    }

    Column(Modifier.fillMaxSize()) {
        Header("Today") { InkButton(if (busy) "Writing…" else "Refresh brief", small = true, enabled = !busy) { make() } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
            SectionLabel("Calendar")
            when {
                !Settings.briefCalendar -> T("Calendar is turned off in Settings.", color = Ink.Gray, italic = true)
                !calOk -> InkButton("Show my calendar here", small = true) { calPerm.launch(android.Manifest.permission.READ_CALENDAR) }
                events.isEmpty() -> T("Nothing scheduled today.", color = Ink.Gray, italic = true)
                else -> events.forEach { T(Brief.eventLine(it), size = 17.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
            val due = Brief.dueTasks()
            if (due.isNotEmpty()) {
                SectionLabel("Due")
                due.forEach { t ->
                    Row(Modifier.fillMaxWidth().inkClick { Store.toggleTask(t.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).border(Ink.Rule, Ink.Black))
                        Spacer(Modifier.width(12.dp))
                        T(t.text, size = 17.sp, modifier = Modifier.weight(1f))
                        T(if (t.due!! < Brief.today()) "overdue" else "today", size = 12.sp, sans = true, bold = true)
                    }
                }
            }
            SectionLabel("Brief")
            when {
                busy && brief == null -> T("Reading yesterday's notes and your tasks…", italic = true, color = Ink.Gray)
                error != null -> T(error!!, italic = true)
                brief != null -> T(brief!!.text, size = 17.sp)
                !AiClient.configured(Settings.backend) -> T("Add Claude or your local AI in Settings to get a written brief each morning.", italic = true, color = Ink.Gray)
                else -> T("Tap Refresh brief.", italic = true, color = Ink.Gray)
            }
            val recent = remember(Store.pagesVersion) {
                Store.allPages().filter { it.third.strokes.isNotEmpty() }.sortedByDescending { it.third.updatedAt }.take(5)
            }
            if (recent.isNotEmpty()) {
                SectionLabel("Pick up where you left off")
                recent.forEach { (nb, i, p) ->
                    Column(Modifier.fillMaxWidth().inkClick { onOpenPage(nb.id, i) }.padding(vertical = 8.dp)) {
                        T("${nb.title} · page ${i + 1}${p.title?.let { " — $it" } ?: ""}", size = 16.sp, bold = true, maxLines = 1)
                        T(dateFmt.format(Date(p.updatedAt)), size = 12.sp, sans = true, color = Ink.Gray)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------- Notes shelf ----------------

@Composable
private fun Shelf(onOpenPage: (String, Int) -> Unit, onOpenRecording: (String) -> Unit) {
    val onOpen: (String) -> Unit = { onOpenPage(it, 0) }
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Notebook?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = "Opening PDF…"
            scope.launch {
                try {
                    val nb = Documents.importPdf(ctx, uri) { i, n -> importing = "Importing page $i of $n…" }
                    importing = null
                    onOpen(nb.id)
                } catch (e: Exception) {
                    importing = "Couldn't import: ${e.message}"
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Notebooks") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Import PDF") { pickPdf.launch(arrayOf("application/pdf")) }
                InkButton("+ New notebook") { creating = true }
            }
        }
        // search your handwriting
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            InkField(query, { query = it }, Modifier.weight(1f), hint = "Search your handwriting, notes and recordings")
            if (query.isNotEmpty() || tag != null) {
                Spacer(Modifier.width(10.dp))
                InkButton("Clear", small = true) { query = ""; tag = null }
            }
        }
        val version = Store.pagesVersion
        val tags = remember(version) { Indexer.allTags() }
        if (tags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 28.dp, end = 20.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t -> InkButton("#$t", small = true, on = tag == t) { tag = if (tag == t) null else t } }
            }
        }
        if (Indexer.pending > 0) {
            T("Reading ${Indexer.pending} page${if (Indexer.pending == 1) "" else "s"} of handwriting…", size = 12.sp, sans = true,
                color = Ink.Gray, modifier = Modifier.padding(start = 28.dp, top = 6.dp))
        }
        if (query.isNotBlank() || tag != null) {
            val hits = remember(query, tag, version) { Indexer.search(query, tag) }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                if (hits.isEmpty()) item {
                    T("Nothing found. Pages are read in the background a moment after you leave them.", italic = true,
                        color = Ink.Gray, modifier = Modifier.padding(28.dp))
                }
                items(hits) { h ->
                    Column(Modifier.fillMaxWidth().inkClick {
                        if (h.recording != null) onOpenRecording(h.recording.id) else onOpenPage(h.nb.id, h.index)
                    }.padding(horizontal = 28.dp, vertical = 12.dp)) {
                        val where = if (h.recording != null) "Recording · ${h.recording.title}"
                            else "${h.nb.title} · page ${h.index + 1}${h.page?.title?.let { " — $it" } ?: ""}"
                        T(where, size = 13.sp, sans = true, bold = true, maxLines = 1)
                        T(h.snippet, size = 16.sp, maxLines = 3)
                        h.page?.tags?.takeIf { it.isNotEmpty() }?.let { T(it.joinToString("  ") { t -> "#$t" }, size = 12.sp, color = Ink.Gray, sans = true) }
                    }
                    HRule(thickness = 1.dp)
                }
            }
        } else {
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp),
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(4.dp)) }
            items(Store.notebooks.toList(), key = { it.id }) { nb ->
                Column(Modifier.inkClick(onLongClick = { editing = nb }) { onOpen(nb.id) }) {
                    // notebook cover: black spine + label, like a paper notebook
                    Row(Modifier.fillMaxWidth().aspectRatio(0.72f).border(2.dp, Ink.Black).background(Ink.White)) {
                        Box(Modifier.width(14.dp).fillMaxHeight().background(Ink.Black))
                        Column(Modifier.fillMaxSize().padding(14.dp)) {
                            Spacer(Modifier.weight(0.35f))
                            Box(Modifier.fillMaxWidth().border(1.dp, Ink.Black).padding(8.dp)) {
                                T(nb.title, size = 16.sp, bold = true, maxLines = 3)
                            }
                            Spacer(Modifier.weight(0.65f))
                            T(nb.template.name.lowercase(), size = 11.sp, sans = true, color = Ink.Gray)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    T("${nb.pageIds.size} page${if (nb.pageIds.size == 1) "" else "s"} · ${dateFmt.format(Date(nb.updatedAt))}",
                        size = 13.sp, sans = true, color = Ink.Gray)
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                T("Long-press a notebook to rename or delete it.", size = 13.sp, color = Ink.Gray, italic = true,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        }
    }

    importing?.let { msg ->
        InkDialog("Import PDF", onDismiss = { if (msg.startsWith("Couldn't")) importing = null }) {
            T(msg)
            if (msg.startsWith("Couldn't")) {
                Spacer(Modifier.height(12.dp))
                InkButton("OK") { importing = null }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var template by remember { mutableStateOf(Settings.defaultTemplate) }
        InkDialog("New notebook", onDismiss = { creating = false }) {
            InkField(name, { name = it }, hint = "Title")
            SectionLabel("Paper")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Template.entries.forEach { t ->
                    InkButton(t.name.lowercase(), small = true, on = t == template) { template = t }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Create", on = true) {
                    val nb = Store.createNotebook(name.ifBlank { "Untitled" }.trim(), template)
                    creating = false
                    onOpen(nb.id)
                }
                InkButton("Cancel") { creating = false }
            }
        }
    }

    editing?.let { nb ->
        var name by remember(nb.id) { mutableStateOf(nb.title) }
        var confirm by remember(nb.id) { mutableStateOf(false) }
        InkDialog(nb.title, onDismiss = { editing = null }) {
            InkField(name, { name = it }, hint = "Title")
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Save", on = true) { Store.updateNotebook(nb.copy(title = name.ifBlank { nb.title }.trim())); editing = null }
                InkButton(if (confirm) "Tap again to delete" else "Delete") {
                    if (confirm) { Store.deleteNotebook(nb.id); editing = null } else confirm = true
                }
            }
        }
    }
}

// ---------------- Recordings ----------------

@Composable
private fun RecordingsList(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    fun begin() {
        if (Recorder.start(ctx, "Recording ${dateFmt.format(Date())}", null, null)) Recorder.active?.let { onOpen(it.id) }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) begin() }
    Column(Modifier.fillMaxSize()) {
        Header("Recordings") {
            val rec = Recorder.active
            InkButton(if (rec != null) "■ Stop ${fmtTime(Recorder.elapsedMs)}" else "● Record", on = rec != null) {
                when {
                    rec != null -> Recorder.stop()
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                    else -> perm.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        if (Store.recordings.isEmpty()) {
            Column(Modifier.padding(28.dp)) {
                T("No recordings yet.", size = 18.sp, italic = true)
                T("Record here for a quick voice memo, or tap ● Rec inside a notebook so your handwriting stays linked to the audio.",
                    color = Ink.Gray)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(Store.recordings.toList(), key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().inkClick { onOpen(r.id) }.padding(horizontal = 28.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (Recorder.active?.id == r.id) T("● ", bold = true)
                        T(r.title, size = 19.sp, bold = true, modifier = Modifier.weight(1f), maxLines = 1)
                        T(fmtTime(if (Recorder.active?.id == r.id) Recorder.elapsedMs else r.durationMs), sans = true, size = 14.sp)
                    }
                    val preview = r.summary?.substringAfter("SUMMARY")?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }
                        ?: r.segments.firstOrNull()?.text
                    if (preview != null) T(preview, size = 15.sp, color = Ink.Gray, maxLines = 2)
                    T("${dateFmt.format(Date(r.createdAt))}${if (r.notebookId != null) " · linked to notes" else ""}${if (r.summary != null) " · minutes" else ""}",
                        size = 12.sp, sans = true, color = Ink.Gray)
                }
                HRule(thickness = 1.dp)
            }
        }
    }
}

// ---------------- Tasks ----------------

@Composable
private fun TasksTab() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    fun pickDue(t: TaskItem) {
        val c = java.util.Calendar.getInstance()
        t.due?.let { d -> try { c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)!! } catch (_: Exception) {} }
        android.app.DatePickerDialog(ctx, { _, y, m, d ->
            Store.updateTask(t.copy(due = "%04d-%02d-%02d".format(y, m + 1, d)))
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }
    Column(Modifier.fillMaxSize()) {
        Header("Tasks") {
            if (Store.tasks.any { it.done }) InkButton("Clear done", small = true) {
                Store.tasks.filter { it.done }.forEach { Store.deleteTask(it.id) }
            }
        }
        Row(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            InkField(text, { text = it }, Modifier.weight(1f), hint = "Add a task")
            Spacer(Modifier.width(10.dp))
            InkButton("Add", on = true) { if (text.isNotBlank()) { Store.addTasks(listOf(text), null); text = "" } }
        }
        if (Store.tasks.isEmpty()) {
            T("Action items from meeting minutes land here automatically, with due dates when one was mentioned. Long-press a task to set a date; 📅 adds it to your calendar.", italic = true, color = Ink.Gray,
                modifier = Modifier.padding(horizontal = 28.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(Store.tasks.sortedWith(compareBy<TaskItem> { it.done }.thenBy { it.due ?: "9999" }), key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().inkClick(onLongClick = { pickDue(t) }) { Store.toggleTask(t.id) }.padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).border(Ink.Rule, Ink.Black).background(if (t.done) Ink.Black else Ink.White),
                        contentAlignment = Alignment.Center) {
                        if (t.done) T("✓", color = Ink.White, sans = true, bold = true, size = 15.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        T(t.text, size = 17.sp, color = if (t.done) Ink.Gray else Ink.Black)
                        val overdue = !t.done && t.due != null && t.due < Brief.today()
                        val meta = listOfNotNull(t.due?.let { dueLabel(it) + if (overdue) " — overdue" else "" }, t.source?.let { "from $it" })
                        if (meta.isNotEmpty()) T(meta.joinToString(" · "), size = 12.sp, sans = true, bold = overdue, color = if (overdue) Ink.Black else Ink.Gray)
                    }
                    ToolItem(if (t.due == null) "Due…" else "📅") { if (t.due == null) pickDue(t) else Brief.addToCalendar(ctx, t) }
                    ToolItem("×") { Store.deleteTask(t.id) }
                }
                HRule(thickness = 1.dp)
            }
        }
    }
}

// ---------------- Ask your notes (Claude twist) ----------------

@Composable
private fun AskTab() {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }

    val pending = Store.notebooks.sumOf { nb ->
        nb.pageIds.count { pid -> Store.page(pid, nb.id).let { it.convertedText == null && it.strokes.isNotEmpty() } }
    }

    fun corpus(): String = buildString {
        Store.notebooks.forEach { nb ->
            nb.pageIds.forEachIndexed { i, pid ->
                val p = Store.page(pid, nb.id)
                val txt = p.convertedText
                if (!txt.isNullOrBlank()) append("### ${nb.title}, page ${i + 1}\n$txt\n\n")
                p.annotations.forEach { append("### Claude's margin note in ${nb.title}, page ${i + 1}\n${it.text}\n\n") }
            }
        }
        Store.recordings.forEach { r ->
            if (r.summary != null) append("### Minutes: ${r.title}\n${r.summary}\n\n")
            else if (r.segments.isNotEmpty()) append("### Transcript: ${r.title}\n${r.segments.joinToString(" ") { it.text }}\n\n")
        }
        Store.tasks.filter { !it.done }.takeIf { it.isNotEmpty() }?.let { open ->
            append("### Open tasks\n${open.joinToString("\n") { "- ${it.text}" }}\n")
        }
    }.takeLast(120_000)

    Column(Modifier.fillMaxSize()) {
        Header("Ask your notes")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
            T("Ask across every notebook, transcript and set of minutes. Claude answers from your notes and says where each point came from.",
                color = Ink.Gray)
            Spacer(Modifier.height(16.dp))
            InkField(q, { q = it }, hint = "What did we decide about…?", singleLine = false, minLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton("Ask", on = true, enabled = !busy && q.isNotBlank()) {
                    busy = true; answer = ""
                    scope.launch {
                        answer = try {
                            AiClient.ask(Twists.SYSTEM, Twists.askNotebook(q, corpus()), null, 1500)
                        } catch (e: Exception) { e.message ?: "Something went wrong." }
                        busy = false
                    }
                }
                if (pending > 0) InkButton(progress ?: "Read $pending handwritten page${if (pending == 1) "" else "s"} first", small = true,
                    enabled = progress == null) {
                    scope.launch {
                        var done = 0
                        Store.notebooks.toList().forEach { nb ->
                            nb.pageIds.forEach { pid ->
                                val p = Store.page(pid, nb.id)
                                if (p.convertedText == null && p.strokes.isNotEmpty()) {
                                    progress = "Reading… ${++done}/$pending"
                                    try {
                                        val t = HandwritingRecognizer.recognize(p.strokes.filter { !it.marker && !it.box }, Settings.language)
                                        Store.savePage(p.copy(convertedText = t), touch = false)
                                    } catch (e: Exception) { progress = "Couldn't read handwriting: ${e.message}" }
                                }
                            }
                        }
                        progress = null
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (busy) T("Reading your notes…", italic = true, color = Ink.Gray)
            if (answer.isNotBlank()) {
                HRule(thickness = 1.dp)
                Spacer(Modifier.height(16.dp))
                T(answer, size = 17.sp)
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
private fun SyncSettings() {
    val scope = rememberCoroutineScope()
    fun s(block: () -> Unit) { block(); Settings.save() }
    SectionLabel("Sync between tablets (WebDAV / Nextcloud)")
    InkField(Settings.syncUrl, { v -> s { Settings.syncUrl = v } }, hint = "https://cloud.example.com/remote.php/dav/files/you")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InkField(Settings.syncUser, { v -> s { Settings.syncUser = v } }, Modifier.weight(1f), hint = "User name")
        InkField(Settings.syncPass, { v -> s { Settings.syncPass = v } }, Modifier.weight(1f), hint = "App password", secret = true)
    }
    Toggle("Sync automatically", Settings.autoSync, { v -> s { Settings.autoSync = v } },
        note = "When the app opens and about a minute after you stop writing")
    Toggle("Include recordings' audio", Settings.syncAudio, { v -> s { Settings.syncAudio = v } },
        note = "Transcripts and minutes always sync; audio files are large")
    Row(verticalAlignment = Alignment.CenterVertically) {
        InkButton(if (Sync.running) "Syncing…" else "Sync now", small = true, enabled = !Sync.running && Sync.configured) {
            scope.launch { Sync.runNow() }
        }
        Spacer(Modifier.width(12.dp))
        Sync.status?.let { T(it, size = 14.sp, italic = true) }
    }
    T("Set this up the same way on both tablets. Notes go in a NoteUp folder on your server. In Nextcloud, make an app password under Settings → Security.",
        size = 13.sp, color = Ink.Gray, modifier = Modifier.padding(top = 6.dp))
    Toggle("Calendar in daily brief", Settings.briefCalendar, { v -> s { Settings.briefCalendar = v } })
}


@Composable
private fun SettingsTab() {
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<String?>(null) }
    fun s(block: () -> Unit) { block(); Settings.save() }

    Column(Modifier.fillMaxSize()) {
        Header("Settings")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
            SectionLabel("AI")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Claude", on = Settings.backend == com.jondude.noteup.data.AiBackend.CLAUDE) {
                    s { Settings.backend = com.jondude.noteup.data.AiBackend.CLAUDE }
                }
                InkButton("Local server", on = Settings.backend == com.jondude.noteup.data.AiBackend.LOCAL) {
                    s { Settings.backend = com.jondude.noteup.data.AiBackend.LOCAL }
                }
            }
            Toggle("Fall back to the other one if it fails", Settings.fallbackToOther, { v -> s { Settings.fallbackToOther = v } },
                note = "e.g. use Claude when your home server is unreachable")

            SectionLabel("Claude")
            InkField(Settings.claudeKey, { v -> s { Settings.claudeKey = v } }, hint = "API key (sk-ant-…) from console.anthropic.com", secret = true)
            Spacer(Modifier.height(8.dp))
            InkField(Settings.claudeModel, { v -> s { Settings.claudeModel = v } }, hint = "Model")

            SectionLabel("Local server (OpenAI-compatible: Ollama, llama.cpp, LM Studio)")
            InkField(Settings.localUrl, { v -> s { Settings.localUrl = v } }, hint = "http://192.168.1.50:11434")
            Spacer(Modifier.height(8.dp))
            InkField(Settings.localModel, { v -> s { Settings.localModel = v } }, hint = "Model name")
            Spacer(Modifier.height(8.dp))
            InkField(Settings.localKey, { v -> s { Settings.localKey = v } }, hint = "API key (optional)", secret = true)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkButton("Test AI", small = true) {
                    test = "Testing…"
                    scope.launch {
                        test = try {
                            val r = AiClient.ask("Reply with exactly: ok", "ping", null, 10)
                            "Working — ${if (AiClient.lastBackend == com.jondude.noteup.data.AiBackend.CLAUDE) "Claude" else "local server"} replied \"${r.take(30)}\""
                        } catch (e: Exception) { "Failed: ${e.message}" }
                    }
                }
                Spacer(Modifier.width(12.dp))
                test?.let { T(it, size = 14.sp, italic = true) }
            }

            SyncSettings()

            SectionLabel("Transcription")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("On-device", on = Settings.sttEngine == com.jondude.noteup.data.SttEngine.ON_DEVICE) {
                    s { Settings.sttEngine = com.jondude.noteup.data.SttEngine.ON_DEVICE }
                }
                InkButton("Whisper server", on = Settings.sttEngine == com.jondude.noteup.data.SttEngine.WHISPER) {
                    s { Settings.sttEngine = com.jondude.noteup.data.SttEngine.WHISPER }
                }
            }
            T("On-device uses the tablet's speech recognizer live (Android 13+). Whisper sends 20-second chunks to your server " +
                "(faster-whisper-server, whisper.cpp) — better accuracy, a little delay.", size = 13.sp, color = Ink.Gray,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
            InkField(Settings.whisperUrl, { v -> s { Settings.whisperUrl = v } }, hint = "Whisper server URL")
            Spacer(Modifier.height(8.dp))
            InkField(Settings.whisperModel, { v -> s { Settings.whisperModel = v } }, hint = "Whisper model")
            Spacer(Modifier.height(8.dp))
            InkField(Settings.language, { v -> s { Settings.language = v } }, hint = "Language (en-US)")

            SectionLabel("Writing")
            Toggle("Pen only", Settings.stylusOnly, { v -> s { Settings.stylusOnly = v } },
                note = "Palm rejection: fingers swipe pages and tap, only the S Pen writes. Hold the pen button to erase.")
            Toggle("E-ink refresh flash", Settings.refreshFlash, { v -> s { Settings.refreshFlash = v } },
                note = "A quick black blink when you turn pages, like a real e-ink screen")
            Toggle("Snap shapes", Settings.shapeSnap, { v -> s { Settings.shapeSnap = v } },
                note = "Hold the pen still at the end of a stroke to turn it into a clean line, box, circle or triangle")
            Toggle("Check boxes", Settings.checkboxInk, { v -> s { Settings.checkboxInk = v } },
                note = "Draw a small square and it becomes a box you can tap to tick. The writing beside it becomes a task in Tasks")
            Toggle("Scribble to erase", Settings.scribbleErase, { v -> s { Settings.scribbleErase = v } },
                note = "Scratch back and forth over ink with the pen to wipe it out")
            Toggle("Auto titles and tags", Settings.autoTag, { v -> s { Settings.autoTag = v } },
                note = "After a page is read, the AI names it and tags its topics (uses your AI setting)")
            SectionLabel("Default paper")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Template.entries.forEach { t ->
                    InkButton(t.name.lowercase(), small = true, on = Settings.defaultTemplate == t) { s { Settings.defaultTemplate = t } }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
