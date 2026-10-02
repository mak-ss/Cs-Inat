// ! Bu araç @Blockades tarafından ARAS ile kodlanmıştır.
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

class Taraftarium24 : MainAPI() {
    override var mainUrl              = "https://patronsports2.cfd"
    override var name                 = "PatronSpor"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

    // Site ana URL'i (HTML sayfası için)
    private val siteUrl = "https://patronspor.com" // veya güncel domain

    /* -------------------- MainPage -------------------- */

    override val mainPage = mainPageOf(
        "$mainUrl/matches.php" to "Canlı Maçlar",
        "$mainUrl/channels.php" to "7/24 Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetchStreams(request.data)
        val cards = items.map { (id, title) ->
            newMovieSearchResponse(
                title,
                "stream:$id",
                TvType.Live
            ) {}
        }
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val matches = fetchStreams("$mainUrl/matches.php")
        val channels = fetchStreams("$mainUrl/channels.php")
        return (matches + channels)
            .filter { (_, name) -> name.lowercase().contains(q) }
            .map { (id, name) ->
                newMovieSearchResponse(name, "stream:$id", TvType.Live) {}
            }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val id = url.removePrefix("stream:")
        val title = resolveTitle(id) ?: "Kanal $id"
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = null
        }
    }

    /* -------------------- Links (player) -------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.removePrefix("stream:")
        if (id.isBlank()) return false

        // ch.html üzerinden stream URL'ini al
        val chUrl = "$siteUrl/ch.html?id=$id"
        val m3u8s = collectM3u8s(chUrl, referer = siteUrl, depth = 0, maxDepth = 3).distinct()
        
        m3u8s.forEach { url ->
            runCatching {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fixUrl(url),
                    referer = siteUrl,
                    name = name
                ).forEach(callback)
            }
        }
        return m3u8s.isNotEmpty()
    }

    /* -------------------- Helpers -------------------- */

    /** JSON endpoint'lerinden stream listesini çek */
    private suspend fun fetchStreams(endpoint: String): List<Pair<String, String>> {
        return try {
            val response = app.get(endpoint, referer = siteUrl).text
            val json = org.json.JSONArray(response)
            val out = mutableListOf<Pair<String, String>>()
            
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val url = obj.optString("URL", "")
                val idMatch = Regex("""[?&]id=([^&]+)""").find(url)
                val id = idMatch?.groupValues?.getOrNull(1) ?: continue
                
                // Başlık: HomeTeam vs AwayTeam veya Mac
                val home = obj.optString("HomeTeam", "")
                val away = obj.optString("AwayTeam", "")
                val mac = obj.optString("Mac", "")
                val title = when {
                    home.isNotBlank() && away.isNotBlank() -> "$home vs $away"
                    mac.isNotBlank() -> mac
                    else -> "Kanal $id"
                }
                out += id to title
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** ID'den başlık bul */
    private suspend fun resolveTitle(id: String): String? {
        val matches = fetchStreams("$mainUrl/matches.php")
        val channels = fetchStreams("$mainUrl/channels.php")
        return (matches + channels).firstOrNull { it.first == id }?.second
    }

    private suspend fun collectM3u8s(
        url: String,
        referer: String,
        depth: Int,
        maxDepth: Int
    ): List<String> {
        if (depth > maxDepth) return emptyList()

        val out = mutableListOf<String>()
        val res = app.get(url, referer = referer)
        val body = res.text
        val doc  = res.document

        out += findM3u8InText(body)

        doc.select("script").forEach { s ->
            val code: String? = if (s.hasAttr("src")) {
                val src = normalizeHref(s.attr("src"), base = url) ?: return@forEach
                runCatching { app.get(src, referer = url).text }.getOrNull()
            } else {
                s.data()
            }
            if (!code.isNullOrBlank()) out += findM3u8InText(code)
        }

        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src"), base = url) ?: return@forEach
            out += collectM3u8s(src, referer = url, depth = depth + 1, maxDepth = maxDepth)
        }

        return out
    }

    private fun findM3u8InText(text: String): List<String> {
        return Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text)
            .map { it.value }
            .toList()
    }

    private fun normalizeHref(href: String?, base: String? = null): String? {
        if (href.isNullOrBlank()) return null
        val raw = href.trim()
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            !base.isNullOrBlank() -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
                ?: fixUrl(raw)
            else -> fixUrl(raw)
        }
    }
}
