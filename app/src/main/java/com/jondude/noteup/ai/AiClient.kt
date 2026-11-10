package com.jondude.noteup.ai

import com.jondude.noteup.data.AiBackend
import com.jondude.noteup.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AiException(msg: String) : Exception(msg)

/**
 * Talks to either Claude (Anthropic Messages API) or any OpenAI-compatible server
 * (Ollama, llama.cpp, LM Studio, vLLM...). Picks the backend from Settings and can
 * fall back to the other one if the first fails.
 */
object AiClient {
    /** Runs a request, retrying a couple of times on rate limits, overloads and network blips. */
    private fun send(req: Request): okhttp3.Response {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                val r = http.newCall(req).execute()
                if (r.code == 429 || r.code == 529 || r.code in 500..503) {
                    if (attempt == 2) return r
                    r.close(); Thread.sleep(1500L * (attempt + 1) * (attempt + 1)); continue
                }
                return r
            } catch (e: java.io.IOException) {
                last = e
                if (attempt < 2) Thread.sleep(1200L * (attempt + 1))
            }
        }
        throw AiException("Network problem: ${last?.message ?: "couldn't reach the AI"}")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_TYPE = "application/json".toMediaType()

    /** Which backend actually answered the last call — shown in the UI. */
    @Volatile var lastBackend: AiBackend = AiBackend.CLAUDE

    suspend fun ask(system: String, prompt: String, imagePngBase64: String? = null, maxTokens: Int = 2000): String =
        withContext(Dispatchers.IO) {
            val first = Settings.backend
            val other = if (first == AiBackend.CLAUDE) AiBackend.LOCAL else AiBackend.CLAUDE
            try {
                call(first, system, prompt, imagePngBase64, maxTokens).also { lastBackend = first }
            } catch (e: Exception) {
                if (Settings.fallbackToOther && configured(other)) {
                    call(other, system, prompt, imagePngBase64, maxTokens).also { lastBackend = other }
                } else throw e
            }
        }

    fun configured(b: AiBackend) = when (b) {
        AiBackend.CLAUDE -> Settings.claudeKey.isNotBlank()
        AiBackend.LOCAL -> Settings.localUrl.isNotBlank() && Settings.localModel.isNotBlank()
    }

    private fun call(b: AiBackend, system: String, prompt: String, img: String?, maxTokens: Int): String = when (b) {
        AiBackend.CLAUDE -> claude(system, prompt, img, maxTokens)
        AiBackend.LOCAL -> openAiCompatible(system, prompt, img, maxTokens)
    }

    private fun claude(system: String, prompt: String, img: String?, maxTokens: Int): String {
        if (Settings.claudeKey.isBlank()) throw AiException("Add your Claude API key in Settings.")
        val body = buildJsonObject {
            put("model", Settings.claudeModel)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (img != null) add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64"); put("media_type", "image/png"); put("data", img)
                            }
                        })
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    }
                })
            }
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", Settings.claudeKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        send(req).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("Claude error ${res.code}: ${errorMessage(text)}")
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["content"] as? JsonArray ?: throw AiException("Claude sent an empty reply.")
            return content.mapNotNull { block ->
                val o = block.jsonObject
                if (o["type"]?.jsonPrimitive?.content == "text") o["text"]?.jsonPrimitive?.content else null
            }.joinToString("\n").trim()
        }
    }


    /**
     * Asks Claude to search the web (its built-in server-side search) and returns the pages it found.
     * Needs web search enabled for the account in the Anthropic console; throws if it isn't.
     */
    suspend fun searchClaude(topic: String, searches: Int): List<Discover.Candidate> = withContext(Dispatchers.IO) {
        if (Settings.claudeKey.isBlank()) throw AiException("Add your Claude API key in Settings.")
        val body = buildJsonObject {
            put("model", Settings.claudeModel)
            put("max_tokens", 1500)
            put("system", "You are a research assistant. Use web search several times with different angles (background, recent developments, data, expert analysis, opposing views). Prefer reputable, readable pages over PDFs and paywalls. Reply with one short line when done.")
            putJsonArray("tools") {
                add(buildJsonObject { put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", searches) })
            }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", "Find many varied, high-quality web sources that would strengthen research on this subject:\n$topic") })
            }
        }
        val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", Settings.claudeKey).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        send(req).use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("Claude web search error ${res.code}: ${errorMessage(text)}")
            val content = json.parseToJsonElement(text).jsonObject["content"] as? JsonArray ?: return@use emptyList<Discover.Candidate>()
            val out = ArrayList<Discover.Candidate>()
            for (b in content) {
                val o = b.jsonObject
                if (o["type"]?.jsonPrimitive?.content != "web_search_tool_result") continue
                val results = o["content"] as? JsonArray ?: continue
                for (r in results) {
                    val ro = r.jsonObject
                    val url = ro["url"]?.jsonPrimitive?.content ?: continue
                    out.add(Discover.Candidate(ro["title"]?.jsonPrimitive?.content ?: url, url))
                }
            }
            out
        }
    }

    private fun openAiCompatible(system: String, prompt: String, img: String?, maxTokens: Int): String {
        val base = Settings.localUrl.trimEnd('/')
        if (base.isBlank()) throw AiException("Set your local AI server address in Settings.")
        val body = buildJsonObject {
            put("model", Settings.localModel)
            put("max_tokens", maxTokens)
            put("stream", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject {
                    put("role", "user")
                    if (img == null) put("content", prompt)
                    else putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/png;base64,$img") }
                        })
                    }
                })
            }
        }
        val rb = Request.Builder().url("$base/v1/chat/completions").post(body.toString().toRequestBody(JSON_TYPE))
        if (Settings.localKey.isNotBlank()) rb.header("Authorization", "Bearer ${Settings.localKey}")
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("Local AI error ${res.code}: ${errorMessage(text)}")
            val root = json.parseToJsonElement(text).jsonObject
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
                ?: throw AiException("Local AI sent an empty reply.")
        }
    }

    /** Sends a WAV clip to an OpenAI-compatible /v1/audio/transcriptions endpoint (faster-whisper-server, whisper.cpp server, etc). */
    suspend fun transcribe(wav: ByteArray, hint: String = ""): String = withContext(Dispatchers.IO) {
        val base = Settings.whisperUrl.trimEnd('/')
        if (base.isBlank()) throw AiException("Set your Whisper server address in Settings.")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", Settings.whisperModel)
            .addFormDataPart("language", Settings.language.substringBefore('-'))
            .addFormDataPart("response_format", "json")
            .apply { if (hint.isNotBlank()) addFormDataPart("prompt", hint.take(500)) }
            .addFormDataPart("file", "clip.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val rb = Request.Builder().url("$base/v1/audio/transcriptions").post(form)
        if (Settings.localKey.isNotBlank()) rb.header("Authorization", "Bearer ${Settings.localKey}")
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("Whisper error ${res.code}: ${errorMessage(text)}")
            json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        }
    }

    private fun errorMessage(body: String): String = try {
        val o = json.parseToJsonElement(body).jsonObject
        val err = o["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.content ?: body.take(200)
            null -> body.take(200)
            else -> err.jsonPrimitive.content
        }
    } catch (e: Exception) {
        body.take(200)
    }
}
