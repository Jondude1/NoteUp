package com.jondude.noteup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AiBackend { CLAUDE, LOCAL }
enum class SttEngine { ON_DEVICE, WHISPER }

/** App settings, backed by private SharedPreferences and exposed as Compose state. */
object Settings {
    private lateinit var prefs: SharedPreferences

    var backend by mutableStateOf(AiBackend.CLAUDE)
    var fallbackToOther by mutableStateOf(true)
    var claudeKey by mutableStateOf("")
    var claudeModel by mutableStateOf("claude-sonnet-5-5")
    var localUrl by mutableStateOf("http://192.168.1.50:11434")
    var localModel by mutableStateOf("llama3.1:8b")
    var localKey by mutableStateOf("")

    var sttEngine by mutableStateOf(SttEngine.ON_DEVICE)
    var whisperUrl by mutableStateOf("http://192.168.1.50:8000")
    var whisperModel by mutableStateOf("whisper-1")
    var language by mutableStateOf("en-US")

    var stylusOnly by mutableStateOf(true)
    var refreshFlash by mutableStateOf(true)
    var defaultTemplate by mutableStateOf(Template.LINED)
    var shapeSnap by mutableStateOf(true)

    /** AI gives pages a title and tags after they're read. */
    var autoTag by mutableStateOf(true)

    // sync (WebDAV: Nextcloud, ownCloud, any WebDAV server)
    var syncUrl by mutableStateOf("")
    var syncUser by mutableStateOf("")
    var syncPass by mutableStateOf("")
    var syncAudio by mutableStateOf(false)
    var autoSync by mutableStateOf(true)

    // daily brief
    var briefCalendar by mutableStateOf(true)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("noteup_settings", Context.MODE_PRIVATE)
        backend = enumOr(prefs.getString("backend", null), AiBackend.CLAUDE)
        fallbackToOther = prefs.getBoolean("fallback", true)
        claudeKey = prefs.getString("claudeKey", "") ?: ""
        claudeModel = prefs.getString("claudeModel", claudeModel) ?: claudeModel
        localUrl = prefs.getString("localUrl", localUrl) ?: localUrl
        localModel = prefs.getString("localModel", localModel) ?: localModel
        localKey = prefs.getString("localKey", "") ?: ""
        sttEngine = enumOr(prefs.getString("stt", null), SttEngine.ON_DEVICE)
        whisperUrl = prefs.getString("whisperUrl", whisperUrl) ?: whisperUrl
        whisperModel = prefs.getString("whisperModel", whisperModel) ?: whisperModel
        language = prefs.getString("language", language) ?: language
        stylusOnly = prefs.getBoolean("stylusOnly", true)
        refreshFlash = prefs.getBoolean("refreshFlash", true)
        defaultTemplate = enumOr(prefs.getString("template", null), Template.LINED)
        shapeSnap = prefs.getBoolean("shapeSnap", true)
        autoTag = prefs.getBoolean("autoTag", true)
        syncUrl = prefs.getString("syncUrl", "") ?: ""
        syncUser = prefs.getString("syncUser", "") ?: ""
        syncPass = prefs.getString("syncPass", "") ?: ""
        syncAudio = prefs.getBoolean("syncAudio", false)
        autoSync = prefs.getBoolean("autoSync", true)
        briefCalendar = prefs.getBoolean("briefCalendar", true)
    }

    fun save() {
        prefs.edit()
            .putString("backend", backend.name)
            .putBoolean("fallback", fallbackToOther)
            .putString("claudeKey", claudeKey.trim())
            .putString("claudeModel", claudeModel.trim())
            .putString("localUrl", localUrl.trim().trimEnd('/'))
            .putString("localModel", localModel.trim())
            .putString("localKey", localKey.trim())
            .putString("stt", sttEngine.name)
            .putString("whisperUrl", whisperUrl.trim().trimEnd('/'))
            .putString("whisperModel", whisperModel.trim())
            .putString("language", language.trim())
            .putBoolean("stylusOnly", stylusOnly)
            .putBoolean("refreshFlash", refreshFlash)
            .putString("template", defaultTemplate.name)
            .putBoolean("shapeSnap", shapeSnap)
            .putBoolean("autoTag", autoTag)
            .putString("syncUrl", syncUrl.trim())
            .putString("syncUser", syncUser.trim())
            .putString("syncPass", syncPass)
            .putBoolean("syncAudio", syncAudio)
            .putBoolean("autoSync", autoSync)
            .putBoolean("briefCalendar", briefCalendar)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        try { if (v == null) d else enumValueOf<E>(v) } catch (e: Exception) { d }
}
