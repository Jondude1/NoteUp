package com.jondude.noteup.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-updater. Looks at the newest GitHub release of the public repo, and if its build number is
 * higher than the installed one, downloads the APK and hands it to Android's installer.
 */
object Updater {
    private const val REPO = "Jondude1/NoteUp"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    class Release(val build: Long, val name: String, val apkUrl: String)

    var available by mutableStateOf<Release?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf(-1)
        private set
    private var lastCheck = 0L

    fun installedBuild(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (_: Exception) { 0L }

    /** [force] = from the button (always checks, always reports); otherwise a quiet check at most every 30 minutes. */
    suspend fun check(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (busy || now - lastCheck < 30 * 60_000L)) return
        lastCheck = now
        if (force) message = "Checking…"
        try {
            val rel = withContext(Dispatchers.IO) { fetchLatest() }
            val mine = installedBuild(ctx)
            if (rel != null && rel.build > mine) {
                available = rel
                if (force) message = "Build ${rel.build} is available."
            } else {
                available = null
                if (force) message = "You're up to date (build $mine)."
            }
        } catch (e: Exception) {
            if (force) message = "Couldn't check for updates: ${e.message}"
        }
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub said ${r.code}")
            val o = Json.parseToJsonElement(r.body!!.string()).jsonObject
            val name = o["name"]?.jsonPrimitive?.content.orEmpty()
            val build = Regex("""(\d+)\s*$""").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets: JsonArray = o["assets"]?.jsonArray ?: return null
            val apk = assets.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return null
            return Release(build, name, apk["browser_download_url"]!!.jsonPrimitive.content)
        }
    }

    /** Downloads the APK and opens the installer. Android asks once for "install unknown apps" permission. */
    suspend fun install(ctx: Context) {
        val rel = available ?: return
        if (busy) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            message = "Allow NoteUp to install updates, then tap Update again."
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true; progress = 0; message = "Downloading build ${rel.build}…"
        try {
            val file = withContext(Dispatchers.IO) { download(ctx, rel) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            message = "Installing…"
        } catch (e: Exception) {
            message = "Update failed: ${e.message}"
        } finally {
            busy = false; progress = -1
        }
    }

    private fun download(ctx: Context, rel: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "NoteUp-${rel.build}.apk")
        http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("download failed (${r.code})")
            val body = r.body!!
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress = (done * 100 / total).toInt()
                    }
                }
            }
        }
        return out
    }
}
