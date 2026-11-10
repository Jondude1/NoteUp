package com.jondude.noteup.ai

import java.net.URI
import java.net.URLDecoder

/** Helpers for finding sources on the web: reading search result pages and keeping a varied, de-duplicated list. */
object Discover {
    class Candidate(val title: String, val url: String)

    /** Results from DuckDuckGo's plain HTML page. Links come wrapped in a redirect (uddg=...) that is unwrapped here. */
    fun parseDuckDuckGo(html: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        val anchor = Regex("""<a\b[^>]*class="[^"]*result__a[^"]*"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in anchor.findAll(html)) {
            val tag = m.value.substringBefore('>')
            val href = Regex("""href="([^"]+)"""").find(tag)?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
            val title = Html.toText(m.groupValues[1]).replace('\n', ' ').trim()
            val url = unwrap(href) ?: continue
            if (title.isNotEmpty()) out.add(Candidate(title, url))
        }
        return out
    }

    private fun unwrap(href: String): String? {
        val full = if (href.startsWith("//")) "https:$href" else href
        if (full.contains("uddg=")) {
            val enc = full.substringAfter("uddg=").substringBefore('&')
            return try { URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { null }
        }
        return if (full.startsWith("http")) full else null
    }

    private val skipExt = setOf("pdf", "zip", "mp4", "mp3", "jpg", "jpeg", "png", "gif", "exe", "apk", "dmg", "pptx", "xlsx", "docx", "mov", "avi")

    /** The same page reached by different links should compare equal: no scheme, www, fragment, tracking params or trailing slash. */
    fun normalize(url: String): String = try {
        val u = URI(url.trim())
        val host = (u.host ?: return url.trim().lowercase()).lowercase().removePrefix("www.")
        val keep = (u.rawQuery ?: "").split('&').filter { it.isNotEmpty() && !it.startsWith("utm_") && !it.startsWith("fbclid") && !it.startsWith("gclid") }
        host + (u.rawPath ?: "").trimEnd('/') + if (keep.isNotEmpty()) "?" + keep.sorted().joinToString("&") else ""
    } catch (e: Exception) { url.trim().lowercase() }

    fun domain(url: String): String = try { URI(url).host?.lowercase()?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }

    fun readable(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        val path = try { URI(url).path.orEmpty().lowercase() } catch (e: Exception) { return false }
        return path.substringAfterLast('.', "") !in skipExt
    }

    /** Drops duplicates, files we can't read and links already used; keeps at most [perDomain] per site so one site can't fill the list. */
    fun pick(all: List<Candidate>, limit: Int, perDomain: Int = 2, exclude: Set<String> = emptySet()): List<Candidate> {
        val seen = HashSet<String>(exclude.map { normalize(it) })
        val perSite = HashMap<String, Int>()
        val out = ArrayList<Candidate>()
        for (c in all) {
            if (out.size >= limit) break
            if (!readable(c.url)) continue
            val key = normalize(c.url)
            if (!seen.add(key)) continue
            val d = domain(c.url)
            if (perSite.getOrDefault(d, 0) >= perDomain) continue
            perSite[d] = perSite.getOrDefault(d, 0) + 1
            out.add(c)
        }
        return out
    }

    /** Spreads results from several searches evenly: first of each list, then second of each, and so on. */
    fun interleave(lists: List<List<Candidate>>): List<Candidate> {
        val out = ArrayList<Candidate>()
        var i = 0
        while (lists.any { i < it.size }) { lists.forEach { l -> if (i < l.size) out.add(l[i]) }; i++ }
        return out
    }
}
