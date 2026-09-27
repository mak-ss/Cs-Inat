package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class DiziPal2 : MainAPI() {
    override var mainUrl              = "https://dizipal2134.com"
    override var name                 = "DiziPal2"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Son Eklenen Diziler",
        "${mainUrl}/filmler" to "Son Eklenen Filmler",
        "${mainUrl}/trend"   to "Trend",
        "${mainUrl}/anime"   to "Anime",
    )

    // ==================== ORTAK PARSER ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.isBlank()) return null

        val title = this.selectFirst(".card-title")?.text()?.trim()
            ?: this.selectFirst(".trending-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.replace(" izle", "")
            ?: return null

        if (title.isBlank()) return null

        val poster = fixUrlNull(
            this.selectFirst("img.lazyload")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )

        val rating = this.selectFirst(".card-rating")?.text()?.trim()
            ?.let { Regex("""([\d.]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

        val year = this.selectFirst(".card-year")?.text()?.trim()?.toIntOrNull()

        return when {
            href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
            href.contains("/anime/") -> newTvSeriesSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
            href.contains("/dizi/") -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
            href.contains("/bolum/") -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        }
    }

    private fun Element.toEpisodeSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        val epTitle = this.selectFirst(".ep-title")?.text()?.trim() ?: return null
        val epInfo  = this.selectFirst(".ep-info")?.text()?.trim() ?: ""

        val displayTitle = if (epInfo.isNotBlank()) "$epTitle - $epInfo" else epTitle

        val poster = fixUrlNull(
            this.selectFirst("img.lazyload")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        return newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ==================== ANA SAYFA ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}${if (request.data.contains("?")) "&" else "?"}page=${page}"
        } else request.data

        val document = app.get(url).document

        val items = mutableListOf<SearchResponse>()

        document.select("li.content-card a.card-link, a.card-link").forEach { el ->
            el.toSearchResponse()?.let { items.add(it) }
        }

        if (items.isEmpty()) {
            document.select("a.trending-item").forEach { el ->
                el.toSearchResponse()?.let { items.add(it) }
            }
        }

        document.select("a.episode-list-item").forEach { el ->
            el.toEpisodeSearchResponse()?.let { items.add(it) }
        }

        return newHomePageResponse(
            request.name,
            items.distinctBy { it.url }
        )
    }

    // ==================== ARAMA ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        try {
            val apiUrl = "${mainUrl}/ajax-search?q=$encoded"
            val response = app.get(
                apiUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text

            val json = org.json.JSONObject(response)
            if (json.optBoolean("success", false)) {
                val arr = json.optJSONArray("results")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val title = item.optString("title", "").trim()
                        val href = fixUrl(item.optString("url", ""))
                        val poster = item.optString("poster", "").takeIf { it.isNotBlank() }
                        val type = item.optString("type", "")

                        if (title.isBlank() || href.isBlank()) continue

                        val searchResp = when {
                            type.equals("Dizi", true) || href.contains("/dizi/") ->
                                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                            type.equals("Film", true) || href.contains("/film/") ->
                                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                            else ->
                                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                        }
                        results.add(searchResp)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("DPO2", "AJAX arama hatası » ${e.message}")
        }

        if (results.isEmpty()) {
            try {
                val searchUrl = "${mainUrl}/arama?q=$encoded"
                val document = app.get(searchUrl).document

                document.select("li.content-card a.card-link, a.card-link").forEach { el ->
                    el.toSearchResponse()?.let { results.add(it) }
                }

                if (results.isEmpty()) {
                    document.select("a.trending-item").forEach { el ->
                        el.toSearchResponse()?.let { results.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.d("DPO2", "HTML arama hatası » ${e.message}")
            }
        }

        return results.distinctBy { it.url }
    }

    // ==================== DETAY SAYFASI ====================

    override suspend fun load(url: String): LoadResponse? {
        Log.e("DPO2", "===== load ÇAĞRILDI =====")
        Log.e("DPO2", "url » $url")

        val document = app.get(url).document

        val jsonLd = document.select("script[type='application/ld+json']")
            .mapNotNull { it.data() }
            .firstOrNull { it.contains("\"TVSeries\"") || it.contains("\"Movie\"") }

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?: document.selectFirst("h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim()
            ?: jsonLd?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: return null

        Log.e("DPO2", "title » $title")

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(
                document.selectFirst(".series-hero")?.attr("style")
                    ?.let { Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1) }
            )
            ?: jsonLd?.let {
                Regex(""""image"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.let { p -> fixUrl(p) }
            }

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p.series-description")?.text()?.trim()
            ?: document.selectFirst("p.movie-description")?.text()?.trim()
            ?: jsonLd?.let { Regex(""""description"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        val tags = document.select(".info-value.categories a, .categories a, a[href*='/kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it != "-" }

        val year = document.selectFirst(".info-row:contains(Yıl) .info-value")?.text()?.trim()?.toIntOrNull()
            ?: jsonLd?.let {
                Regex(""""datePublished"\s*:\s*"?(\d{4})"?""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        return when {
            url.contains("/dizi/") || url.contains("/anime/") -> {
                val episodes = parseEpisodes(document)
                Log.e("DPO2", "TOPLAM BÖLÜM » ${episodes.size}")
                episodes.take(3).forEachIndexed { i, ep ->
                    Log.e("DPO2", "  EP#$i » name=${ep.name}, url=${ep.data}, s=${ep.season}, e=${ep.episode}")
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
            else -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }
        }
    }

    /**
     * Bölüm listesi parser — farklı seçicileri dener
     */
    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        data class EpData(
            val href: String,
            val name: String?,
            val season: Int,
            val episode: Int
        )

        // Farklı seçicileri dene
        val selectors = listOf(
            "a.detail-episode-item",
            ".detail-episode-list a[href*='/bolum/']",
            ".episode-item-wrap a[href*='/bolum/']",
            "a.episode-item[href*='/bolum/']",
            ".episode-grid-small a[href*='/bolum/']",
            "a[href*='/bolum/']"
        )

        val epDataList = mutableListOf<EpData>()
        var usedSelector = ""

        for (selector in selectors) {
            val elements = document.select(selector)
            Log.e("DPO2", "Seçici » $selector → ${elements.size} element")
            if (elements.isNotEmpty()) {
                usedSelector = selector
                elements.forEach { el ->
                    val href = fixUrlNull(el.attr("href")) ?: return@forEach

                    val subtitle = el.selectFirst(".detail-episode-subtitle")?.text()?.trim()
                        ?: el.selectFirst(".ep-label")?.text()?.trim()
                        ?: el.text().trim()

                    val season = Regex("""(\d+)\.\s*Sezon""").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = Regex("""(\d+)\.\s*Bölüm""").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val epName = el.selectFirst(".detail-episode-title")?.text()?.trim()
                        ?: subtitle

                    epDataList.add(EpData(href, epName, season, episode))
                }
                break
            }
        }

        Log.e("DPO2", "KULLANILAN SEÇİCİ » $usedSelector")
        Log.e("DPO2", "BULUNAN BÖLÜM » ${epDataList.size}")

        return epDataList.distinctBy { it.href }.map { data ->
            newEpisode(data.href) {
                this.name = data.name
                this.season = data.season
                this.episode = data.episode
            }
        }
    }

    // ==================== LİNK YÜKLEME ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e("DPO2", "===== loadLinks ÇAĞRILDI =====")
        Log.e("DPO2", "data » $data")
        Log.e("DPO2", "isCasting » $isCasting")

        // 1. Dizipal kendi player'ı (AES şifreli config)
        try {
            Log.e("DPO2", ">>> DizipalPlayer2 INSTANCE OLUŞTURULUYOR...")
            val player = DizipalPlayer2()
            Log.e("DPO2", ">>> DizipalPlayer2 INSTANCE HAZIR")
            Log.e("DPO2", ">>> getUrl ÇAĞRILIYOR...")
            player.getUrl(data, "$mainUrl/", subtitleCallback, callback)
            Log.e("DPO2", ">>> getUrl TAMAMLANDI")
            return true
        } catch (e: Exception) {
            Log.e("DPO2", "DizipalPlayer2 HATA » ${e.message}", e)
        }

        // 2. Yedek: sayfadan iframe/video/meta bul
        try {
            Log.e("DPO2", ">>> YEDEK yöntem deneniyor...")
            val document = app.get(data).document

            val embedUrl = document.selectFirst("iframe[src]")?.attr("src")
                ?.takeIf { it.contains("player") || it.contains("embed") || it.contains("video") }
                ?: document.selectFirst("meta[property='og:video']")?.attr("content")
                ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

            if (!embedUrl.isNullOrBlank()) {
                val fixedUrl = fixUrl(embedUrl)
                Log.e("DPO2", "Yedek embedUrl » $fixedUrl")

                try {
                    if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                        Log.e("DPO2", "Yedek loadExtractor BAŞARILI")
                        return true
                    }
                    Log.e("DPO2", "Yedek loadExtractor FALSE döndü")
                } catch (e: Exception) {
                    Log.e("DPO2", "Yedek loadExtractor hatası » ${e.message}")
                }
            }

            val videoSrc = document.selectFirst("video[src]")?.attr("src")
                ?: document.selectFirst("video source[src]")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val fixedVideo = fixUrl(videoSrc)
                Log.e("DPO2", "Doğrudan video » $fixedVideo")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fixedVideo,
                        type = if (fixedVideo.contains(".m3u8"))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.e("DPO2", "Yedek yöntem hatası » ${e.message}")
        }

        Log.e("DPO2", ">>> HİÇBİR YÖNTEM ÇALIŞMADI")
        return false
    }
}
