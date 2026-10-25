package com.jondude.noteup.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import androidx.core.content.FileProvider
import com.jondude.noteup.ink.HandwritingRecognizer
import com.jondude.noteup.ink.InkView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** PDF import (pages become backgrounds you can write on) and export/sharing. */
object Documents {

    // ---------------- import ----------------

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
        }
        return "Imported PDF"
    }

    /** Renders each PDF page to an image and makes a notebook of them. */
    suspend fun importPdf(ctx: Context, uri: Uri, progress: (Int, Int) -> Unit): Notebook = withContext(Dispatchers.IO) {
        val title = displayName(ctx, uri)
        val tmp = File(ctx.cacheDir, "import.pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val names = ArrayList<String?>()
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                val count = minOf(pdf.pageCount, 400)
                for (i in 0 until count) {
                    withContext(Dispatchers.Main) { progress(i + 1, count) }
                    pdf.openPage(i).use { page ->
                        val w = 1600
                        val h = (w * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = "${newId()}.png"
                        FileOutputStream(File(Store.bgDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                        names.add(name)
                    }
                }
            }
        }
        tmp.delete()
        withContext(Dispatchers.Main) { Store.createNotebook(title, Template.BLANK, names.ifEmpty { listOf(null) }) }
    }

    fun loadBackground(name: String?, maxWidth: Int = 1600): Bitmap? {
        if (name == null) return null
        val f = File(Store.bgDir, name)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------- export ----------------

    /** Draws a page exactly as it looks in the editor (paper, PDF, ink, Claude's notes). */
    fun renderPage(ctx: Context, nb: Notebook, page: Page, widthPx: Int): Bitmap {
        val h = (widthPx * PAGE_H / PAGE_W).roundToInt()
        val v = InkView(ctx)
        v.template = nb.template
        v.background = loadBackground(page.background)
        v.texts = page.texts
        v.setContent(page.strokes, page.annotations)
        v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, widthPx, h)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    /** Small preview without a View (safe off the main thread). */
    fun renderThumb(nb: Notebook, page: Page, widthPx: Int): Bitmap {
        val h = (widthPx * PAGE_H / PAGE_W).roundToInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        loadBackground(page.background, widthPx * 2)?.let { bg ->
            val s = minOf(widthPx.toFloat() / bg.width, h.toFloat() / bg.height)
            val w = bg.width * s; val hh = bg.height * s
            c.drawBitmap(bg, null, android.graphics.RectF((widthPx - w) / 2, (h - hh) / 2, (widthPx + w) / 2, (h + hh) / 2),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            bg.recycle()
        }
        val sc = widthPx / PAGE_W
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        for (st in page.strokes.sortedBy { !it.marker }) {
            p.color = when { st.marker -> Color.argb(70, 0, 0, 0); st.color == 1 -> Color.DKGRAY; st.color == 2 -> Color.LTGRAY; else -> Color.BLACK }
            p.strokeWidth = maxOf(1f, st.width * sc)
            val n = st.pts.size / 3
            for (i in 1 until n) c.drawLine(st.pts[(i - 1) * 3] * sc, st.pts[(i - 1) * 3 + 1] * sc, st.pts[i * 3] * sc, st.pts[i * 3 + 1] * sc, p)
        }
        return bmp
    }

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifBlank { "NoteUp" }

    suspend fun pagePng(ctx: Context, nb: Notebook, index: Int): File = withContext(Dispatchers.Main) {
        val page = Store.page(nb.pageIds[index], nb.id)
        val bmp = renderPage(ctx, nb, page, 2000)
        val f = File(exportDir(ctx), "${safe(nb.title)} p${index + 1}.png")
        withContext(Dispatchers.IO) { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bmp.recycle()
        f
    }

    /** PDF of the chosen pages (A4). */
    suspend fun pdf(ctx: Context, nb: Notebook, indices: List<Int>, progress: (Int, Int) -> Unit = { _, _ -> }): File {
        val doc = PdfDocument()
        val pw = 595
        val ph = 842
        indices.forEachIndexed { n, i ->
            progress(n + 1, indices.size)
            val page = Store.page(nb.pageIds[i], nb.id)
            val bmp = withContext(Dispatchers.Main) { renderPage(ctx, nb, page, 1650) }
            val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n + 1).create())
            val c = pdfPage.canvas
            c.drawColor(Color.WHITE)
            val s = minOf(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
            c.save(); c.scale(s, s); c.drawBitmap(bmp, 0f, 0f, null); c.restore()
            doc.finishPage(pdfPage)
            bmp.recycle()
        }
        val f = File(exportDir(ctx), "${safe(nb.title)}${if (indices.size == 1) " p${indices[0] + 1}" else ""}.pdf")
        withContext(Dispatchers.IO) { FileOutputStream(f).use { doc.writeTo(it) } }
        doc.close()
        return f
    }

    /** Markdown: converted handwriting, Claude's margin notes, and any linked minutes. */
    suspend fun markdown(ctx: Context, nb: Notebook, progress: (Int, Int) -> Unit = { _, _ -> }): File {
        val sb = StringBuilder("# ${nb.title}\n\n")
        nb.pageIds.forEachIndexed { i, pid ->
            progress(i + 1, nb.pageIds.size)
            var page = Store.page(pid, nb.id)
            if (page.convertedText == null && page.strokes.any { !it.marker }) {
                val text = try { HandwritingRecognizer.recognize(page.strokes.filter { !it.marker }, Settings.language) } catch (e: Exception) { null }
                if (text != null) { page = page.copy(convertedText = text); Store.savePage(page, touch = false) }
            }
            sb.append("## Page ${i + 1}${page.title?.let { " — $it" } ?: ""}\n\n")
            if (page.tags.isNotEmpty()) sb.append(page.tags.joinToString(" ") { "#" + it.replace(' ', '-') }).append("\n\n")
            page.convertedText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
            page.texts.forEach { sb.append("- ").append(it.text.replace('\n', ' ')).append('\n') }
            page.annotations.forEach { sb.append("> **Claude:** ").append(it.text.replace("\n", "\n> ")).append("\n\n") }
        }
        Store.recordings.filter { it.notebookId == nb.id && it.summary != null }.forEach { r ->
            sb.append("## Minutes — ${r.title}\n\n${r.summary}\n\n")
        }
        val f = File(exportDir(ctx), "${safe(nb.title)}.md")
        withContext(Dispatchers.IO) { f.writeText(sb.toString()) }
        return f
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
