package com.jondude.noteup.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.jondude.noteup.ai.AiClient
import com.jondude.noteup.ai.Twists
import com.jondude.noteup.audio.Player
import com.jondude.noteup.audio.Recorder
import com.jondude.noteup.data.Documents
import com.jondude.noteup.data.Indexer
import com.jondude.noteup.data.Stroke
import com.jondude.noteup.ink.DiagramBuilder
import com.jondude.noteup.ink.InkCommands
import com.jondude.noteup.data.MarginNote
import com.jondude.noteup.data.Notebook
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.Page
import com.jondude.noteup.data.PageText
import com.jondude.noteup.data.STICKY_COLORS
import com.jondude.noteup.data.Sticky
import com.jondude.noteup.data.Reminders
import com.jondude.noteup.data.Recording
import com.jondude.noteup.data.Settings
import com.jondude.noteup.data.Store
import com.jondude.noteup.data.Template
import com.jondude.noteup.ink.HandwritingRecognizer
import com.jondude.noteup.ink.InkView
import com.jondude.noteup.ink.PageRenderer
import com.jondude.noteup.ink.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Holds the open page and saves it a moment after the last change. */
private class PageEditor(val nbId: String, val scope: CoroutineScope) {
    var page by mutableStateOf<Page?>(null)
    private var job: Job? = null
    private var dirty = false

    fun load(id: String) {
        leave()
        page = Store.page(id, nbId)
    }

    /** Save, and queue the page to be read in the background (search, titles, tags). */
    fun leave() {
        flush()
        page?.let { p -> if (p.convertedText == null && p.strokes.any { !it.marker && !it.box }) Indexer.schedule(p.id, nbId) }
    }

    fun update(f: (Page) -> Page) {
        val p = page ?: return
        page = f(p)
        dirty = true
        job?.cancel()
        job = scope.launch { delay(700); flush() }
    }

    fun flush() {
        job?.cancel(); job = null
        if (dirty) page?.let { Store.savePage(it) }
        dirty = false
    }
}

/** A loop just drawn around ink, waiting to see if a command word is written beside it. */
private class CircleCmd(val enclosed: Set<String>, val bounds: RectF, var lastAt: Long) {
    val cmdIds = HashSet<String>()
}

private class CircleState {
    var active: CircleCmd? = null
    var knownIds: Set<String> = emptySet()
    var job: Job? = null
    var boxJob: Job? = null
}

private enum class PanelKind { NONE, TRANSCRIPT, CLAUDE }

private data class Panel(
    val kind: PanelKind = PanelKind.NONE,
    val title: String = "",
    val body: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val mode: String = "", // "text" when body is converted handwriting, enables Tidy/Flashcards
)

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("NoteUp", text))
}

@Composable
fun NotebookScreen(nbId: String, startPage: Int, onBack: () -> Unit, onOpenRecording: (String) -> Unit, onOpenStudio: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nb = Store.notebooks.firstOrNull { it.id == nbId }
    if (nb == null) { LaunchedEffect(Unit) { onBack() }; return }

    var index by remember { mutableIntStateOf(startPage) }
    val safeIndex = index.coerceIn(0, nb.pageIds.lastIndex)
    val pageId = nb.pageIds[safeIndex]

    val ed = remember { PageEditor(nbId, scope) }
    val ink = remember { InkView(ctx) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var penWidth by remember { mutableStateOf(3.5f) }
    var inkColor by remember { mutableIntStateOf(0) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Pair<Set<String>, RectF>?>(null) }
    var panel by remember { mutableStateOf(Panel()) }
    var question by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var tappedNote by remember { mutableStateOf<MarginNote?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var zoomPct by remember { mutableIntStateOf(100) }
    var dock by remember { mutableIntStateOf(-1) }
    val circle = remember { CircleState() }

    LaunchedEffect(pageId) {
        ed.load(pageId)
        val p = ed.page!!
        ink.setContent(p.strokes, p.annotations)
        ink.texts = p.texts
        circle.active = null
        circle.boxJob?.cancel()
        circle.knownIds = p.strokes.map { it.id }.toHashSet()
        selection = null
        ink.background = null
        ink.background = withContext(Dispatchers.IO) { Documents.loadBackground(p.background) }
    }
    // when the notes for a recording on this page arrive, open the meeting tab on them
    val notesReady = Store.recordings.count { it.pageId == pageId && it.summary != null }
    var lastNotes by remember(pageId) { mutableIntStateOf(notesReady) }
    LaunchedEffect(notesReady) {
        if (notesReady > lastNotes) dock = 0
        lastNotes = notesReady
    }
    LaunchedEffect(pageId) { dock = -1 }
    LaunchedEffect(inkColor) { ink.inkColor = inkColor }
    LaunchedEffect(nb.template) { ink.template = nb.template }
    LaunchedEffect(tool) { ink.tool = tool; if (tool != Tool.LASSO) selection = null }
    LaunchedEffect(penWidth) { ink.penWidth = penWidth }
    LaunchedEffect(Settings.shapeSnap) { ink.shapeSnap = Settings.shapeSnap }
    LaunchedEffect(Settings.stylusOnly) { ink.stylusOnly = Settings.stylusOnly }
    LaunchedEffect(Settings.checkboxInk) { ink.checkboxInk = Settings.checkboxInk }
    LaunchedEffect(Settings.scribbleErase) { ink.scribbleErase = Settings.scribbleErase }
    DisposableEffect(Unit) {
        ink.recordingClock = { Recorder.clock() }
        onDispose { ed.leave(); ink.recordingClock = null; com.jondude.noteup.audio.Speaker.stop() }
    }

    // Ink replay: while audio plays, strokes written later in the recording are drawn faded.
    LaunchedEffect(Player.playing, Player.currentId, tool) {
        while (Player.playing) {
            Player.tick()
            ink.playhead = Player.currentId?.let { it to Player.positionMs }
            delay(300)
        }
        if (tool != Tool.PLAY) ink.playhead = null
    }

    fun turn(dir: Int) {
        val n = safeIndex + dir
        if (n in 0..nb.pageIds.lastIndex) {
            ed.flush()
            index = n
            scope.launch { Flash.blink() }
        }
    }

    fun pageRecordings(): List<Recording> {
        val p = ed.page ?: return emptyList()
        val linked = p.strokes.mapNotNull { it.recId }.toSet()
        return Store.recordings.filter { it.pageId == p.id || it.id in linked }.map { r ->
            if (Recorder.active?.id == r.id) r.copy(segments = Recorder.segments.toList()) else r
        }
    }

    fun runClaude(title: String, mode: String = "", work: suspend () -> String, then: (String) -> String? = { null }) {
        panel = Panel(PanelKind.CLAUDE, title, busy = true, mode = mode)
        scope.launch {
            try {
                val out = work()
                val extra = then(out)
                panel = Panel(PanelKind.CLAUDE, title, if (extra != null) "$out\n\n$extra" else out, mode = mode)
            } catch (e: Exception) {
                panel = Panel(PanelKind.CLAUDE, title, error = e.message ?: e.toString(), mode = mode)
            }
        }
    }

    fun targetStrokes() = ed.page?.let { p ->
        val sel = selection
        (if (sel != null) p.strokes.filter { it.id in sel.first } else p.strokes).filter { !it.marker && !it.box }
    } ?: emptyList()

    fun placeInMargin(answer: String, area: RectF?, strokes: List<Stroke>) {
        val b = area ?: InkView.boundsOf(strokes)
        val note = if (b.right + 300f < PAGE_W) {
            val x = b.right + 30f
            MarginNote(x = x, y = max(60f, b.top + 14f), w = PAGE_W - x - 40f, text = answer)
        } else {
            val x = max(60f, b.left)
            MarginNote(x = x, y = min(PAGE_H - 160f, b.bottom + 50f), w = min(640f, PAGE_W - x - 50f), text = answer)
        }
        ed.update { it.copy(annotations = it.annotations + note) }
        ink.setAnnotations(ed.page!!.annotations)
    }

    /** define / translate / explain / ... on some ink; the answer goes in the margin. */
    fun runCommand(cmd: String, extra: String, strokes: List<Stroke>, area: RectF?) {
        if (strokes.isEmpty()) return
        val title = cmd.replaceFirstChar { it.uppercase() }
        runClaude(title, work = {
            val img = withContext(Dispatchers.Default) { PageRenderer.toPngBase64(PageRenderer.render(strokes, area)) }
            val ocr = try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
            AiClient.ask(Twists.SYSTEM, Twists.command(cmd, extra, ocr), img, 600)
        }, then = { answer ->
            placeInMargin(answer, area, strokes)
            "(Written in the margin.)"
        })
    }

    fun cleanDiagram() {
        val strokes = targetStrokes()
        if (strokes.isEmpty()) { panel = Panel(PanelKind.CLAUDE, "Clean diagram", error = "Lasso a sketch first."); return }
        val area = selection?.second ?: InkView.boundsOf(strokes)
        panel = Panel(PanelKind.CLAUDE, "Clean diagram")
        runClaude("Clean diagram", work = {
            val r = withContext(Dispatchers.Default) { PageRenderer.renderMapped(strokes, area, 1200) }
            val reply = AiClient.ask(Twists.SYSTEM, Twists.diagram(r.bitmap.width, r.bitmap.height), PageRenderer.toPngBase64(r.bitmap), 2500)
            val (newStrokes, labels) = withContext(Dispatchers.Default) { DiagramBuilder.build(reply, r.src, r.scale, penWidth) }
            ink.replaceStrokes(strokes.map { it.id }.toSet(), newStrokes)
            ed.update { it.copy(texts = it.texts + labels) }
            ink.texts = ed.page!!.texts
            selection = null
            "Redrawn with ${newStrokes.size} clean lines and ${labels.size} labels. Undo brings your sketch back."
        })
    }

    fun clearLabels() {
        ed.update { it.copy(texts = emptyList()) }
        ink.texts = emptyList()
    }

    fun askClaude() {
        val strokes = targetStrokes()
        if (strokes.isEmpty()) { panel = Panel(PanelKind.CLAUDE, "Ask Claude", error = "Write something on the page first."); return }
        val area = selection?.second
        val q = question
        runClaude("Ask Claude", work = {
            val img = withContext(Dispatchers.Default) { PageRenderer.toPngBase64(PageRenderer.render(strokes, area)) }
            val ocr = try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
            AiClient.ask(Twists.SYSTEM, Twists.askPage(q, ocr), img, 700)
        }, then = { answer ->
            // Claude writes back in the margin, next to what you asked about.
            placeInMargin(answer, area, strokes)
            question = ""
            "(Written in the margin — tap it to copy or remove.)"
        })
    }

    fun convert() {
        val strokes = targetStrokes()
        if (strokes.isEmpty()) { panel = Panel(PanelKind.CLAUDE, "Convert", error = "Nothing to convert yet."); return }
        val whole = selection == null
        runClaude("Handwriting → text", mode = "text", work = {
            HandwritingRecognizer.recognize(strokes, Settings.language)
        }, then = { text ->
            if (whole) ed.update { it.copy(convertedText = text) }
            null
        })
    }

    fun tidy(ocr: String) {
        val strokes = targetStrokes()
        val area = selection?.second
        val whole = selection == null
        runClaude("Tidied text", mode = "text", work = {
            val img = withContext(Dispatchers.Default) { PageRenderer.toPngBase64(PageRenderer.render(strokes, area)) }
            AiClient.ask(Twists.SYSTEM, Twists.tidy(ocr), img, 1500)
        }, then = { text -> if (whole) ed.update { it.copy(convertedText = text) }; null })
    }

    fun minutes() {
        val recs = pageRecordings()
        val strokes = ed.page?.strokes.orEmpty()
        if (recs.isEmpty() && strokes.isEmpty()) {
            panel = Panel(PanelKind.CLAUDE, "Meeting minutes", error = "Record a meeting or write some notes first."); return
        }
        val source = "${nb.title}, page ${safeIndex + 1}"
        runClaude("Meeting minutes", work = {
            val transcript = recs.joinToString("\n") { r -> r.segments.joinToString("\n") { "[${fmtTime(it.startMs)}] ${it.text}" } }
            val hand = try { HandwritingRecognizer.recognize(strokes, Settings.language) } catch (e: Exception) { "" }
            AiClient.ask(Twists.SYSTEM, Twists.minutes(transcript, hand, Settings.noteStyle), null, 1800)
        }, then = { out ->
            recs.singleOrNull()?.let { Store.upsertRecording(it.copy(summary = out, summaryStyle = Settings.noteStyle.name)) }
            val actions = Twists.parseActionsWithDue(out)
            Store.addTasksWithDue(actions, source)
            if (actions.isNotEmpty()) "(${actions.size} action item${if (actions.size == 1) "" else "s"} added to Tasks.)" else null
        })
    }

    fun startRecording() {
        if (Recorder.start(ctx, "${nb.title} · p${safeIndex + 1}", nb.id, pageId)) {
            dock = 1
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording()
        else android.widget.Toast.makeText(ctx, "NoteUp needs the microphone. Allow it in Settings > Apps > NoteUp > Permissions.", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Drawn check boxes become tasks: the handwriting beside a box is read and added to Tasks. */
    suspend fun linkBoxes() {
        val forPage = ed.page?.id ?: return
        val all = ink.currentStrokes()
        val source = "${nb.title} · p${safeIndex + 1}"
        for (box in all.filter { it.box && it.taskId == null }) {
            val row = InkCommands.rowStrokes(box, all)
            if (row.isEmpty()) continue
            val read = try { HandwritingRecognizer.recognize(row, Settings.language) } catch (e: Exception) { "" }
            val text = read.replace('\n', ' ').trim().take(140)
            if (text.isBlank() || ed.page?.id != forPage) continue
            val cur = ink.currentStrokes().firstOrNull { it.id == box.id }
            if (cur == null || cur.taskId != null) continue
            val task = Store.tasks.firstOrNull { it.text == text && it.source == source }
                ?: Store.addTask(text, null, source, done = cur.checked)
            ink.updateStrokes(setOf(box.id)) { it.copy(taskId = task.id, checked = task.done) }
        }
    }

    fun scheduleLink() {
        if (!Settings.checkboxInk) return
        circle.boxJob?.cancel()
        circle.boxJob = scope.launch { delay(2500); linkBoxes() }
    }

    /** A task ticked in the Tasks tab ticks its box on the page. */
    fun reconcileBoxes() {
        val fix = ink.currentStrokes().filter { it.box && it.taskId != null }.mapNotNull { b ->
            val t = Store.tasks.firstOrNull { it.id == b.taskId } ?: return@mapNotNull null
            if (t.done != b.checked) b.id to t.done else null
        }.toMap()
        if (fix.isNotEmpty()) ink.updateStrokes(fix.keys) { it.copy(checked = fix[it.id] ?: it.checked) }
    }

    /** Circle something and write a day beside it: it becomes a dated task. */
    suspend fun addReminder(r: Reminders.Reminder, target: List<Stroke>, area: RectF) {
        val read = try { HandwritingRecognizer.recognize(target, Settings.language) } catch (e: Exception) { "" }
        val text = read.replace('\n', ' ').trim().take(140)
        if (text.isBlank()) {
            panel = Panel(PanelKind.CLAUDE, "Reminder", error = "I couldn't read what you circled. Write it a little larger, or add the task in the Tasks tab.")
            return
        }
        Store.addTask(text, r.due, "${nb.title} · p${safeIndex + 1}")
        val said = r.due?.let { dueLabel(it) }
        // a small typed note beside the circle, so the page shows it was captured
        val x = min(area.right + 14f, PAGE_W - 220f)
        ed.update { it.copy(texts = it.texts + PageText(x = x, y = area.top, w = 200f, text = "→ ${said ?: "task"}", size = 16f, centered = false)) }
        ink.texts = ed.page!!.texts
        panel = Panel(PanelKind.CLAUDE, "Reminder",
            body = "Added to Tasks: $text" + (if (said != null) " — $said." else ".") +
                "\n\nIt shows on your Today tab when it's due. In Tasks, tap 📅 to put it in your calendar.")
    }

    // ticks made in the Tasks tab show on the page; boxes drawn earlier but not yet linked get linked
    val taskSig = Store.tasks.map { it.id to it.done }
    LaunchedEffect(taskSig, pageId) {
        reconcileBoxes()
        if (ink.currentStrokes().any { it.box && it.taskId == null }) scheduleLink()
    }

    SideEffect {
        ink.onStrokesChanged = { s ->
            ed.update { it.copy(strokes = s, convertedText = null) }
            val added = s.filter { it.id !in circle.knownIds }
            circle.knownIds = s.map { it.id }.toHashSet()
            if (s.any { it.box && it.taskId == null }) scheduleLink()
            if (added.size == 1 && !added[0].box) {
                val st = added[0]
                val c = circle.active
                val now = SystemClock.uptimeMillis()
                if (c != null && now - c.lastAt < 4000 && InkCommands.gap(InkView.boundsOf(listOf(st)), c.bounds) < 350f) {
                    // writing next to a fresh circle: maybe a command like "define"
                    c.cmdIds += st.id
                    c.lastAt = now
                    circle.job?.cancel()
                    circle.job = scope.launch {
                        delay(1400)
                        val cmdStrokes = ink.currentStrokes().filter { it.id in c.cmdIds }
                        val written = try { HandwritingRecognizer.recognize(cmdStrokes, Settings.language) } catch (e: Exception) { "" }
                        val parsed = Twists.parseCommand(written)
                        circle.active = null
                        if (parsed != null) {
                            val target = ink.currentStrokes().filter { it.id in c.enclosed }
                            ink.deleteStrokes(c.cmdIds.toSet())
                            panel = Panel(PanelKind.CLAUDE, "Claude")
                            runCommand(parsed.first, parsed.second, target, c.bounds)
                        } else {
                            val reminder = Reminders.parse(written)
                            if (reminder != null) {
                                val target = ink.currentStrokes().filter { it.id in c.enclosed }
                                ink.deleteStrokes(c.cmdIds.toSet())
                                addReminder(reminder, target, c.bounds)
                            }
                        }
                    }
                } else {
                    val enclosed = InkCommands.enclosedBy(st, s)
                    circle.active = if (enclosed.isEmpty()) null else
                        CircleCmd(enclosed, InkView.boundsOf(s.filter { it.id in enclosed }), now)
                }
            }
        }
        ink.onLasso = { ids, b -> selection = ids to b }
        ink.onZoomChanged = { z -> zoomPct = (z * 100f).roundToInt() }
        ink.onBoxToggle = { st ->
            val tid = st.taskId
            if (tid != null) Store.tasks.firstOrNull { it.id == tid }?.let { Store.updateTask(it.copy(done = st.checked)) }
            else scheduleLink()
        }
        ink.onSwipe = { dir -> turn(dir) }
        ink.onAnnotationTap = { tappedNote = it }
        ink.onSeek = { recId, ms -> Store.recording(recId)?.let { Player.play(it, max(0L, ms - 1500L)) } }
    }

    Column(Modifier.fillMaxSize().background(Ink.White)) {
        // ---------- toolbar ----------
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolItem("‹ Shelf") { ed.flush(); onBack() }
            Box(Modifier.inkClick { renaming = true }.padding(horizontal = 10.dp)) {
                T(nb.title, size = 18.sp, bold = true, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            ToolItem(
                when (penWidth) { 2.2f -> "Pen ·"; 3.5f -> "Pen ••"; else -> "Pen ●" },
                on = tool == Tool.PEN,
            ) {
                if (tool == Tool.PEN) penWidth = when (penWidth) { 2.2f -> 3.5f; 3.5f -> 6f; else -> 2.2f }
                tool = Tool.PEN
            }
            ToolItem(listOf("Ink ■", "Ink ▩", "Ink □")[inkColor]) {
                inkColor = (inkColor + 1) % 3
                tool = Tool.PEN
            }
            ToolItem("Marker", on = tool == Tool.MARKER) { tool = Tool.MARKER }
            ToolItem("Eraser", on = tool == Tool.ERASER) { tool = Tool.ERASER }
            ToolItem("Lasso", on = tool == Tool.LASSO) { tool = Tool.LASSO }
            ToolItem("−") { ink.zoomStep(-1) }
            ToolItem(if (zoomPct <= 101) "100%" else "$zoomPct% · fit", on = zoomPct > 101) { ink.resetZoom() }
            ToolItem("+") { ink.zoomStep(1) }
            ToolItem("Undo") { ink.undo() }
            ToolItem("Redo") { ink.redo() }
            ToolItem("Paper") { dialog = "template" }
            ToolItem("Sticky") { dialog = "sticky" }
            ToolItem("Sync ▶", on = tool == Tool.PLAY) { tool = if (tool == Tool.PLAY) Tool.PEN else Tool.PLAY }
            val rec = Recorder.active
            ToolItem(if (rec != null) "■ ${fmtTime(Recorder.elapsedMs)}" else "● Rec", on = rec != null) {
                android.widget.Toast.makeText(ctx, if (rec != null) "Stopping…" else "Starting recording…", android.widget.Toast.LENGTH_SHORT).show()
                if (rec != null) Recorder.stop()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            if (Recorder.active != null) ToolItem("★ ${Recorder.marks.size}") { Recorder.mark() }
            ToolItem("Meeting", on = dock >= 0) { dock = if (dock >= 0) -1 else 0 }
            ToolItem("Studio") { ed.flush(); onOpenStudio(nb.id) }
            ToolItem("✦ Claude", on = panel.kind == PanelKind.CLAUDE) {
                panel = if (panel.kind == PanelKind.CLAUDE) Panel() else Panel(PanelKind.CLAUDE, "Claude")
            }
            ToolItem("Pages") { dialog = "pages" }
            ToolItem("Share") { dialog = "export" }
        }
        HRule()

        // ---------- selection bar ----------
        if (selection != null) {
            Row(Modifier.fillMaxWidth().background(Ink.Black).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                T("${selection!!.first.size} selected", color = Ink.White, sans = true, size = 14.sp, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.width(12.dp))
                SelAction("Ask Claude") { panel = Panel(PanelKind.CLAUDE, "Claude"); askClaude() }
                listOf("define", "translate", "explain", "solve").forEach { cmd ->
                    SelAction(cmd.replaceFirstChar { it.uppercase() }) {
                        panel = Panel(PanelKind.CLAUDE, "Claude")
                        runCommand(cmd, "", targetStrokes(), selection?.second)
                    }
                }
                SelAction("Clean diagram") { cleanDiagram() }
                SelAction("To text") { convert() }
                SelAction("Delete") { ink.deleteStrokes(selection!!.first); selection = null }
                SelAction("Done") { ink.clearSelection(); selection = null }
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(factory = { ink }, modifier = Modifier.fillMaxSize())
                StickyLayer(
                    stickies = ed.page?.stickies.orEmpty(),
                    xf = ink.xf,
                    onChange = { st -> ed.update { p -> p.copy(stickies = p.stickies.map { if (it.id == st.id) st else it }) } },
                    onDelete = { id -> ed.update { p -> p.copy(stickies = p.stickies.filterNot { it.id == id }) } },
                )
            }
            if (panel.kind != PanelKind.NONE) {
                VRule(Modifier.fillMaxHeight())
                Column(Modifier.width(340.dp).fillMaxHeight().background(Ink.White)) {
                    when (panel.kind) {
                        PanelKind.TRANSCRIPT -> TranscriptPanel(pageRecordings(), onOpenRecording) { panel = Panel() }
                        PanelKind.CLAUDE -> ClaudePanel(
                            panel = panel,
                            question = question,
                            onQuestion = { question = it },
                            hasSelection = selection != null,
                            onAsk = { askClaude() },
                            onConvert = { convert() },
                            onMinutes = { minutes() },
                            onDiagram = { cleanDiagram() },
                            hasLabels = ed.page?.texts?.isNotEmpty() == true,
                            onClearLabels = { clearLabels() },
                            onTidy = { tidy(panel.body) },
                            onFlashcards = { text ->
                                runClaude("Flashcards", work = { AiClient.ask(Twists.SYSTEM, Twists.flashcards(text), null, 1200) })
                            },
                            onCopy = { copyText(ctx, panel.body) },
                            onClose = { panel = Panel() },
                        )
                        PanelKind.NONE -> {}
                    }
                }
            }
        }
        MeetingDock(pageRecordings(), dock, { dock = it }, onOpenRecording)
        }

        HRule()
        // ---------- page footer ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolItem("‹ Prev", enabled = safeIndex > 0) { turn(-1) }
            Spacer(Modifier.weight(1f))
            ToolItem("${"↳ ".repeat(nb.depth(pageId).coerceAtMost(3))}${safeIndex + 1} / ${nb.pageIds.size}") { dialog = "pages" }
            Spacer(Modifier.weight(1f))
            ToolItem("+ Subpage") {
                ed.flush()
                Store.addSubpage(nb.id, pageId)
                val n = Store.notebook(nb.id)
                index = n?.pageIds?.indexOfLast { n.parentOf(it) == pageId }?.coerceAtLeast(0) ?: safeIndex
                scope.launch { Flash.blink() }
            }
            ToolItem("+ Page") {
                ed.flush()
                Store.addPage(nb.id, safeIndex)
                index = safeIndex + 1
                scope.launch { Flash.blink() }
            }
            ToolItem("Next ›", enabled = safeIndex < nb.pageIds.lastIndex) { turn(1) }
        }
    }

    // ---------- dialogs ----------
    when (dialog) {
        "template" -> InkDialog("Paper", onDismiss = { dialog = null }) {
            Template.entries.forEach { t ->
                Row(Modifier.fillMaxWidth().inkClick { Store.updateNotebook(nb.copy(template = t)); dialog = null }.padding(vertical = 10.dp)) {
                    T(if (t == nb.template) "■  " else "□  ", sans = true)
                    T(t.name.lowercase().replaceFirstChar { it.uppercase() }, size = 18.sp)
                }
            }
        }
        "export" -> InkDialog("Share / export", onDismiss = { dialog = null; exportStatus = null }) {
            fun go(label: String, mime: String, make: suspend () -> java.io.File) {
                exportStatus = "$label…"
                scope.launch {
                    exportStatus = try {
                        ed.flush()
                        val f = make()
                        Documents.share(ctx, f, mime)
                        null
                    } catch (e: Exception) { "Export failed: ${e.message}" }
                    if (exportStatus == null) dialog = null
                }
            }
            val busy = exportStatus != null && !exportStatus!!.startsWith("Export failed")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("This page — PDF", Modifier.fillMaxWidth(), enabled = !busy) {
                    go("Making PDF", "application/pdf") { Documents.pdf(ctx, nb, listOf(safeIndex)) }
                }
                InkButton("This page — image", Modifier.fillMaxWidth(), enabled = !busy) {
                    go("Making image", "image/png") { Documents.pagePng(ctx, nb, safeIndex) }
                }
                InkButton("Whole notebook — PDF", Modifier.fillMaxWidth(), enabled = !busy) {
                    go("Making PDF", "application/pdf") {
                        Documents.pdf(ctx, nb, nb.pageIds.indices.toList()) { i, n -> exportStatus = "Making PDF… page $i of $n" }
                    }
                }
                InkButton("Whole notebook — text (Markdown)", Modifier.fillMaxWidth(), enabled = !busy) {
                    go("Reading handwriting", "text/markdown") {
                        Documents.markdown(ctx, nb) { i, n -> exportStatus = "Reading handwriting… page $i of $n" }
                    }
                }
                exportStatus?.let { T(it, italic = true, color = Ink.Gray, size = 14.sp) }
            }
        }
        "sticky" -> InkDialog("Sticky note or text box", onDismiss = { dialog = null }) {
            T("Pick a colour, or Aa for plain typed text. Drag its top bar to move it, drag the corner to resize, ✕ to delete. It stays on this page until you delete it.",
                size = 14.sp, color = Ink.Gray)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                STICKY_COLORS.forEachIndexed { ci, c ->
                    Box(Modifier.size(52.dp).background(androidx.compose.ui.graphics.Color(c)).border(2.dp, Ink.Black).inkClick {
                        val (cx, cy) = ink.viewCenterPage()
                        val plain = ci == com.jondude.noteup.data.PLAIN_TEXT_BOX
                        val st = Sticky(x = (cx - 120f).coerceIn(0f, PAGE_W - 240f), y = (cy - 90f).coerceIn(0f, PAGE_H - 180f), color = ci,
                            w = if (plain) 300f else 240f, h = if (plain) 120f else 180f)
                        ed.update { p -> p.copy(stickies = p.stickies + st) }
                        dialog = null
                    }, contentAlignment = Alignment.Center) { if (ci == com.jondude.noteup.data.PLAIN_TEXT_BOX) T("Aa", sans = true, bold = true) }
                }
            }
        }
        "pages" -> PagesDialog(nb, safeIndex,
            onPick = { ed.flush(); index = it; dialog = null },
            onDelete = {
                ed.flush()
                Store.deletePage(nb.id, pageId)
                index = (safeIndex - 1).coerceAtLeast(0)
                dialog = null
            },
            onIndent = { ed.flush(); Store.indentPage(nb.id, pageId) },
            onMove = { dir ->
                ed.flush()
                if (Store.movePage(nb.id, pageId, dir)) index = Store.notebook(nb.id)?.pageIds?.indexOf(pageId)?.coerceAtLeast(0) ?: safeIndex
            },
            onOutdent = {
                ed.flush()
                if (Store.outdentPage(nb.id, pageId)) index = Store.notebook(nb.id)?.pageIds?.indexOf(pageId)?.coerceAtLeast(0) ?: safeIndex
            },
            onSubpage = {
                ed.flush()
                Store.addSubpage(nb.id, pageId)
                val n = Store.notebook(nb.id)
                index = n?.pageIds?.indexOfLast { n.parentOf(it) == pageId }?.coerceAtLeast(0) ?: safeIndex
                dialog = null
            },
            onDismiss = { dialog = null })
    }

    tappedNote?.let { note ->
        InkDialog("Claude's note", onDismiss = { tappedNote = null }) {
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { T(note.text, italic = true, size = 17.sp) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Copy") { copyText(ctx, note.text); tappedNote = null }
                InkButton("Remove") {
                    ed.update { p -> p.copy(annotations = p.annotations.filterNot { it.id == note.id }) }
                    ink.setAnnotations(ed.page!!.annotations)
                    tappedNote = null
                }
                InkButton("Close") { tappedNote = null }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(nb.title) }
        InkDialog("Rename notebook", onDismiss = { renaming = false }) {
            InkField(name, { name = it })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("Save", on = true) { if (name.isNotBlank()) Store.updateNotebook(nb.copy(title = name.trim())); renaming = false }
                InkButton("Cancel") { renaming = false }
            }
        }
    }
}

@Composable
private fun SelAction(label: String, onClick: () -> Unit) {
    Box(Modifier.inkClick(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        T(label, color = Ink.White, sans = true, bold = true, size = 14.sp)
    }
}

@Composable
private fun PanelHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        T(title, size = 18.sp, bold = true, modifier = Modifier.weight(1f))
        ToolItem("×", onClick = onClose)
    }
    HRule()
}

@Composable
private fun TranscriptPanel(recs: List<Recording>, onOpenRecording: (String) -> Unit, onClose: () -> Unit) {
    PanelHeader("Transcript", onClose)
    val live = Recorder.active
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (live != null) {
            item {
                SectionLabel("● Recording  ${fmtTime(Recorder.elapsedMs)}")
                Recorder.status?.let { T(it, size = 13.sp, italic = true, color = Ink.Gray) }
            }
            items(Recorder.segments.toList()) { s ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    T(fmtTime(s.startMs), size = 12.sp, sans = true, color = Ink.Gray)
                    T(s.text, size = 16.sp)
                }
            }
            if (Recorder.partial.isNotBlank()) item { T(Recorder.partial, italic = true, color = Ink.Gray) }
            item { Spacer(Modifier.height(8.dp)); HRule(thickness = 1.dp) }
        }
        val saved = recs.filter { it.id != live?.id }
        if (saved.isEmpty() && live == null) {
            item {
                Spacer(Modifier.height(16.dp))
                T("No recordings on this page.", italic = true, color = Ink.Gray)
                T("Tap ● Rec and write as you listen. Later, Sync ▶ lets you tap any line of ink to hear that moment.", size = 14.sp, color = Ink.Gray)
            }
        }
        items(saved) { r ->
            val playingThis = Player.currentId == r.id && Player.playing
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(r.title, bold = true, modifier = Modifier.weight(1f), maxLines = 2)
                    ToolItem(if (playingThis) "❚❚" else "▶") { Player.toggle(r) }
                }
                T("${fmtTime(r.durationMs)} · ${r.segments.size} lines", size = 13.sp, color = Ink.Gray, sans = true)
                r.segments.forEach { s ->
                    val current = Player.currentId == r.id && Player.positionMs in s.startMs until s.endMs.coerceAtLeast(s.startMs + 1)
                    Column(
                        Modifier.fillMaxWidth().background(if (current) Ink.Black else Ink.White)
                            .inkClick { Player.play(r, s.startMs) }.padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        T(s.text, size = 15.sp, color = if (current) Ink.White else Ink.Black)
                    }
                }
                Spacer(Modifier.height(6.dp))
                InkButton("Open recording", small = true) { onOpenRecording(r.id) }
            }
            HRule(thickness = 1.dp)
        }
    }
}

@Composable
private fun ClaudePanel(
    panel: Panel,
    question: String,
    onQuestion: (String) -> Unit,
    hasSelection: Boolean,
    onAsk: () -> Unit,
    onConvert: () -> Unit,
    onMinutes: () -> Unit,
    onDiagram: () -> Unit,
    hasLabels: Boolean,
    onClearLabels: () -> Unit,
    onTidy: () -> Unit,
    onFlashcards: (String) -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    PanelHeader(if (panel.title.isBlank()) "Claude" else panel.title, onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T(if (hasSelection) "Working on your lasso selection." else "Working on the whole page. Use Lasso to pick a part.",
            size = 13.sp, color = Ink.Gray, sans = true)
        Spacer(Modifier.height(10.dp))
        InkField(question, onQuestion, hint = "Ask about it… (or leave blank)", singleLine = false, minLines = 2)
        Spacer(Modifier.height(10.dp))
        InkButton("Ask → write in margin", on = true, modifier = Modifier.fillMaxWidth(), enabled = !panel.busy, onClick = onAsk)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("To text", small = true, enabled = !panel.busy, onClick = onConvert)
            InkButton("Minutes", small = true, enabled = !panel.busy, onClick = onMinutes)
            InkButton("Clean diagram", small = true, enabled = !panel.busy, onClick = onDiagram)
        }
        if (hasLabels) {
            Spacer(Modifier.height(8.dp))
            InkButton("Remove typed labels", small = true, onClick = onClearLabels)
        }
        Spacer(Modifier.height(10.dp))
        T("Tips: circle a word and write define, translate, explain, solve or ? beside it. Write a day (Fri, tomorrow, 10/30) beside a circled item to make it a reminder. Draw a small box to make a to-do; tap it to tick. Scribble over ink to erase it.", size = 13.sp, color = Ink.Gray, italic = true)
        Spacer(Modifier.height(16.dp))
        HRule(thickness = 1.dp)
        Spacer(Modifier.height(12.dp))
        when {
            panel.busy -> T("Thinking…", italic = true, color = Ink.Gray)
            panel.error != null -> T(panel.error, color = Ink.Black, italic = true)
            panel.body.isNotBlank() -> {
                T(panel.body, size = 16.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Copy", small = true, onClick = onCopy)
                    if (panel.mode == "text") {
                        InkButton("Tidy with AI", small = true, onClick = onTidy)
                        InkButton("Flashcards", small = true) { onFlashcards(panel.body) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                T("via ${if (AiClient.lastBackend.name == "CLAUDE") "Claude" else "local AI"}", size = 12.sp, color = Ink.Gray, sans = true)
            }
            else -> T(
                "Ask writes Claude's answer in the margin beside your ink. To text reads your handwriting on-device. " +
                    "Minutes turns this page's recording and notes into a summary and adds action items to Tasks.",
                size = 14.sp, color = Ink.Gray,
            )
        }
    }
}

@Composable
private fun PagesDialog(
    nb: Notebook, current: Int,
    onPick: (Int) -> Unit, onDelete: () -> Unit, onIndent: () -> Unit, onOutdent: () -> Unit, onSubpage: () -> Unit, onMove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    InkDialog("${nb.title} — ${nb.pageIds.size} pages", onDismiss = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(nb.pageIds) { i, pid ->
                val depth = nb.depth(pid)
                val thumb by produceState<ImageBitmap?>(null, pid) {
                    value = withContext(Dispatchers.Default) {
                        Documents.renderThumb(nb, Store.page(pid, nb.id), 160).asImageBitmap()
                    }
                }
                Row(Modifier.fillMaxWidth().inkClick { onPick(i) }.padding(vertical = 5.dp).padding(start = (depth * 28).dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (depth > 0) T("↳ ", sans = true, color = Ink.Gray)
                    Box(Modifier.width(54.dp).aspectRatio(PAGE_W / PAGE_H)
                        .border(if (i == current) 3.dp else Ink.Rule, Ink.Black).background(Ink.White)) {
                        thumb?.let { Image(it, null, Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.width(10.dp))
                    val title = Store.page(pid, nb.id).title
                    T("${i + 1}${title?.let { " · $it" } ?: ""}", size = 15.sp, sans = true, bold = i == current, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val here = nb.pageIds.getOrNull(current)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("+ Subpage", small = true, onClick = onSubpage)
            InkButton("Indent →", small = true, enabled = here != null && current > 0, onClick = onIndent)
            InkButton("← Outdent", small = true, enabled = here != null && nb.parentOf(here) != null, onClick = onOutdent)
            InkButton("▲", small = true, onClick = { onMove(-1) })
            InkButton("▼", small = true, onClick = { onMove(1) })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Delete this page", small = true, enabled = nb.pageIds.size > 1, onClick = onDelete)
            InkButton("Close", small = true, onClick = onDismiss)
        }
    }
}
