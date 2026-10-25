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
        notebooks.clear(); recordings.clear(); tasks.clear()
        notebooks.addAll(read<List<Notebook>>("notebooks.json") ?: emptyList())
        recordings.addAll(read<List<Recording>>("recordings.json") ?: emptyList())
        tasks.addAll(read<List<TaskItem>>("tasks.json") ?: emptyList())
        tombstones.clear(); tombstones.putAll(read<Map<String, Long>>("tombstones.json") ?: emptyMap())
        pageStamps.clear(); pageStamps.putAll(read<Map<String, Long>>("page-stamps.json") ?: emptyMap())
        pageCache.clear()
        pagesVersion++
    }

    inline fun <reified T> read(name: String): T? = try {
        val f = File(root, name)
        if (f.exists()) json.decodeFromString<T>(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun writeNow(name: String, text: String) {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private fun writeAsync(name: String, text: String) {
        io.launch { synchronized(this@Store) { writeNow(name, text) } }
        onLocalChange?.invoke()
    }

    private fun saveNotebooks() = writeAsync("notebooks.json", json.encodeToString(notebooks.toList()))
    private fun saveRecordings() = writeAsync("recordings.json", json.encodeToString(recordings.toList()))
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
        updateNotebook(nb.copy(pageIds = nb.pageIds - pageId))
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
