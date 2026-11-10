package com.jondude.noteup.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps both tablets in step through a WebDAV folder (Nextcloud, ownCloud, any WebDAV server).
 * Everything is merged item by item: the newest edit of each notebook, page, recording and task wins,
 * and deletions carry over. Pages only transfer when they changed.
 */
object Sync {
    var status by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pendingJob: Job? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(300, TimeUnit.SECONDS).build()

    val configured get() = Settings.syncUrl.isNotBlank()

    /** Sync a little while after local edits stop (auto sync). */
    fun schedule(delayMs: Long = 45_000) {
        if (!configured || !Settings.autoSync) return
        pendingJob?.cancel()
        pendingJob = scope.launch { delay(delayMs); runNow() }
    }

    fun syncSoon() { if (configured && Settings.autoSync) scope.launch { runNow() } }

    suspend fun runNow(): Boolean = lock.withLock {
        if (!configured) return false
        withContext(Dispatchers.Main) { running = true; status = "Syncing…" }
        return try {
            // network work must never run on the UI thread (the "Sync now" button calls from there)
            val msg = withContext(Dispatchers.IO) { sync() }
            withContext(Dispatchers.Main) { status = msg }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { status = "Sync failed: ${e.message ?: e.javaClass.simpleName}" }
            false
        } finally {
            withContext(Dispatchers.Main) { running = false }
        }
    }

    // ---------------- WebDAV ----------------

    private val base get() = Settings.syncUrl.trim().trimEnd('/') + "/NoteUp"

    private fun req(path: String) = Request.Builder().url("$base/$path")
        .header("Authorization", Credentials.basic(Settings.syncUser, Settings.syncPass))

    private fun mkcol(path: String) {
        http.newCall(req(path).method("MKCOL", null).build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 405 && r.code != 301) {
                if (r.code == 401) throw IllegalStateException("wrong user name or password")
                if (r.code == 404 || r.code == 409) throw IllegalStateException("folder not found — check the server address")
            }
        }
    }

    private fun get(path: String): ByteArray? =
        http.newCall(req(path).get().build()).execute().use { r ->
            when {
                r.code == 404 -> null
                r.code == 401 -> throw IllegalStateException("wrong user name or password")
                !r.isSuccessful -> throw IllegalStateException("server said ${r.code} for $path")
                else -> r.body?.bytes()
            }
        }

    private fun exists(path: String): Boolean =
        http.newCall(req(path).head().build()).execute().use { it.isSuccessful }

    private fun put(path: String, bytes: ByteArray, type: String = "application/json") {
        http.newCall(req(path).put(bytes.toRequestBody(type.toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("upload failed (${r.code}) for $path")
        }
    }

    private fun putFile(path: String, f: File, type: String) {
        http.newCall(req(path).put(f.asRequestBody(type.toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("upload failed (${r.code}) for $path")
        }
    }

    private inline fun <reified T> remote(path: String, default: T): T =
        get(path)?.let { try { Store.json.decodeFromString<T>(it.decodeToString()) } catch (e: Exception) { default } } ?: default

    // ---------------- merge ----------------

    private fun <T> merge(local: List<T>, remote: List<T>, id: (T) -> String, stamp: (T) -> Long, dead: Map<String, Long>): List<T> {
        val out = LinkedHashMap<String, T>()
        for (item in local + remote) {
            val k = id(item)
            val prev = out[k]
            if (prev == null || stamp(item) > stamp(prev)) out[k] = item
        }
        return out.values.filter { item -> dead[id(item)]?.let { it < stamp(item) } ?: true }
    }

    private fun sync(): String {
        mkcol(""); mkcol("pages"); mkcol("backgrounds"); if (Settings.syncAudio) mkcol("audio")

        val local = Snapshot(
            Store.read<List<Notebook>>("notebooks.json") ?: emptyList(),
            Store.read<List<Recording>>("recordings.json") ?: emptyList(),
            Store.read<List<TaskItem>>("tasks.json") ?: emptyList(),
            Store.read<Map<String, Long>>("tombstones.json") ?: emptyMap(),
            Store.read<Map<String, Long>>("page-stamps.json") ?: emptyMap(),
        )
        val rTomb = remote<Map<String, Long>>("tombstones.json", emptyMap())
        val dead = HashMap(local.tombstones).apply { rTomb.forEach { (k, v) -> merge(k, v) { a, b -> maxOf(a, b) } } }
        val notebooks = merge(local.notebooks, remote("notebooks.json", emptyList()), { it.id }, { it.updatedAt }, dead)
            .sortedByDescending { it.updatedAt }
        val recordings = merge(local.recordings, remote("recordings.json", emptyList()), { it.id }, { maxOf(it.updatedAt, it.createdAt) }, dead)
            .sortedByDescending { it.createdAt }
        val tasks = merge(local.tasks, remote("tasks.json", emptyList()), { it.id }, { maxOf(it.updatedAt, it.createdAt) }, dead)
            .sortedByDescending { it.createdAt }
        val localSources = Store.read<List<Source>>("sources.json") ?: emptyList()
        val localStudio = Store.read<List<StudioItem>>("studio.json") ?: emptyList()
        val sources = merge(localSources, remote("sources.json", emptyList()), { it.id }, { it.updatedAt }, dead)
            .filter { s -> notebooks.any { it.id == s.notebookId } }.sortedBy { it.addedAt }
        val studio = merge(localStudio, remote("studio.json", emptyList()), { it.id }, { it.updatedAt }, dead)
            .filter { s -> notebooks.any { it.id == s.notebookId } }.sortedBy { it.createdAt }
        val rStamps = remote<Map<String, Long>>("page-stamps.json", emptyMap())

        var up = 0
        var down = 0
        val stamps = HashMap<String, Long>()
        for (nb in notebooks) for (pid in nb.pageIds) {
            if (dead.containsKey(pid)) continue
            val l = local.pageStamps[pid] ?: -1L
            val r = rStamps[pid] ?: -1L
            when {
                r > l -> {
                    val bytes = get("pages/$pid.json") ?: continue
                    Store.writeNow("pages/$pid.json", bytes.decodeToString())
                    val page = Store.json.decodeFromString<Page>(bytes.decodeToString())
                    page.background?.let { bg ->
                        val f = File(Store.bgDir, bg)
                        if (!f.exists()) get("backgrounds/$bg")?.let { f.writeBytes(it) }
                    }
                    stamps[pid] = r; down++
                }
                l > r -> {
                    val f = File(Store.root, "pages/$pid.json")
                    if (!f.exists()) continue
                    put("pages/$pid.json", f.readBytes())
                    val page = Store.read<Page>("pages/$pid.json")
                    page?.background?.let { bg ->
                        val bf = File(Store.bgDir, bg)
                        if (bf.exists() && !exists("backgrounds/$bg")) putFile("backgrounds/$bg", bf, "image/png")
                    }
                    stamps[pid] = l; up++
                }
                else -> if (l >= 0) stamps[pid] = l
            }
        }

        // pages deleted elsewhere
        for (pid in dead.keys) {
            File(Store.root, "pages/$pid.json").takeIf { it.exists() }?.delete()
        }

        if (Settings.syncAudio) for (r in recordings) {
            val f = File(Store.audioDir, r.fileName)
            if (f.exists() && f.length() > 44) {
                if (!exists("audio/${r.fileName}")) { putFile("audio/${r.fileName}", f, "audio/wav"); up++ }
            } else if (!f.exists()) {
                get("audio/${r.fileName}")?.let { f.writeBytes(it); down++ }
            }
        }

        val enc = Store.json
        val lists = mapOf(
            "notebooks.json" to enc.encodeToString(notebooks),
            "recordings.json" to enc.encodeToString(recordings),
            "tasks.json" to enc.encodeToString(tasks),
            "sources.json" to enc.encodeToString(sources),
            "studio.json" to enc.encodeToString(studio),
            "tombstones.json" to enc.encodeToString(dead.toMap()),
            "page-stamps.json" to enc.encodeToString(stamps.toMap()),
        )
        synchronized(Store) { lists.forEach { (name, text) -> Store.writeNow(name, text) } }
        lists.forEach { (name, text) -> put(name, text.encodeToByteArray()) }

        val changedLocally = down > 0 || notebooks != local.notebooks || recordings != local.recordings || tasks != local.tasks ||
            sources != localSources.sortedBy { it.addedAt } || studio != localStudio.sortedBy { it.createdAt }
        if (changedLocally) kotlinx.coroutines.runBlocking(Dispatchers.Main) { Store.reloadLists() }

        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        return "Synced at $time" + if (up + down > 0) " · $up up, $down down" else " · up to date"
    }

    private class Snapshot(
        val notebooks: List<Notebook>,
        val recordings: List<Recording>,
        val tasks: List<TaskItem>,
        val tombstones: Map<String, Long>,
        val pageStamps: Map<String, Long>,
    )
}
