package com.jondude.noteup.audio

import com.jondude.noteup.data.Store

/** Names and terms likely to come up in a meeting, taken from the notebook it's recorded in. Helps recognition and cleanup. */
object Vocab {
    fun build(notebookId: String?): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String?) {
            val t = s?.trim().orEmpty()
            if (t.length in 3..40) out.add(t)
        }
        try {
            val nb = notebookId?.let { Store.notebook(it) }
            if (nb != null) {
                add(nb.title)
                nb.title.split(' ', '·', '-', ':').forEach { if (it.length >= 4) add(it) }
                for (pid in nb.pageIds.take(40)) {
                    val p = Store.page(pid, nb.id)
                    add(p.title)
                    p.tags.forEach { add(it) }
                    // capitalised words in converted handwriting are usually names and products
                    p.convertedText?.let { t ->
                        Regex("""\b[A-Z][a-zA-Z]{3,}\b""").findAll(t).forEach { add(it.value) }
                    }
                }
            }
            Store.recordings.take(15).forEach { r -> r.title.split(' ', '·').forEach { if (it.length >= 4 && it[0].isUpperCase()) add(it) } }
        } catch (_: Exception) {}
        return out.take(80)
    }
}
