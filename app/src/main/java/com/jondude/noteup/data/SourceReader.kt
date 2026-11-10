package com.jondude.noteup.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jondude.noteup.ai.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Turns a web link or a PDF into plain text for a notebook's sources. */
object SourceReader {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    suspend fun web(url0: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val url = if (url0.startsWith("http", true)) url0.trim() else "https://${url0.trim()}"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NoteUp").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("The site answered ${r.code}.")
            val body = r.body ?: throw IllegalStateException("Empty page.")
            val raw = body.source().let { src -> src.request(2_500_000); src.buffer.clone().readUtf8() }
            val ctype = r.header("Content-Type").orEmpty()
            val text = if (ctype.contains("html", true) || raw.trimStart().startsWith("<")) Html.toText(raw) else raw
            if (text.length < 200) throw IllegalStateException("I couldn't find readable text on that page.")
            (Html.title(raw) ?: url.removePrefix("https://").removePrefix("http://").take(60)) to text.take(200_000)
        }
    }

    /** Reads every page of a PDF with on-device text recognition (works for scans too). Up to 80 pages. */
    suspend fun pdf(ctx: Context, uri: Uri, progress: (Int, Int) -> Unit): String = withContext(Dispatchers.IO) {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Couldn't open that PDF.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val sb = StringBuilder()
        pfd.use {
            val renderer = PdfRenderer(it)
            renderer.use { rd ->
                val n = minOf(rd.pageCount, 80)
                for (i in 0 until n) {
                    progress(i + 1, n)
                    rd.openPage(i).use { page ->
                        val w = 1500
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                        bmp.recycle()
                        if (res.text.isNotBlank()) sb.append(res.text.trim()).append("\n\n")
                    }
                }
            }
        }
        recognizer.close()
        if (sb.isBlank()) throw IllegalStateException("No readable text found in that PDF.")
        sb.toString().trim()
    }
}
