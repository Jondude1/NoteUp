package com.jondude.noteup.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
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
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: File

    val notebooks = mutableStateListOf<Notebook>()
    val recordings = mutableStateListOf<Recording>()
    val tasks = mutableStateListOf<TaskItem>()

    private val pageCache = HashMap<String, Page>()

    val audioDir: File get() = File(root, "audio").apply { mkdirs() }

    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = File(ctx.filesDir, "noteup").apply { mkdirs() }
        File(root, "pages").mkdirs()
        notebooks.addAll(read<List<Notebook>>("notebooks.json") ?: emptyList())
        recordings.addAll(read<List<Recording>>("recordings.json") ?: emptyList())
        tasks.addAll(read<List<TaskItem>>("tasks.json") ?: emptyList())
        if (notebooks.isEmpty()) createNotebook("My first notebook", Template.LINED)
    }

    private inline fun <reified T> read(name: String): T? = try {
        val f = File(root, name)
        if (f.exists()) json.decodeFromString<T>(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeAsync(name: String, text: String) {
        io.launch {
            val f = File(root, name)
            val tmp = File(root, "$name.tmp")
            tmp.parentFile?.mkdirs()
            tmp.writeText(text)
            tmp.renameTo(f)
        }
    }

    private fun saveNotebooks() = writeAsync("notebooks.json", json.encodeToString(notebooks.toList()))
    private fun saveRecordings() = writeAsync("recordings.json", json.encodeToString(recordings.toList()))
    private fun saveTasks() = writeAsync("tasks.json", json.encodeToString(tasks.toList()))

    // ---- notebooks ----
    fun notebook(id: String): Notebook? = notebooks.firstOrNull { it.id == id }

    fun createNotebook(title: String, template: Template): Notebook {
        val nb = Notebook(title = title, template = template)
        val first = Page(notebookId = nb.id)
        savePage(first)
        val withPage = nb.copy(pageIds = listOf(first.id))
        notebooks.add(0, withPage)
        saveNotebooks()
        return withPage
    }

    fun updateNotebook(nb: Notebook) {
        val i = notebooks.indexOfFirst { it.id == nb.id }
        if (i >= 0) notebooks[i] = nb.copy(updatedAt = System.currentTimeMillis())
        saveNotebooks()
    }

    fun deleteNotebook(id: String) {
        val nb = notebook(id) ?: return
        nb.pageIds.forEach { pid -> pageCache.remove(pid); File(root, "pages/$pid.json").delete() }
        notebooks.removeAll { it.id == id }
        saveNotebooks()
    }

    fun addPage(nbId: String, afterIndex: Int): Page {
        val nb = notebook(nbId)!!
        val p = Page(notebookId = nbId)
        savePage(p)
        val ids = nb.pageIds.toMutableList()
        ids.add((afterIndex + 1).coerceIn(0, ids.size), p.id)
        updateNotebook(nb.copy(pageIds = ids))
        return p
    }

    fun deletePage(nbId: String, pageId: String) {
        val nb = notebook(nbId) ?: return
        if (nb.pageIds.size <= 1) return
        pageCache.remove(pageId)
        File(root, "pages/$pageId.json").delete()
        updateNotebook(nb.copy(pageIds = nb.pageIds - pageId))
    }

    // ---- pages ----
    fun page(id: String, nbId: String): Page {
        pageCache[id]?.let { return it }
        val p = read<Page>("pages/$id.json") ?: Page(id = id, notebookId = nbId)
        pageCache[id] = p
        return p
    }

    fun savePage(p: Page) {
        pageCache[p.id] = p
        writeAsync("pages/${p.id}.json", json.encodeToString(p))
        notebook(p.notebookId)?.let { nb ->
            val i = notebooks.indexOf(nb)
            if (i >= 0) notebooks[i] = nb.copy(updatedAt = System.currentTimeMillis())
            saveNotebooks()
        }
    }

    // ---- recordings ----
    fun recording(id: String): Recording? = recordings.firstOrNull { it.id == id }

    fun upsertRecording(r: Recording) {
        val i = recordings.indexOfFirst { it.id == r.id }
        if (i >= 0) recordings[i] = r else recordings.add(0, r)
        saveRecordings()
    }

    fun deleteRecording(id: String) {
        recording(id)?.let { File(audioDir, it.fileName).delete() }
        recordings.removeAll { it.id == id }
        saveRecordings()
    }

    fun audioFile(r: Recording): File = File(audioDir, r.fileName)

    // ---- tasks ----
    fun addTasks(texts: List<String>, source: String?) {
        texts.filter { it.isNotBlank() }.forEach { tasks.add(0, TaskItem(text = it.trim(), source = source)) }
        saveTasks()
    }

    fun toggleTask(id: String) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i >= 0) tasks[i] = tasks[i].copy(done = !tasks[i].done)
        saveTasks()
    }

    fun deleteTask(id: String) {
        tasks.removeAll { it.id == id }
        saveTasks()
    }
}
