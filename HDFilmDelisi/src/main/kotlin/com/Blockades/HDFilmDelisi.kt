package com.Blockades

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

// ===== Common.kt içerikleri (tek dosyaya gömüldü) =====

val browserHeaders: Map<String, String> =
    java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        put("accept-language", "tr-TR,tr;q=0.9")
    }

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME="(\d{3,4})p?"""", RegexOption.IGNORE_CASE)

fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
    if (!m3u8Text.contains("#EXT-X-STREAM-INF") ||
        m3u8Text.contains("TYPE=AUDIO", ignoreCase = true) ||
        m3u8Text.contains("AUDIO=", ignoreCase = true)
    ) return emptyList()
    val base = runCatching { URI(masterUrl.trim()) }.getOrNull() ?: return emptyList()
    val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val out = ArrayList<Pair<Int, String>>()
    val seen = HashSet<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val height = HLS_RES.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: HLS_NAME.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            var j = i + 1
            while (j < lines.size && lines[j].startsWith("#")) j++
            if (j < lines.size) {
                val target = runCatching {
                    val raw = lines[j]
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else base.resolve(raw).toString()
                }.getOrNull()
                if (!target.isNullOrBlank() && height > 0 && seen.add("$height|$target")) {
                    out.add(height to target)
                }
                i = j
            }
        }
        i++
    }
    return out.sortedByDescending { it.first }
}

fun Document.jsonLdDescription(): String? {
    val nodes = select("script[type=application/ld+json]").flatMap { element ->
        runCatching {
            val raw = element.data().trim()
            if (raw.startsWith("[")) {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } else {
                listOf(JSONObject(raw))
            }
        }.getOrDefault(emptyList())
    }.flatMap { it.ldNodes() }
    nodes.firstOrNull { it.optString("name").contains("konusu nedir", ignoreCase = true) }
        ?.optJSONObject("acceptedAnswer")?.optString("text")
        ?.takeIf { it.isNotBlank() }?.let { return cleanJsonLd(it) }
    for (type in listOf("TVSeries", "Movie", "VideoObject", "CreativeWork", "Article")) {
        nodes.firstOrNull {
            it.optString("@type").contains(type, ignoreCase = true) && it.optString("description").isNotBlank()
        }?.let { return cleanJsonLd(it.optString("description")) }
    }
    return nodes.firstOrNull { it.optString("description").isNotBlank() }
        ?.let { cleanJsonLd(it.optString("description")) }
}

private fun JSONObject.ldNodes(): List<JSONObject> = buildList {
    add(this@ldNodes)
    for (key in listOf("@graph", "mainEntity")) {
        when (val child = opt(key)) {
            is JSONObject -> addAll(child.ldNodes())
            is JSONArray -> for (i in 0 until child.length()) {
                child.optJSONObject(i)?.let { addAll(it.ldNodes()) }
            }
        }
    }
}

private fun cleanJsonLd(raw: String): String = Jsoup.parse(raw).text().trim()

// ===== Provider =====

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class HDFilmDelisi : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://hdfilmdelisi1.com"
    override var name = "HDFilmDelisi"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        val slug = CATEGORY_SLUGS[rowName] ?: return@mapNotNull null
        MainPageData(rowName, "$mainUrl/api/films/category/$slug?page={page}")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name !in ROWS) return newHomePageResponse(request.name, emptyList(), false)
        val url = request.data.replace("{page}", page.toString())
        val raw = app.get(url, headers = uaHeaders).text
        val arr = JSONObject(raw).optJSONArray("films") ?: JSONArray()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("baslik").trim()
            val slug = o.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val href = if (slug.startsWith("http")) slug else "$mainUrl/film/" + slug.trimStart('/')
            out += newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(o.optString("afis").takeIf { it.startsWith("http") })
                year = o.optInt("yayinYili").takeIf { it > 1900 }
                score = Score.from10(o.optString("imdbPuani"))
            }
        }
        return newHomePageResponse(request.name, out, out.isNotEmpty() && page < 200)
    }

    companion object {
        private val CATEGORY_SLUGS = linkedMapOf(
            "Aile" to "aile",
            "Aksiyon" to "aksiyon",
            "Animasyon" to "animasyon",
            "Belgeseller" to "belgeseller",
            "Bilim Kurgu" to "bilim-kurgu",
            "Dram" to "dram",
            "Fantastik" to "fantastik",
            "Gerilim" to "gerilim",
            "Gizem" to "gizem",
            "Hint" to "hint",
            "Komedi" to "komedi",
            "Korku" to "korku",
            "Macera" to "macera",
            "Müzikal" to "muzikal",
            "Psikolojik" to "psikolojik",
            "Romantik" to "romantik",
            "Savaş" to "savas",
            "Suç" to "suc",
            "Tarih" to "tarih",
            "Western" to "western",
            "Yerli" to "yerli",
        )
        val ROWS = CATEGORY_SLUGS.keys.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val raw = app.get(url, headers = uaHeaders).text
        val root = if (raw.trimStart().startsWith("[")) JSONObject() else JSONObject(raw)
        val out = mutableListOf<SearchResponse>()
        val arr = root.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("baslik").trim()
            val slug = o.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val href = if (slug.startsWith("http")) slug else "$mainUrl/film/" + slug.trimStart('/')
            out += newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(o.optString("afis").takeIf { it.startsWith("http") })
                year = o.optInt("yayinYili").takeIf { it > 1900 }
                score = Score.from10(o.optString("imdbPuani"))
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: throw ErrorLoadingException("$name: başlık okunamadı")
        ).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val duration = Regex(""""duration"\s*:\s*"PT(?:(\d+)H)?(?:(\d+)M)?""").find(raw)?.let { match ->
            (match.groupValues[1].toIntOrNull() ?: 0) * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
        }?.takeIf { it > 0 }
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val basePath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val slug = basePath.trim('/').split('/').lastOrNull().orEmpty()
        val slugBase = slug.removeSuffix("-izle").removeSuffix("-film-izle")
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!Regex("""(?:bolum|episode|sezon|season)""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return@mapNotNull null
            val number = Regex("""(?:sezon|season)[-_/]?(\d+).*?(?:bolum|episode)[-_/]?([0-9]*)""")
                .find(path)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[-_/]?(\d+)""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(\d+)[-_]bolum""").find(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
                this.episode = number
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.duration = duration
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
        }
    }

    // pl.vixolity.com oynatıcısı: hex -> ters -> base64 -> karakter kaydırma -> base64 ile gizlenmiş JS; "kaynak" HLS master'dır.
    private suspend fun vixolityMaster(embed: String): String? {
        val html = runCatching { app.get(embed, headers = uaHeaders, referer = "$mainUrl/").text }.getOrNull() ?: return null
        val m = Regex("""\}\)\('([0-9a-f]+)',\s*(\d+)\)""").find(html) ?: return null
        return runCatching {
            val rev = String(m.groupValues[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.ISO_8859_1).reversed()
            val shifted = String(java.util.Base64.getDecoder().decode(rev), Charsets.ISO_8859_1)
                .map { (it.code - m.groupValues[2].toInt()).toChar() }.joinToString("")
            val code = String(java.util.Base64.getDecoder().decode(shifted), Charsets.UTF_8)
            Regex(""""kaynak"\s*:\s*"([^"]+)"""").find(code)?.groupValues?.get(1)?.replace("\\/", "/")
        }.getOrNull()
    }

    /**
     * Vixolity altyazıyı HLS olarak sarar: `sub_tr.gif` → `#EXTM3U` → tek segment (`sub_tr.jpg`,
     * içeriği `WEBVTT`). Oynatıcı playlist'i altyazı olarak parse edemediği için segmenti ver.
     */
    private suspend fun vixolitySubtitleFile(url: String, referer: String): SubtitleFile? {
        if (!url.contains("/vtt/")) return SubtitleFile("tr", url)
        val body = runCatching { app.get(url, headers = uaHeaders, referer = referer).text }.getOrNull() ?: return null
        if (!body.startsWith("#EXTM3U")) return SubtitleFile("tr", url).takeIf { body.contains("WEBVTT") }
        val segments = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        // Çok segmentli altyazı tek dosyaya birleştirilemez (barındırma yok); eksik altyazı verme.
        if (segments.size != 1) return null
        val segment = runCatching { URI(url).resolve(segments[0]).toString() }.getOrNull() ?: return null
        return SubtitleFile("tr", segment)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val resp = app.get(data, headers = uaHeaders)
        val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8") || stream.contains("/vs/") || stream.endsWith(".gif")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: getQualityFromName(label)
            callback.invoke(newExtractorLink(name, label, stream, type) {
                this.referer = referer
                this.quality = quality
            })
            found = true
        }
        val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
        for (m in media) emit(m.value, "Direct", data)
        val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
        for (embed in embeds) {
            val absolute = fixUrl(embed)
            if (absolute == data) continue
            if (absolute.contains("vixolity.com")) {
                vixolityMaster(absolute)?.let { m ->
                    val vixRef = "https://pl.vixolity.com/"
                    emit(m, "Vixolity", vixRef, Qualities.Unknown.value)
                    val playlist = runCatching { app.get(m, headers = uaHeaders, referer = vixRef).text }.getOrNull().orEmpty()
                    for ((h, vUrl) in parseHlsVariants(m, playlist)) {
                        emit(vUrl, "Vixolity ${h}p", vixRef, h)
                    }
                    for (sub in Regex("""#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*""").findAll(playlist)) {
                        val uri = Regex("""URI="([^"]+)"""").find(sub.value)?.groupValues?.get(1) ?: continue
                        val langCode = Regex("""LANGUAGE="([^"]+)"""").find(sub.value)?.groupValues?.get(1)?.lowercase() ?: "tr"
                        val subName = Regex("""NAME="([^"]+)"""").find(sub.value)?.groupValues?.get(1)?.trim()
                            ?.takeIf { it.isNotEmpty() } ?: langCode
                        val subUrl = runCatching { URI(m).resolve(uri).toString() }.getOrDefault(uri)
                        vixolitySubtitleFile(subUrl, vixRef)?.let { subtitleCallback.invoke(SubtitleFile(subName, it.url)) }
                    }
                }
                continue
            }
            if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
            if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                emit(absolute, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = data).text }.getOrNull() ?: continue
            val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
            for (m in innerMedia) emit(m.value, "Embed", absolute)
        }
        val frames = resp.document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
            fixUrlNull(raw)
        }.distinct()
        for (frame in frames) {
            if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                emit(frame, "Embed", data)
                continue
            }
            val inner = runCatching { app.get(frame, headers = uaHeaders, referer = data).text }.getOrNull()
            if (inner != null) {
                val innerText = inner.replace("\\/", "/")
                for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                    emit(m.value, "Embed", frame)
                }
                for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                    val subUrl = fixUrl(sub.groupValues[1])
                    if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                }
            }
            if (loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
        }
        val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
        for (m in subs) {
            subtitleCallback.invoke(SubtitleFile("tr", m.value))
        }
        if (!found && frames.isEmpty() && embeds.isEmpty() && media.none()) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }
}
