package com.jondude.noteup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AiBackend { CLAUDE, LOCAL }
enum class SttEngine { ON_DEVICE, WHISPER }

/** AUTO: fingers write until a real S Pen is seen, then fingers only navigate. */
enum class PenMode { AUTO, SPEN, ANY_STYLUS }
enum class PressureMode { AUTO, SIMULATED, OFF }

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

    var penMode by mutableStateOf(PenMode.AUTO)
    var pressureMode by mutableStateOf(PressureMode.AUTO)
    /** Contacts wider than this are treated as a palm. */
    var palmMm by mutableStateOf(7f)
    /** Key code a stylus button sends (learned in Settings), or -1. */
    var penKey by mutableStateOf(-1)
    var learningPenKey by mutableStateOf(false)
    var scratchErase by mutableStateOf(true)
    var stylusSeen by mutableStateOf(false)
    /** Hand rest line as a fraction of canvas height, or -1 when off. */
    var handRest by mutableStateOf(-1f)
    var refreshFlash by mutableStateOf(true)
    var defaultTemplate by mutableStateOf(Template.LINED)

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
        penMode = enumOr(prefs.getString("penMode", null), PenMode.AUTO)
        pressureMode = enumOr(prefs.getString("pressureMode", null), PressureMode.AUTO)
        palmMm = prefs.getFloat("palmMm", 7f)
        penKey = prefs.getInt("penKey", -1)
        scratchErase = prefs.getBoolean("scratchErase", true)
        stylusSeen = prefs.getBoolean("stylusSeen", false)
        handRest = prefs.getFloat("handRest", -1f)
        refreshFlash = prefs.getBoolean("refreshFlash", true)
        defaultTemplate = enumOr(prefs.getString("template", null), Template.LINED)
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
            .putString("penMode", penMode.name)
            .putString("pressureMode", pressureMode.name)
            .putFloat("palmMm", palmMm)
            .putInt("penKey", penKey)
            .putBoolean("scratchErase", scratchErase)
            .putBoolean("stylusSeen", stylusSeen)
            .putFloat("handRest", handRest)
            .putBoolean("refreshFlash", refreshFlash)
            .putString("template", defaultTemplate.name)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        try { if (v == null) d else enumValueOf<E>(v) } catch (e: Exception) { d }
}

/** Counts presses of a stylus button that arrive as key events (most passive/BT styluses). */
object PenKeys {
    var presses by mutableStateOf(0)
}
