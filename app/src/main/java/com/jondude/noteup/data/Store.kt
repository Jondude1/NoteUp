package com.jondude.noteup.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Plain JSON files in app storage. Lists are Compose snapshot state, so screens update
 * automatically; writes go to disk in the background.
 *
 * Layout (mirrored as-is by sync):
 *   notebooks.json, recordings.json, tasks.json, tombstones.json, page-stamps.json
 *   pages/<id>.json, backgrounds/<file>, audio/<file>
 */
object Store {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var root: File
        private set

    val notebooks = mutableStateListOf<Notebook>()
    val recordings = mutableStateListOf<Recording>()
    val tasks = mutableStateListOf<TaskItem>()
    val sources = mutableStateListOf<Source>()
    val studio = mutableStateListOf<StudioItem>()

    /** id -> time deleted, so a delete on one tablet wins over the copy on the other. */
    val tombstones = HashMap<String, Long>()
    /** page id -> updatedAt, so sync can tell which pages changed without opening them all. */
    val pageStamps = HashMap<String, Long>()

    /** Bumped whenever a page's saved content changes (search results, thumbnails). */
    var pagesVersion by mutableIntStateOf(0)
        private set

    /** Called after any local change (used to schedule sync). */
    var onLocalChange: (() -> Unit)? = null

    private val pageCache = HashMap<String, Page>()

    val audioDir: File get() = File(root, "audio").apply { mkdirs() }
    val bgDir: File get() = File(root, "backgrounds").apply { mkdirs() }

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.filesDir, "noteup").apply { mkdirs() }
        File(root, "pages").mkdirs()
        reloadLists()
        if (notebooks.isEmpty()) createNotebook("My first notebook", Template.LINED)
    }

    /** Re-reads the list files from disk (after sync). */
    fun reloadLists() {
        notebooks.clear(); recordings.clear(); tasks.clear(); sources.clear(); studio.clear()
        notebooks.addAll(read<List<Notebook>>("notebooks.json") ?: emptyList())
        recordings.addAll(read<List<Recording>>("recordings.json") ?: emptyList())
        tasks.addAll(read<List<TaskItem>>("tasks.json") ?: emptyList())
        sources.addAll(read<List<Source>>("sources.json") ?: emptyList())
        studio.addAll(read<List<StudioItem>>("studio.json") ?: emptyList())
        tombstones.clear(); tombstones.putAll(read<Map<String, Long>>("tombstones.json") ?: emptyMap())
        pageStamps.clear(); pageStamps.putAll(read<Map<String, Long>>("page-stamps.json") ?: emptyMap())
        pageCache.clear()
        pagesVersion++
    }

    /**
     * Reads a saved file. If it is damaged (power loss, full storage) the bad copy is kept as .corrupt and the
     * last good copy (.bak) is used, so a bad write never silently becomes an empty notebook list.
     */
    inline fun <reified T> read(name: String): T? {
        val f = File(root, name)
        if (!f.exists()) return recoverBackup(name)
        return try { json.decodeFromString<T>(f.readText()) } catch (e: Exception) {
            quarantine(f)
            val bak = File(root, "$name.bak")
            if (bak.exists()) try { json.decodeFromString<T>(bak.readText()).also { bak.copyTo(f, overwrite = true) } } catch (e2: Exception) { null } else null
        }
    }

    inline fun <reified T> recoverBackup(name: String): T? {
        val bak = File(root, "$name.bak")
        return if (bak.exists()) try { json.decodeFromString<T>(bak.readText()) } catch (e: Exception) { null } else null
    }

    fun quarantine(f: File) {
        try { f.copyTo(File(f.parentFile, f.name + ".corrupt"), overwrite = true) } catch (_: Exception) {}
    }

    /** Writes to a temp file, keeps the previous good version as .bak, then swaps the new one in. */
    fun writeNow(name: String, text: String) {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (tmp.length() == 0L && text.isNotEmpty()) { tmp.delete(); throw java.io.IOException("Couldn't save $name (storage full?)") }
        if (f.exists() && f.length() > 2 && !name.startsWith("pages/")) try { f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true) } catch (_: Exception) {}
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() } }
    }

    @Volatile var saveError: String? = null

    private fun writeAsync(name: String, text: String) {
        io.launch { synchronized(this@Store) { try { writeNow(name, text) } catch (e: Exception) { saveError = e.message } } }
        onLocalChange?.invoke()
    }

    private fun saveNotebooks() = writeAsync("notebooks.json", json.encodeToString(notebooks.toList()))
    private fun saveRecordings() = writeAsync("recordings.json", json.encodeToString(recordings.toList()))
    private fun saveSources() = writeAsync("sources.json", json.encodeToString(sources.toList()))
    private fun saveStudio() = writeAsync("studio.json", json.encodeToString(studio.toList()))
    private fun saveTasks() = writeAsync("tasks.json", json.encodeToString(tasks.toList()))
    private fun saveTombstones() = writeAsync("tombstones.json", json.encodeToString(tombstones.toMap()))
    private fun savePageStamps() = writeAsync("page-stamps.json", json.encodeToString(pageStamps.toMap()))

    private fun bury(id: String) { tombstones[id] = System.currentTimeMillis(); saveTombstones() }

    // ---- notebooks ----
    fun notebook(id: String): Notebook? = notebooks.firstOrNull { it.id == id }

    fun createNotebook(title: String, template: Template, backgrounds: List<String?> = listOf(null)): Notebook {
        val nb = Notebook(title = title, template = template)
        val ids = backgrounds.map { bg ->
            val p = Page(notebookId = nb.id, background = bg)
            savePage(p)
            p.id
        }
        val withPages = nb.copy(pageIds = ids)
        notebooks.add(0, withPages)
        saveNotebooks()
        return withPages
    }

    fun updateNotebook(nb: Notebook) {
        val i = notebooks.indexOfFirst { it.id == nb.id }
        if (i >= 0) notebooks[i] = nb.copy(updatedAt = System.currentTimeMillis())
        saveNotebooks()
    }

    fun deleteNotebook(id: String) {
        val nb = notebook(id) ?: return
        nb.pageIds.forEach { pid -> removePageFiles(pid) }
        notebooks.removeAll { it.id == id }
        bury(id)
        saveNotebooks()
        sources.filter { it.notebookId == id }.forEach { bury(it.id) }
        studio.filter { it.notebookId == id }.forEach { bury(it.id) }
        sources.removeAll { it.notebookId == id }; saveSources()
        studio.removeAll { it.notebookId == id }; saveStudio()
    }

    // ---- sources and studio items ----
    fun sourcesOf(nbId: String): List<Source> = sources.filter { it.notebookId == nbId }.sortedBy { it.addedAt }
    fun studioOf(nbId: String): List<StudioItem> = studio.filter { it.notebookId == nbId }.sortedBy { it.createdAt }

    fun upsertSource(s: Source) {
        val stamped = s.copy(updatedAt = System.currentTimeMillis())
        val i = sources.indexOfFirst { it.id == s.id }
        if (i >= 0) sources[i] = stamped else sources.add(stamped)
        saveSources()
    }

    fun deleteSource(id: String) { sources.removeAll { it.id == id }; bury(id); saveSources() }

    fun upsertStudio(item: StudioItem) {
        val stamped = item.copy(updatedAt = System.currentTimeMillis())
        val i = studio.indexOfFirst { it.id == item.id }
        if (i >= 0) studio[i] = stamped else studio.add(stamped)
        saveStudio()
    }

    fun deleteStudio(id: String) { studio.removeAll { it.id == id }; bury(id); saveStudio() }

    /** Saves text onto new typed pages at the end of the notebook (or under [parentId]). Returns the index of the first new page. */
    fun addTextPages(nbId: String, title: String, body: String, parentId: String? = null): Int {
        val chunks = com.jondude.noteup.ai.Splitter.split(body, 2600).ifEmpty { listOf("") }
        var first = -1
        chunks.forEachIndexed { i, chunk ->
            val nb = notebook(nbId) ?: return first
            val p = if (parentId != null && i == 0) addSubpage(nbId, parentId) else addPage(nbId, notebook(nbId)!!.pageIds.lastIndex)
            val texts = ArrayList<PageText>()
            texts.add(PageText(x = 60f, y = 60f, w = 880f, text = if (i == 0) title else "$title (cont.)", size = 28f, centered = false))
            texts.add(PageText(x = 60f, y = 130f, w = 880f, text = chunk, size = 20f, centered = false))
            savePage(p.copy(texts = texts, title = if (i == 0) title else null))
            if (first < 0) first = notebook(nbId)!!.pageIds.indexOf(p.id)
        }
        return first
    }


    /** Puts generated drawings (mind map, table) on new pages after the last page: boxes as ink outlines, text as typed text. Returns the first page index. */
    fun addDrawingPages(nbId: String, title: String, drawings: List<com.jondude.noteup.ai.Drawing>): Int {
        var first = -1
        drawings.forEachIndexed { n, d ->
            val nb = notebook(nbId) ?: return first
            val p = addPage(nbId, nb.pageIds.lastIndex)
            fun stroke(pts: FloatArray) = Stroke(pts = pts, times = LongArray(pts.size / 3) { it * 8L }, t0 = System.currentTimeMillis(), width = 2.2f)
            val strokes = ArrayList<Stroke>()
            val texts = ArrayList<PageText>()
            texts.add(PageText(x = 40f, y = 24f, w = 920f, text = if (n == 0) title else "$title (cont.)", size = 26f, centered = false))
            for (b in d.boxes) {
                if (b.bordered) strokes.add(stroke(floatArrayOf(b.x, b.y, 1f, b.x + b.w, b.y, 1f, b.x + b.w, b.y + b.h, 1f, b.x, b.y + b.h, 1f, b.x, b.y, 1f)))
                if (b.text.isNotBlank()) texts.add(PageText(x = b.x + 6f, y = b.y + 6f, w = (b.w - 12f).coerceAtLeast(20f), text = b.text, size = b.size, centered = b.centered))
            }
            for (l in d.lines) strokes.add(stroke(floatArrayOf(l.x1, l.y1, 1f, l.x2, l.y2, 1f)))
            savePage(p.copy(strokes = strokes, texts = texts, title = if (n == 0) title else null))
            if (first < 0) first = notebook(nbId)!!.pageIds.indexOf(p.id)
        }
        return first
    }

    private fun removePageFiles(pid: String) {
        val p = pageCache.remove(pid) ?: read<Page>("pages/$pid.json")
        p?.background?.let { File(bgDir, it).delete() }
        File(root, "pages/$pid.json").delete()
        pageStamps.remove(pid)
        bury(pid)
        savePageStamps()
    }

    fun addPage(nbId: String, afterIndex: Int, background: String? = null): Page {
        val nb = notebook(nbId)!!
        val p = Page(notebookId = nbId, background = background)
        savePage(p)
        val ids = nb.pageIds.toMutableList()
        ids.add((afterIndex + 1).coerceIn(0, ids.size), p.id)
        updateNotebook(nb.copy(pageIds = ids))
        return p
    }

    fun deletePage(nbId: String, pageId: String) {
        val nb = notebook(nbId) ?: return
        if (nb.pageIds.size <= 1) return
        removePageFiles(pageId)
        val r = Outline.remove(nb.pageIds, nb.parents, pageId)
        updateNotebook(nb.copy(pageIds = r.ids, parents = r.parents))
    }

    /** New page nested under [parentId], placed after that page's existing sub-pages. */
    fun addSubpage(nbId: String, parentId: String): Page {
        val nb = notebook(nbId)!!
        val p = Page(notebookId = nbId)
        savePage(p)
        val r = Outline.addSub(nb.pageIds, nb.parents, parentId, p.id)
        updateNotebook(nb.copy(pageIds = r.ids, parents = r.parents))
        return p
    }

    /** Make the page a sub-page of the page just above it at the same level. Returns false if there is none. */
    fun indentPage(nbId: String, pageId: String): Boolean {
        val nb = notebook(nbId) ?: return false
        val r = Outline.indent(nb.pageIds, nb.parents, pageId) ?: return false
        updateNotebook(nb.copy(pageIds = r.ids, parents = r.parents))
        return true
    }

    /** Move the page (with its sub-pages) up one level, to just after its old parent's branch. */
    fun outdentPage(nbId: String, pageId: String): Boolean {
        val nb = notebook(nbId) ?: return false
        val r = Outline.outdent(nb.pageIds, nb.parents, pageId) ?: return false
        updateNotebook(nb.copy(pageIds = r.ids, parents = r.parents))
        return true
    }

    /** Move the page (with its sub-pages) up or down among its siblings. */
    fun movePage(nbId: String, pageId: String, dir: Int): Boolean {
        val nb = notebook(nbId) ?: return false
        val r = Outline.move(nb.pageIds, nb.parents, pageId, dir) ?: return false
        updateNotebook(nb.copy(pageIds = r.ids, parents = r.parents))
        return true
    }

    /** Today's page in the Daily notebook (both made if they don't exist yet). Returns the notebook id and page index. */
    fun dailyPage(): Pair<String, Int> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        var nb = notebooks.firstOrNull { it.daily }
        if (nb == null) {
            nb = createNotebook("Daily", Template.LINED)
            updateNotebook(nb.copy(daily = true))
            nb = notebook(nb.id)!!
        }
        val existing = nb.pageIds.indexOfFirst { page(it, nb.id).day == today }
        if (existing >= 0) return nb.id to existing
        // the notebook starts with one blank page: use it for the first day
        val first = nb.pageIds.firstOrNull()?.let { page(it, nb.id) }
        val heading = java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US).format(java.util.Date())
        val head = PageText(x = 60f, y = 70f, w = 880f, text = heading, size = 30f, centered = false)
        if (nb.pageIds.size == 1 && first != null && first.day == null && first.strokes.isEmpty() && first.texts.isEmpty()) {
            savePage(first.copy(day = today, texts = listOf(head)))
            return nb.id to 0
        }
        val p = addPage(nb.id, nb.pageIds.lastIndex)
        savePage(p.copy(day = today, texts = listOf(head)))
        return nb.id to (notebook(nb.id)!!.pageIds.indexOf(p.id))
    }

    // ---- pages ----
    fun page(id: String, nbId: String): Page {
        pageCache[id]?.let { return it }
        val p = read<Page>("pages/$id.json") ?: Page(id = id, notebookId = nbId)
        pageCache[id] = p
        return p
    }

    /** Saves a page; [touch] stamps it as edited now (false for background updates like indexing). */
    fun savePage(p0: Page, touch: Boolean = true) {
        val p = if (touch) p0.copy(updatedAt = System.currentTimeMillis()) else p0
        pageCache[p.id] = p
        pageStamps[p.id] = p.updatedAt
        writeAsync("pages/${p.id}.json", json.encodeToString(p))
        savePageStamps()
        pagesVersion++
        if (touch) notebook(p.notebookId)?.let { nb ->
            val i = notebooks.indexOf(nb)
            if (i >= 0) notebooks[i] = nb.copy(updatedAt = System.currentTimeMillis())
            saveNotebooks()
        }
    }

    /** Every page as (notebook, index, page). Loads pages from disk as needed. */
    fun allPages(): List<Triple<Notebook, Int, Page>> =
        notebooks.toList().flatMap { nb -> nb.pageIds.mapIndexed { i, pid -> Triple(nb, i, page(pid, nb.id)) } }

    // ---- recordings ----
    fun recording(id: String): Recording? = recordings.firstOrNull { it.id == id }

    fun upsertRecording(r: Recording) {
        val stamped = r.copy(updatedAt = System.currentTimeMillis())
        val i = recordings.indexOfFirst { it.id == r.id }
        if (i >= 0) recordings[i] = stamped else recordings.add(0, stamped)
        saveRecordings()
    }

    fun deleteRecording(id: String) {
        recording(id)?.let { File(audioDir, it.fileName).delete() }
        recordings.removeAll { it.id == id }
        bury(id)
        saveRecordings()
    }

    fun audioFile(r: Recording): File = File(audioDir, r.fileName)

    // ---- tasks ----
    fun addTasks(texts: List<String>, source: String?) = addTasksWithDue(texts.map { it to null }, source)

    fun addTasksWithDue(items: List<Pair<String, String?>>, source: String?) {
        items.filter { it.first.isNotBlank() }.forEach { (text, due) ->
            val t = text.trim()
            // running minutes twice must not list the same action items twice
            if (tasks.any { !it.done && it.text == t && it.source == source }) return@forEach
            tasks.add(0, TaskItem(text = t, source = source, due = due, updatedAt = System.currentTimeMillis()))
        }
        saveTasks()
    }

    /** Adds one task and returns it (so a checkbox on a page can link to it). */
    fun addTask(text: String, due: String?, source: String?, done: Boolean = false): TaskItem {
        val t = TaskItem(text = text.trim(), done = done, source = source, due = due, updatedAt = System.currentTimeMillis())
        tasks.add(0, t)
        saveTasks()
        return t
    }

    fun updateTask(t: TaskItem) {
        val i = tasks.indexOfFirst { it.id == t.id }
        if (i >= 0) tasks[i] = t.copy(updatedAt = System.currentTimeMillis())
        saveTasks()
    }

    fun toggleTask(id: String) {
        tasks.firstOrNull { it.id == id }?.let { updateTask(it.copy(done = !it.done)) }
    }

    fun deleteTask(id: String) {
        tasks.removeAll { it.id == id }
        bury(id)
        saveTasks()
    }
}
