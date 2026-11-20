package com.Blockades

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOrijinal : MainAPI() {
    override var mainUrl = "https://dizipal2150.com"
    override var name = "DiziPalOrijinal"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val uaHeaders get() = mapOf("user-agent" to UA, "Accept-Language" to "tr-TR,tr;q=0.9")

    private val pagedSections = setOf(
        "$mainUrl/yabanci-dizi-izle", "$mainUrl/film-izle",
        "$mainUrl/platform/blutv", "$mainUrl/platform/exxen", "$mainUrl/platform/gain",
        "$mainUrl/platform/max", "$mainUrl/platform/paramount-plus", "$mainUrl/platform/tabii",
    )

    override val mainPage get() = mainPageOf(
        "$mainUrl/diziler" to "Yeni Diziler", "$mainUrl/filmler" to "Yeni Filmler",
        "$mainUrl/trend" to "Trendler", "$mainUrl/anime" to "Animeler",
        "$mainUrl/platform/netflix" to "Netflix", "$mainUrl/platform/prime-video" to "Prime Video",
        "$mainUrl/platform/disney-plus" to "Disney+", "$mainUrl/platform/blutv" to "BluTV",
        "$mainUrl/platform/exxen" to "Exxen", "$mainUrl/platform/gain" to "Gain",
        "$mainUrl/platform/max" to "Max", "$mainUrl/platform/paramount-plus" to "Paramount+",
        "$mainUrl/platform/tabii" to "tabii",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = request.data in pagedSections
        val url = if (paged && page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = uaHeaders).document
        val items = doc.select("a.card-link[href], a.tc[href]").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = paged && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val body = runCatching {
            app.post("$mainUrl/api/mj",
                json = JSONObject().put("action", "searchContent").put("params", JSONArray().put(q)),
                headers = uaHeaders + mapOf("Origin" to mainUrl), referer = "$mainUrl/").text
        }.getOrNull() ?: throw ErrorLoadingException("$name: arama isteği başarısız.")
        val rows = runCatching { JSONObject(body).optJSONArray("result") }.getOrNull()
            ?: throw ErrorLoadingException("$name: arama yanıtı okunamadı.")
        return rows.objects().mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = httpsUrl(url)
        val doc = runCatching { app.get(pageUrl, headers = uaHeaders).document }.getOrNull()
            ?: throw ErrorLoadingException("$name: içerik sayfası yüklenemedi.")
        val isSeries = pageUrl.contains("/dizi/") || pageUrl.contains("/series/")
        val ld = jsonLd(doc, if (isSeries) "TVSeries" else "Movie")
        val title = ld?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı.")

        if (isSeries) {
            val episodes = episodesOf(doc, ld)
            if (episodes.isEmpty()) throw ErrorLoadingException("$name: bölüm listesi bulunamadı.")
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterOf(doc, ld)
                this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
                this.year = yearOf(ld)
                this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
                this.duration = minutes(ld?.optString("timeRequired"))
                this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
                this.recommendations = recommendationsOf(doc, isSeries)
                actorsOf(ld)?.let { addActors(it) }
            }
        }
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            this.posterUrl = posterOf(doc, ld)
            this.plot = ld?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            this.year = yearOf(ld)
            this.score = Score.from10(ld?.optJSONObject("aggregateRating")?.optString("ratingValue"))
            this.duration = minutes(ld?.optString("timeRequired"))
            this.tags = tagsOf(doc).takeIf { it.isNotEmpty() }
            this.recommendations = recommendationsOf(doc, isSeries)
            actorsOf(ld)?.let { addActors(it) }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = httpsUrl(data)
        val pageHtml = runCatching { app.get(pageUrl, headers = uaHeaders, referer = "$mainUrl/").text }
            .getOrNull() ?: throw ErrorLoadingException("$name: bölüm sayfası yüklenemedi.")

        val cfg = Regex("""data-cfg="([a-f0-9]{32})"""").find(pageHtml)?.groupValues?.get(1)
        println("DiziPal DEBUG: data-cfg = $cfg")

        if (cfg != null) {
            val config = fetchPlayerConfig(cfg, pageUrl)
            println("DiziPal DEBUG: config obj = $config")
            if (config != null) {
                println("DiziPal DEBUG: v = ${config.optString("v")}, t = ${config.optString("t")}")
                if (playConfig(config, pageUrl, callback, subtitleCallback)) return true
            }
        }

        val playerUrl = decryptEmbed(pageHtml)
        println("DiziPal DEBUG: enc playerUrl = $playerUrl")
        if (playerUrl != null) {
            val playerHtml = runCatching { app.get(playerUrl, headers = uaHeaders, referer = pageUrl).text }.getOrNull()
            if (playerHtml != null) {
                return npLinks(playerHtml, playerUrl, callback, subtitleCallback) ||
                    dplayerLinks(playerHtml, playerUrl, callback)
            }
        }
        throw ErrorLoadingException("$name: oynatılabilir kaynak bulunamadı.")
    }

    private suspend fun fetchPlayerConfig(cfg: String, pageUrl: String): JSONObject? {
        val body = runCatching {
            app.post("$mainUrl/ajax-player-config",
                headers = uaHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded"),
                referer = pageUrl, data = mapOf("cfg" to cfg)).text
        }.getOrNull()
        println("DiziPal DEBUG: ajax-player-config raw response = $body") // Kritik log
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optBoolean("success")) return null
        return root.optJSONObject("config")
    }

    private suspend fun playConfig(config: JSONObject, pageUrl: String, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit): Boolean {
        val video = config.optString("v").trim()
        if (video.isEmpty()) return false
        val type = config.optString("t").trim().ifEmpty { "iframe" }
        when (type.lowercase()) {
            "m3u8", "mp4" -> {
                val kind = if (type.equals("mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                callback(newExtractorLink(name, "DiziPal", video, kind) {
                    this.referer = pageUrl; this.quality = Qualities.Unknown.value; this.headers = uaHeaders
                })
                return true
            }
            "iframe" -> {
                val playerUrl = if (video.contains("<iframe")) {
                    Regex("""<iframe[^>]+src=["']([^"']+)"""").find(video)?.groupValues?.get(1)?.let { httpsUrl(it) }
                } else httpsUrl(video)
                if (playerUrl.isNullOrEmpty() || !playerUrl.startsWith("http")) return false
                val playerHtml = runCatching { app.get(playerUrl, headers = uaHeaders, referer = pageUrl).text }.getOrNull() ?: return false
                return npLinks(playerHtml, playerUrl, callback, subtitleCallback) || dplayerLinks(playerHtml, playerUrl, callback)
            }
        }
        return false
    }

    // ... (toCard, toSearchResult, directImage, episodesOf, jsonLd, posterOf, yearOf, tagsOf, recommendationsOf, actorsOf, minutes, decryptEmbed, npLinks, dplayerLinks, emitPlaylist, parseVariants, hlsLink, objectAt, httpsUrl, seasons, objects, hexToBytes, aesCbcDecrypt, pbkdf2AesCbcDecrypt, pichiveGateNonce, pichiveSign fonksiyonları önceki kodla aynı, yer kazanmak için burada atlandı) ...

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val IMAGE_PROXY = "/i/s/"
        private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val EPISODE_IN_SLUG = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        private val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
        private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
        private val OPEN_PLAYER = Regex("""openPlayer\s*\(\s*['"]([^'"]+)""")
        private val ORIGIN = Regex("""^https?://[^/]+""")
        private val ENC_FIELD = Regex("""\\{0,3}"(ciphertext|iv|salt|k)\\{0,3}":\\{0,3}"([^"\\]+)""")
    }
}
