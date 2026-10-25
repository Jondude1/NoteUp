package com.jondude.noteup.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import com.jondude.noteup.data.MarginNote
import com.jondude.noteup.data.Notebook
import com.jondude.noteup.data.PAGE_H
import com.jondude.noteup.data.PAGE_W
import com.jondude.noteup.data.Page
import com.jondude.noteup.data.PenKeys
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

/** Holds the open page and saves it a moment after the last change. */
private class PageEditor(val nbId: String, val scope: CoroutineScope) {
    var page by mutableStateOf<Page?>(null)
    private var job: Job? = null
    private var dirty = false

    fun load(id: String) {
        flush()
        page = Store.page(id, nbId)
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
fun NotebookScreen(nbId: String, startPage: Int, onBack: () -> Unit, onOpenRecording: (String) -> Unit) {
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
    var selection by remember { mutableStateOf<Pair<Set<String>, RectF>?>(null) }
    var panel by remember { mutableStateOf(Panel()) }
    var question by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var tappedNote by remember { mutableStateOf<MarginNote?>(null) }
    var renaming by remember { mutableStateOf(false) }

    LaunchedEffect(pageId) {
        ed.load(pageId)
        val p = ed.page!!
        ink.setContent(p.strokes, p.annotations)
        selection = null
    }
    LaunchedEffect(nb.template) { ink.template = nb.template }
    LaunchedEffect(tool) { ink.tool = tool; if (tool != Tool.LASSO) selection = null }
    LaunchedEffect(penWidth) { ink.penWidth = penWidth }
    LaunchedEffect(Settings.penMode, Settings.pressureMode, Settings.palmMm, Settings.scratchErase, Settings.stylusSeen) {
        ink.penMode = Settings.penMode
        ink.pressureMode = Settings.pressureMode
        ink.palmPx = Settings.palmMm * ctx.resources.displayMetrics.xdpi / 25.4f
        ink.scratchErase = Settings.scratchErase
        ink.stylusSeen = Settings.stylusSeen
    }
    LaunchedEffect(Settings.handRest) { ink.handRestFrac = Settings.handRest }
    // stylus button that arrives as a key: toggles eraser on/off
    val firstPresses = remember { PenKeys.presses }
    LaunchedEffect(PenKeys.presses) {
        if (PenKeys.presses != firstPresses) tool = if (tool == Tool.ERASER) Tool.PEN else Tool.ERASER
    }
    DisposableEffect(Unit) {
        ink.recordingClock = { Recorder.clock() }
        onDispose { ed.flush(); ink.recordingClock = null }
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
        return Store.recordings.filter { it.pageId == p.id || it.id in linked }
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
        if (sel != null) p.strokes.filter { it.id in sel.first } else p.strokes
    } ?: emptyList()

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
            AiClient.ask(Twists.SYSTEM, Twists.minutes(transcript, hand), null, 1800)
        }, then = { out ->
            recs.singleOrNull()?.let { Store.upsertRecording(it.copy(summary = out)) }
            val actions = Twists.parseActions(out)
            Store.addTasks(actions, source)
            if (actions.isNotEmpty()) "(${actions.size} action item${if (actions.size == 1) "" else "s"} added to Tasks.)" else null
        })
    }

    fun startRecording() {
        if (Recorder.start(ctx, "${nb.title} · p${safeIndex + 1}", nb.id, pageId)) {
            panel = Panel(PanelKind.TRANSCRIPT, "Transcript")
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording()
    }

    SideEffect {
        ink.onStrokesChanged = { s -> ed.update { it.copy(strokes = s, convertedText = null) } }
        ink.onLasso = { ids, b -> selection = ids to b }
        ink.onSwipe = { dir -> turn(dir) }
        ink.onTwoFingerTap = { ink.undo() }
        ink.onHandRestMoved = { f -> Settings.handRest = f; Settings.save() }
        ink.onStylusDetected = { Settings.stylusSeen = true; Settings.save() }
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
            ToolItem("Eraser", on = tool == Tool.ERASER) { tool = Tool.ERASER }
            ToolItem("Lasso", on = tool == Tool.LASSO) { tool = Tool.LASSO }
            ToolItem("Undo") { ink.undo() }
            ToolItem("Redo") { ink.redo() }
            ToolItem("Paper") { dialog = "template" }
            ToolItem("Hand rest", on = Settings.handRest >= 0f) {
                Settings.handRest = if (Settings.handRest >= 0f) -1f else 0.72f
                Settings.save()
            }
            ToolItem("Sync ▶", on = tool == Tool.PLAY) { tool = if (tool == Tool.PLAY) Tool.PEN else Tool.PLAY }
            val rec = Recorder.active
            ToolItem(if (rec != null) "■ ${fmtTime(Recorder.elapsedMs)}" else "● Rec", on = rec != null) {
                if (rec != null) Recorder.stop()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            ToolItem("Transcript", on = panel.kind == PanelKind.TRANSCRIPT) {
                panel = if (panel.kind == PanelKind.TRANSCRIPT) Panel() else Panel(PanelKind.TRANSCRIPT, "Transcript")
            }
            ToolItem("✦ Claude", on = panel.kind == PanelKind.CLAUDE) {
                panel = if (panel.kind == PanelKind.CLAUDE) Panel() else Panel(PanelKind.CLAUDE, "Claude")
            }
            ToolItem("Pages") { dialog = "pages" }
        }
        HRule()

        // ---------- selection bar ----------
        if (selection != null) {
            Row(Modifier.fillMaxWidth().background(Ink.Black).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${selection!!.first.size} strokes selected", color = Ink.White, sans = true, size = 14.sp, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.weight(1f))
                SelAction("Ask Claude") { panel = Panel(PanelKind.CLAUDE, "Claude"); askClaude() }
                SelAction("To text") { convert() }
                SelAction("Delete") { ink.deleteStrokes(selection!!.first); selection = null }
                SelAction("Done") { ink.clearSelection(); selection = null }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(factory = { ink }, modifier = Modifier.fillMaxSize())
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

        HRule()
        // ---------- page footer ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolItem("‹ Prev", enabled = safeIndex > 0) { turn(-1) }
            Spacer(Modifier.weight(1f))
            T("${safeIndex + 1} / ${nb.pageIds.size}", sans = true, size = 15.sp)
            Spacer(Modifier.weight(1f))
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
        "pages" -> PagesDialog(nb, safeIndex, onPick = { ed.flush(); index = it; dialog = null }, onDelete = {
            ed.flush()
            Store.deletePage(nb.id, pageId)
            index = (safeIndex - 1).coerceAtLeast(0)
            dialog = null
        }, onDismiss = { dialog = null })
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
        }
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
private fun PagesDialog(nb: Notebook, current: Int, onPick: (Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    InkDialog("${nb.title} — ${nb.pageIds.size} pages", onDismiss = onDismiss) {
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.heightIn(max = 460.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(nb.pageIds) { i, pid ->
                val thumb by produceState<ImageBitmap?>(null, pid) {
                    value = withContext(Dispatchers.Default) {
                        PageRenderer.render(Store.page(pid, nb.id).strokes, null, 220).asImageBitmap()
                    }
                }
                Column(Modifier.inkClick { onPick(i) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(PAGE_W / PAGE_H)
                        .border(if (i == current) 3.dp else Ink.Rule, Ink.Black).background(Ink.White)) {
                        thumb?.let { Image(it, null, Modifier.fillMaxSize()) }
                    }
                    T("${i + 1}", size = 13.sp, sans = true, bold = i == current)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Delete this page", enabled = nb.pageIds.size > 1, onClick = onDelete)
            InkButton("Close", onClick = onDismiss)
        }
    }
}
