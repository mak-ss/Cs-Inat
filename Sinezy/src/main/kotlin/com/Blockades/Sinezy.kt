package com.sinezy

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Sinezy — sinezy.si film arşivi.
 *
 * Arama `/arama/?s=<sorgu>`; detay sayfası ilk oynatıcı iframe'ini base64
 * `ilkpartkod` içinde taşır. Iframe `player.filmizle.in` FirePlayer'dır:
 * `POST /player/index.php?data=<id>&do=getVideo` → `securedLink` HLS master.
 */
class SinezyProvider : MainAPI() {
    override var mainUrl = "https://sinezy.si"
    override var name = "Sinezy"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val headers get() = BROWSER_HEADERS

    override val mainPage get() = listOf(
        MainPageData("Yeni Filmler", "$mainUrl/izle/en-yeni-filmler/"),
        MainPageData("Bilim Kurgu", "$mainUrl/izle/bilim-kurgu-filmleri/"),
        MainPageData("Animasyon", "$mainUrl/izle/animasyon-filmleri/"),
        MainPageData("Macera", "$mainUrl/izle/macera-filmleri/"),
        MainPageData("Suç", "$mainUrl/izle/suc-filmleri/"),
        MainPageData("Film Arşivi", "$mainUrl/film-arsivi/"),
    ).filter { Settings.rowEnabled(it.name) }

    private fun Element.toCard(): SearchResponse? {
        val anchor = selectFirst("a.image") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: anchor.attr("title").removeSuffix(" izle").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val poster = fixUrlNull(selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val doc = app.get(request.data, headers = headers).document
        return newHomePageResponse(request.name, doc.select(".movie_box").mapNotNull { it.toCard() }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/?s=${URLEncoder.encode(query.trim(), "UTF-8")}", headers = headers).document
        return doc.select(".movie_box").mapNotNull { it.toCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = app.get(pageUrl, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı. $REPORT_HINT")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.jsonLdDescription()
            ?: doc.selectFirst("div.desc, div.yeniscroll")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = fixUrl(data)
        val page = app.get(pageUrl, headers = headers).text
        val encoded = Regex("""var\s+ilkpartkod\s*=\s*'([^']+)'""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı verisi bulunamadı. $REPORT_HINT")
        val iframeHtml = runCatching {
            Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull() ?: throw ErrorLoadingException("$name: oynatıcı verisi çözülemedi. $REPORT_HINT")
        val rawPlayerUrl = fixUrlNull(Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1))
            ?: throw ErrorLoadingException("$name: oynatıcı adresi bulunamadı. $REPORT_HINT")
        val playerOrigin = rawPlayerUrl.substringBefore("/video/").lowercase()
        val playerUrl = "$playerOrigin/video/${rawPlayerUrl.substringAfter("/video/")}"

        val playerResp = app.get(playerUrl, headers = headers, referer = pageUrl)
        val playerHtml = playerResp.text
        val id = playerUrl.substringAfterLast('/').substringBefore('?').trim()
        if (id.isEmpty()) throw ErrorLoadingException("$name: oynatıcı kimliği bulunamadı. $REPORT_HINT")
        val api = "$playerOrigin/player/index.php?data=$id&do=getVideo"
        val response = app.post(
            api,
            data = mapOf("hash" to id, "r" to "$mainUrl/"),
            headers = headers + mapOf(
                "Referer" to playerUrl,
                "Origin" to playerOrigin,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = playerResp.cookies,
        ).text
        val body = runCatching { JSONObject(response) }.getOrNull()
            ?: throw ErrorLoadingException("$name: oynatıcı yanıtı okunamadı. $REPORT_HINT")

        var found = false
        for (key in listOf("securedLink", "videoSource")) {
            val stream = body.optString(key).trim().replace("\\/", "/")
            if (!stream.startsWith("http")) continue
            val m3u8Text = runCatching {
                app.get(stream, headers = headers, referer = playerUrl).text
            }.getOrNull().orEmpty()
            if (!m3u8Text.trimStart().startsWith("#EXTM3U")) continue
            if (Settings.qualityAllowed(Qualities.Unknown.value)) {
                callback.invoke(
                    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                        this.referer = playerUrl
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    },
                )
            }
            for ((h, vUrl) in parseHlsVariants(stream, m3u8Text)) {
                if (!Settings.qualityAllowed(h)) continue
                callback.invoke(
                    newExtractorLink(name, "$name • ${h}p", vUrl, ExtractorLinkType.M3U8) {
                        this.referer = playerUrl
                        this.headers = headers
                        this.quality = h
                    },
                )
            }
            found = true
            break
        }

        if (Settings.subtitleLang != "off") {
            Regex("""playerjsSubtitle\s*=\s*"\[([^\]]+)\]([^"]+)""")
                .find(playerHtml)?.let { match ->
                    val language = match.groupValues[1]
                    val subtitle = match.groupValues[2].replace("\\/", "/")
                    if (Settings.subtitleAllowed(language)) {
                        subtitleCallback.invoke(newSubtitleFile(language, subtitle))
                    }
                }
        }

        if (!found) throw ErrorLoadingException("$name: oynatılabilir akış yok. $REPORT_HINT")
        return true
    }

    companion object {
        val ROWS = listOf("Yeni Filmler", "Bilim Kurgu", "Animasyon", "Macera", "Suç", "Film Arşivi")
        private const val REPORT_HINT =
            "Sorun sürüyorsa bildir: https://github.com/F0Rextasy/bigbullapp-cs/issues"
    }
}

// ---------------------------------------------------------------------------
// Inlined helpers (previously from com.bigbull.common / Common.kt)
// ---------------------------------------------------------------------------

/**
 * Tüm modüllerin kopyaladığı tarayıcı kimliği; UA rulajı tek yerden yapılır.
 * NiceHttp app.defaultHeaders küçük harfli "user-agent" kullandığı için anahtarlar küçük harflidir.
 */
private val BROWSER_HEADERS: Map<String, String> =
    java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        put(
            "user-agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        )
        put("accept-language", "tr-TR,tr;q=0.9")
    }

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME="(\d{3,4})p?"""", RegexOption.IGNORE_CASE)

/**
 * HLS master playlist içindeki #EXT-X-STREAM-INF kalite varyantlarını
 * (yükseklik px, tam URL) olarak çıkarır.
 */
private fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
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

/**
 * Sayfadaki JSON-LD `description` alanını okur; FAQ şemasında "... konusu nedir?" cevabını tercih eder.
 * Meta açıklamaları SEO cümlesi olduğu için içerik özeti gereken yerlerde bu yardımcı kullanılır.
 */
private fun Document.jsonLdDescription(): String? {
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
