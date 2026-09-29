package com.Blockades

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziFilm : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilm"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        private val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

        private val mainSections = listOf(
            "Son Eklenenler" to "/",
            "Türkçe Dublaj" to "/turkce-dublaj-filmler",
            "Türkçe Altyazılı" to "/turkce-altyazili-filmler",
            "Yabancı Diziler" to "/yabanci-dizi-izle",
            "Trend Filmler" to "/trend-filmler",
            "Trend Diziler" to "/trend-diziler"
        )
    }

    // ── Main Page ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allPages = mutableListOf<HomePageList>()

        for ((title, path) in mainSections) {
            try {
                val html = app.get("$mainUrl$path", headers = defaultHeaders).text
                val doc = org.jsoup.Jsoup.parse(html)
                val rsc = parseRscPayload(html)
                val items = parseCardsFromPage(doc, rsc)
                if (items.isNotEmpty()) {
                    allPages.add(HomePageList(title, items))
                }
            } catch (_: Exception) {
                // Sessizce geç
            }
        }

        return newHomePageResponse(allPages)
    }

    /**
     * Hem DOM'dan hem RSC payload'dan kartları çıkarır.
     * RSC payload'da {"slug":"...","title":"...","posterUrl":"...","year":...,"imdbRating":...}
     * formatında film/dizi objeleri vardır. DOM'da ise `a.media-card__link` linkleri vardır.
     */
    private fun parseCardsFromPage(doc: Document, rsc: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        // ── Yöntem 1: DOM'dan media-card__link'leri çek (en güvenilir) ──
        val cards = doc.select("a.media-card__link, a[href^='/film/'], a[href^='/dizi/']")
        for (a in cards) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            if (href.contains("/sezon-") || href.contains("/bolum-")) continue
            if (!href.startsWith("/film/") && !href.startsWith("/dizi/")) continue

            val fullUrl = fixUrl(href)
            if (!seen.add(fullUrl)) continue

            val container = findCardContainer(a)
            val img = container?.selectFirst("img") ?: a.selectFirst("img")
            val poster = img?.let { extractPosterUrl(it) }

            val title = extractTitle(a, img)
            if (title.isBlank()) continue

            val isSeries = href.contains("/dizi/")
            val rating = container?.selectFirst("[class*='bg-yellow-500']")?.text()?.let {
                Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            }
            val year = container?.selectFirst("span.text-gray-300")?.text()?.toIntOrNull()

            if (isSeries) {
                items.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            } else {
                items.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            }
        }

        // ── Yöntem 2: RSC payload'dan tamamla (DOM'da olmayanları) ──
        val movieRegex = Regex(
            """"slug"\s*:\s*"([^"]+)"\s*,\s*"title"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*?"posterUrl"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in movieRegex.findAll(rsc)) {
            val slug = m.groupValues[1]
            val title = unescapeUnicode(m.groupValues[2])
            val rawPoster = m.groupValues[3].replace("\\/", "/")
            if (slug.isBlank() || title.isBlank()) continue

            val isSeries = slug.contains("-dizi") || slug.contains("dizisi")
            val fullUrl = if (isSeries) "$mainUrl/dizi/$slug" else "$mainUrl/film/$slug"
            if (!seen.add(fullUrl)) continue

            val blockStart = m.range.first
            val blockEnd = minOf(m.range.last + 500, rsc.length)
            val block = rsc.substring(blockStart, blockEnd)
            val year = Regex(""""year"\s*:\s*(\d{4})""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            val rating = Regex(""""imdb_rating"\s*:\s*(\d+(?:\.\d+)?)""").find(block)?.groupValues?.get(1)?.toDoubleOrNull()

            val poster = fixPosterUrl(rawPoster)
            if (isSeries) {
                items.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            } else {
                items.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            }
        }

        return items
    }

    private fun findCardContainer(a: Element): Element? {
        var p: Element? = a
        var depth = 0
        while (p != null && depth < 5) {
            if (p.tagName() == "div" && (p.hasClass("apple-card") || p.className().contains("group"))) {
                return p
            }
            p = p.parent()
            depth++
        }
        return a.parent()
    }

    private fun extractTitle(a: Element, img: Element?): String {
        return a.attr("aria-label").takeIf { it.isNotBlank() }
            ?: a.selectFirst(".sr-only")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.replace(Regex("""(?i)\s*izle\s*$"""), "")?.trim()?.takeIf { it.isNotBlank() }
            ?: a.text().trim()
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val searchApi = "$mainUrl/api/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}"

        val jsonStr = try {
            app.get(searchApi, headers = defaultHeaders).text
        } catch (_: Exception) {
            return emptyList()
        }

        val json = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return emptyList()
        }

        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() }
                ?: item.optString("original_title").takeIf { it.isNotBlank() } ?: continue
            val posterUrl = item.optString("poster_url").takeIf { it.isNotBlank() }?.let { fixPosterUrl(it) }
            val contentType = item.optString("content_type")
            val year = item.optInt("year", 0).takeIf { it > 0 }
            val rating = item.optDouble("imdb_rating", 0.0).takeIf { it > 0 }

            if (contentType == "movie") {
                val fullUrl = "$mainUrl/film/$slug"
                list.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            } else {
                val fullUrl = "$mainUrl/dizi/$slug"
                list.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                    if (rating != null) this.score = Score.from10(rating)
                })
            }
        }

        return list
    }

    // ── Details / Load ──────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document
        val html = doc.html()
        val rsc = parseRscPayload(html)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
                ?.replace(Regex("""(?i)\s*\|\s*Film izle.*$"""), "")?.trim()
            ?: Regex(""""name"\s*:\s*"([^"]+)"""").find(rsc)?.groupValues?.get(1)
            ?: "DiziFilm"

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixPosterUrl(it) }
            ?: doc.selectFirst("img.object-cover")?.let { extractPosterUrl(it) }
            ?: doc.selectFirst("img[srcSet*='/poster/']")?.let { extractPosterUrl(it) }
            ?: doc.selectFirst("img[src*='/poster/']")?.let { extractPosterUrl(it) }
            ?: Regex(""""(?:poster_url|thumbnailUrl)"\s*:\s*"([^"]+)"""").find(rsc)?.groupValues?.get(1)?.let { fixPosterUrl(it) }

        val plot = extractPlot(doc, rsc)

        val year = Regex(""""year"\s*:\s*(\d{4})""").find(rsc)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""href="/yil/(\d{4})"""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        val score = Regex(""""ratingValue"\s*:\s*(\d+(?:\.\d+)?)""").find(rsc)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex(""""imdb_rating"\s*:\s*(\d+(?:\.\d+)?)""").find(rsc)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        val tags = doc.select("a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val isMovie = url.contains("/film/")
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                if (score != null) this.score = Score.from10(score)
            }
        }

        // ── TV Series ──
        val seriesSlug = Regex("""/dizi/([^/?#]+)""").find(url)?.groupValues?.get(1) ?: ""
        var episodes = if (seriesSlug.isNotBlank()) {
            parseSeasonsWithEpisodes(rsc, seriesSlug)
        } else {
            emptyList()
        }

        if (episodes.isEmpty()) {
            val episodeCards = doc.select("a[href*='/bolum-']")
            if (episodeCards.isNotEmpty()) {
                episodes = episodeCards.mapNotNull { a ->
                    val epHref = a.attr("href")
                    val m = Regex("""/dizi/[^/]+/sezon-(\d+)/bolum-(\d+)""").find(epHref) ?: return@mapNotNull null
                    val sNum = m.groupValues[1].toIntOrNull() ?: 1
                    val eNum = m.groupValues[2].toIntOrNull() ?: 1
                    val epThumb = a.selectFirst("img")?.let { extractPosterUrl(it) } ?: poster
                    val epTitle = a.selectFirst("h3, p.text-zinc-500")?.text()?.trim()
                        ?: a.attr("title").takeIf { it.isNotBlank() }
                        ?: "$eNum. Bölüm"

                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.season = sNum
                        this.episode = eNum
                        this.posterUrl = epThumb
                    }
                }.distinctBy { "${it.season}-${it.episode}" }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
            }
        }

        if (episodes.isEmpty()) {
            val matches = Regex("""href=["'](/dizi/[^/]+/sezon-(\d+)/bolum-(\d+))["']""").findAll(html).toList()
            episodes = matches.map { m ->
                val epUrl = fixUrl(m.groupValues[1])
                val sNum = m.groupValues[2].toIntOrNull() ?: 1
                val eNum = m.groupValues[3].toIntOrNull() ?: 1
                newEpisode(epUrl) {
                    this.name = "$sNum. Sezon $eNum. Bölüm"
                    this.season = sNum
                    this.episode = eNum
                    this.posterUrl = poster
                }
            }.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            if (score != null) this.score = Score.from10(score)
        }
    }

    // ── Load Links ──────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        if (data.contains("/embed/") || data.contains("vidmixi.com")) {
            return extractDirectEmbed(data, "$mainUrl/", subtitleCallback, callback)
        }

        val html = try {
            app.get(data, headers = defaultHeaders).text
        } catch (_: Exception) {
            return false
        }
        val rsc = parseRscPayload(html)

        val parts = parsePartsFromRsc(rsc)
        for (part in parts) {
            found = extractDirectEmbed(part, data, subtitleCallback, callback) || found
        }

        val embed1 = Regex(""""embed_player_url_1"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(rsc)?.groupValues?.get(1)?.replace("\\/", "/")
        val embed2 = Regex(""""embed_player_url_2"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(rsc)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!embed1.isNullOrBlank() && embed1.startsWith("http")) {
            found = extractDirectEmbed(embed1, data, subtitleCallback, callback) || found
        }
        if (!embed2.isNullOrBlank() && embed2.startsWith("http")) {
            found = extractDirectEmbed(embed2, data, subtitleCallback, callback) || found
        }

        if (parts.isEmpty() && embed1.isNullOrBlank() && embed2.isNullOrBlank()) {
            val iframes = Regex("""(?:src|data-src)=["'](https?://[^"']*(?:vidmixi|vidlop|embed|video)[^"']*)["']""")
                .findAll(html)
            for (m in iframes) {
                val u = m.groupValues[1].replace("\\/", "/")
                found = extractDirectEmbed(u, data, subtitleCallback, callback) || found
            }
        }

        return found
    }

    /**
     * RSC payload'dan "parts" dizisindeki embed URL'lerini çıkarır.
     */
    private fun parsePartsFromRsc(rsc: String): List<String> {
        val parts = mutableListOf<String>()
        val regex = Regex(""""parts"\s*:\s*(\[[^\]]*\])""")
        for (m in regex.findAll(rsc)) {
            try {
                val arr = JSONArray(m.groupValues[1])
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url").replace("\\/", "/")
                    if (url.isNotBlank() && url.startsWith("http") && !parts.contains(url)) {
                        parts.add(url)
                    }
                }
            } catch (_: Exception) {}
        }
        return parts
    }

    // ── Extractors ──────────────────────────────────────────────────────

    private suspend fun extractDirectEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            embedUrl.contains("vidmixi.com") -> extractVidmixi(embedUrl, referer, subtitleCallback, callback)
            embedUrl.contains("vidlop.com") -> extractVidlop(embedUrl, referer, subtitleCallback, callback)
            else -> try {
                loadExtractor(embedUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Vidmixi embed sayfasını açar. Çoklu senaryo destekli:
     *   1. Klasik bePlayer('PASSPHRASE', '{...}')
     *   2. data-config / data-player attribute'unda JSON
     *   3. Base64 ile gizlenmiş JSON
     *   4. Doğrudan m3u8 URL'i
     *   5. loadExtractor fallback
     */
    private suspend fun extractVidmixi(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val origin = getOrigin(embedUrl)
            val html = app.get(embedUrl, headers = mapOf(
                "User-Agent" to defaultHeaders["User-Agent"]!!,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )).text

            // ── Senaryo 1: Klasik bePlayer ──
            val bePlayerMatch = Regex("""bePlayer\(\s*['"]([^'"]+)['"]\s*,\s*['"]([\s\S]*?)['"]\s*\)""").find(html)
            if (bePlayerMatch != null) {
                val ok = decryptAndExtract(
                    bePlayerMatch.groupValues[1],
                    bePlayerMatch.groupValues[2],
                    origin, subtitleCallback, callback
                )
                if (ok) return true
            }

            // ── Senaryo 2: data-config / data-player attribute ──
            val dataConfigMatch = Regex("""data-(?:config|player|source)=['"]([^'"]+)['"]""").find(html)
            if (dataConfigMatch != null) {
                val jsonStr = dataConfigMatch.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                val obj = try { JSONObject(jsonStr) } catch (_: Exception) { null }
                if (obj != null && obj.has("ct") && obj.has("s")) {
                    val passphrase = obj.optString("passphrase").takeIf { it.isNotBlank() } ?: ""
                    val ok = decryptAndExtract(passphrase, jsonStr, origin, subtitleCallback, callback)
                    if (ok) return true
                }
            }

            // ── Senaryo 3: Base64 gizli JSON ──
            val encodedMatch = Regex("""(?:playerData|source|config)\s*[=:]\s*['"]([A-Za-z0-9+/=]{40,})['"]""").find(html)
            if (encodedMatch != null) {
                val decoded = try {
                    String(Base64.decode(encodedMatch.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) { null }
                if (decoded != null && decoded.contains("\"ct\"")) {
                    val obj = try { JSONObject(decoded) } catch (_: Exception) { null }
                    if (obj != null) {
                        val passphrase = obj.optString("passphrase").takeIf { it.isNotBlank() } ?: ""
                        val ok = decryptAndExtract(passphrase, decoded, origin, subtitleCallback, callback)
                        if (ok) return true
                    }
                }
            }

            // ── Senaryo 4: Doğrudan m3u8 ──
            val directM3u8 = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").find(html)
            if (directM3u8 != null) {
                callback(
                    ExtractorLink(
                        source = "Vidmixi",
                        name = "DiziFilm (Vidmixi Direct)",
                        url = directM3u8.groupValues[1].replace("\\/", "/"),
                        referer = "$origin/",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Referer" to "$origin/",
                            "Origin" to origin,
                            "User-Agent" to defaultHeaders["User-Agent"]!!
                        )
                    )
                )
                return true
            }

            // ── Senaryo 5: loadExtractor fallback ──
            return try {
                loadExtractor(embedUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * AES-CBC decrypt edip m3u8 URL'ini ve altyazıları çıkarır.
     */
    private fun decryptAndExtract(
        passphrase: String,
        setJson: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val decrypted = decryptBePlayer(passphrase, setJson) ?: return false
            val settings = JSONObject(decrypted)
            val streamUrl = settings.optString("video_location").replace("\\/", "/")
            if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return false

            val subsArray = settings.optJSONArray("strSubtitles")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val subObj = subsArray.optJSONObject(i) ?: continue
                    var subFile = subObj.optString("file").replace("\\/", "/")
                    if (subFile.isBlank()) continue
                    if (subFile.startsWith("/")) subFile = "$origin$subFile"
                    val subLabel = subObj.optString("label").takeIf { it.isNotBlank() }
                        ?: subObj.optString("language").takeIf { it.isNotBlank() } ?: "Türkçe"
                    subtitleCallback(SubtitleFile(lang = unescapeUnicode(subLabel), url = subFile))
                }
            }

            callback(
                ExtractorLink(
                    source = "Vidmixi",
                    name = "DiziFilm (Vidmixi HLS)",
                    url = streamUrl,
                    referer = "$origin/",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "Referer" to "$origin/",
                        "Origin" to origin,
                        "User-Agent" to defaultHeaders["User-Agent"]!!
                    )
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractVidlop(
        videoUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val videoId = Regex("""vidlop\.com/video/([^/?#]+)""").find(videoUrl)?.groupValues?.get(1) ?: return false
            val vidlopOrigin = "https://vidlop.com"
            val pageUrl = "$vidlopOrigin/video/$videoId"

            val jsonStr = app.post(
                "$vidlopOrigin/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to referer),
                headers = mapOf(
                    "User-Agent" to defaultHeaders["User-Agent"]!!,
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            val json = JSONObject(jsonStr)
            val streamUrl = json.optString("securedLink").takeIf { it.isNotBlank() }
                ?: json.optString("videoSource").takeIf { it.isNotBlank() } ?: return false

            callback(
                ExtractorLink(
                    source = "Vidlop",
                    name = "DiziFilm (Vidlop HLS)",
                    url = streamUrl.replace("\\/", "/"),
                    referer = pageUrl,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "Referer" to pageUrl,
                        "Origin" to vidlopOrigin,
                        "User-Agent" to defaultHeaders["User-Agent"]!!
                    )
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parseRscPayload(html: String): String {
        val sb = StringBuilder()
        val regex = Regex("""self\.__next_f\.push\(\[1,"((?:\\.|[^"\\])*)"\]\)""")
        for (m in regex.findAll(html)) {
            var chunk = m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\\\", "\\")
            chunk = Regex("""\\u([0-9a-fA-F]{4})""").replace(chunk) { match ->
                try {
                    match.groupValues[1].toInt(16).toChar().toString()
                } catch (_: Exception) {
                    match.value
                }
            }
            sb.append(chunk)
        }
        return sb.toString()
    }

    private fun parseSeasonsWithEpisodes(rsc: String, seriesSlug: String): List<Episode> {
        val marker = "\"seasonsWithEpisodes\":"
        val idx = rsc.indexOf(marker)
        if (idx == -1) return emptyList()

        val start = idx + marker.length
        var depth = 0
        var end = -1
        var inString = false
        var escape = false

        for (i in start until rsc.length) {
            val c = rsc[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (!inString) {
                when (c) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { end = i + 1; break }
                    }
                }
            }
        }

        if (end == -1) return emptyList()

        val episodes = mutableListOf<Episode>()
        try {
            val seasonsArr = JSONArray(rsc.substring(start, end))
            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(s) ?: continue
                val seasonNum = seasonObj.optInt("season_number", s + 1)
                val epArr = seasonObj.optJSONArray("episodes") ?: continue
                for (e in 0 until epArr.length()) {
                    val epObj = epArr.optJSONObject(e) ?: continue
                    val epNum = epObj.optInt("episode_number", e + 1)
                    val rawTitle = epObj.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: epObj.optString("title_tr").takeIf { it.isNotBlank() && it != "null" }
                        ?: epObj.optString("title_en").takeIf { it.isNotBlank() && it != "null" }
                    val rawOverview = epObj.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                        ?: epObj.optString("overview_tr").takeIf { it.isNotBlank() && it != "null" }
                    val rawThumb = epObj.optString("thumbnail_url").takeIf { it.isNotBlank() && it != "null" }
                        ?: epObj.optString("still_path").takeIf { it.isNotBlank() && it != "null" }

                    val epName = if (!rawTitle.isNullOrBlank()) unescapeUnicode(rawTitle) else "$epNum. Bölüm"
                    val epDesc = if (!rawOverview.isNullOrBlank()) unescapeUnicode(rawOverview) else null
                    val epThumb = rawThumb?.let { fixPosterUrl(unescapeUnicode(it)) }
                    val epUrl = "$mainUrl/dizi/$seriesSlug/sezon-$seasonNum/bolum-$epNum"

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epThumb
                        this.description = epDesc
                    })
                }
            }
        } catch (_: Exception) {}
        return episodes
    }

    private fun extractPlot(doc: Document, rsc: String): String? {
        for (script in doc.select("script[type='application/ld+json']")) {
            try {
                val data = JSONObject(script.data())
                val type = data.optString("@type")
                if (type == "TVSeries" || type == "Movie" || type == "VideoObject") {
                    val desc = data.optString("description")
                    if (desc.isNotBlank() && !desc.contains("tüm sezonları ve bölümleri") && !desc.contains("olarak Full HD izleyebilirsiniz")) {
                        return unescapeUnicode(desc.trim())
                    }
                }
            } catch (_: Exception) {}
        }

        val domPlot = doc.selectFirst("div.prose, div.text-gray-300.text-sm.prose, p.text-gray-300")?.text()?.trim()
        if (!domPlot.isNullOrBlank() && !domPlot.contains("olarak Full HD izleyebilirsiniz")) {
            return domPlot
        }

        val rscPlot = Regex(""""description"\s*:\s*"((?:\\.|[^"\\]){20,})"""").find(rsc)?.groupValues?.get(1)
        if (!rscPlot.isNullOrBlank() && !rscPlot.contains("olarak Full HD izleyebilirsiniz")) {
            return unescapeUnicode(rscPlot)
        }

        return doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var fixed = url.replace("\\/", "/").trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        fixed = fixed.replace(Regex("""\.avif(\?.*)?$""", RegexOption.IGNORE_CASE), ".jpg")
        fixed = fixed.replace(Regex("""-w\d+\.(jpg|jpeg|png|webp|avif)""", RegexOption.IGNORE_CASE), ".$1")
        return fixUrl(fixed)
    }

    private fun extractPosterUrl(img: Element): String? {
        val src = img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val srcSet = img.attr("srcSet").takeIf { it.isNotBlank() }
            ?: img.attr("srcset").takeIf { it.isNotBlank() }
        val fromSrcSet = srcSet?.split(",")
            ?.map { it.trim().split(" ").firstOrNull() }
            ?.filter { !it.isNullOrBlank() }
            ?.lastOrNull()
        val dataSrc = img.attr("data-src").takeIf { it.isNotBlank() }
        val dataSrcSet = img.attr("data-srcset").takeIf { it.isNotBlank() }
        val fromDataSrcSet = dataSrcSet?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()

        val raw = src ?: fromSrcSet ?: dataSrc ?: fromDataSrcSet ?: return null
        return fixPosterUrl(raw)
    }

    private fun unescapeUnicode(input: String): String {
        var str = input
        str = Regex("""(?:\\+u|%u)([0-9a-fA-F]{4})""").replace(str) { match ->
            try {
                match.groupValues[1].toInt(16).toChar().toString()
            } catch (_: Exception) {
                match.value
            }
        }
        return str.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .trim()
    }

    private fun getOrigin(url: String): String {
        val match = Regex("""^(https?://[^/]+)""").find(url)
        return match?.groupValues?.get(1) ?: "https://vidmixi.com"
    }

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun evpBytesToKey(pass: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val target = keyLen + ivLen
        var derived = ByteArray(0)
        var prev = ByteArray(0)
        while (derived.size < target) {
            prev = md5(prev + pass + salt)
            derived += prev
        }
        return Pair(
            derived.copyOfRange(0, keyLen),
            derived.copyOfRange(keyLen, keyLen + ivLen)
        )
    }

    private fun decryptBePlayer(passphrase: String, setJson: String): String? {
        return try {
            val obj = JSONObject(setJson)
            val ctB64 = obj.getString("ct").replace("\\/", "/").replace("\\", "")
            val saltHex = obj.getString("s")
            val ct = Base64.decode(ctB64, Base64.DEFAULT)
            val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val (key, iv) = evpBytesToKey(passBytes, salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ct)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
