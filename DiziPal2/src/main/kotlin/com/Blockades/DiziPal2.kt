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

    /**
     * content-card veya trending-item elementini SearchResponse'a çevirir.
     * HTML:
     *   <a href="..." class="card-link">
     *     <div class="card-poster">
     *       <img class="lazyload" data-src="..." alt="...">
     *       <div class="card-info">
     *         <h3 class="card-title">Title</h3>
     *         <div class="card-meta">
     *           <span class="card-rating"><i class="fas fa-star"></i> 7.0</span>
     *           <span class="card-year">2026</span>
     *         </div>
     *       </div>
     *     </div>
     *   </a>
     *
     * trending-item:
     *   <a href="..." class="trending-item">
     *     <div class="trending-poster"><img data-src="..." alt="..."></div>
     *     <div class="trending-info">
     *       <span class="trending-badge">Dizi</span>
     *       <h3 class="trending-title">Title</h3>
     *     </div>
     *   </a>
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href.isBlank()) return null

        // Başlık
        val title = this.selectFirst(".card-title")?.text()?.trim()
            ?: this.selectFirst(".trending-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.replace(" izle", "")
            ?: return null

        if (title.isBlank()) return null

        // Poster (lazyload data-src öncelikli)
        val poster = fixUrlNull(
            this.selectFirst("img.lazyload")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")
        )

        // Puan
        val rating = this.selectFirst(".card-rating")?.text()?.trim()
            ?.let { Regex("""([\d.]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

        // Yıl
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

    /**
     * episode-list-item elementini SearchResponse'a çevirir.
     * HTML:
     *   <a href="/bolum/..." class="episode-list-item">
     *     <div class="episode-thumb"><img data-src="..."></div>
     *     <div class="episode-meta">
     *       <span class="ep-title">Series Name</span>
     *       <span class="ep-info">1. Sezon 9. Bölüm</span>
     *       <span class="ep-time">9 saat önce</span>
     *     </div>
     *   </a>
     */
    private fun Element.toEpisodeSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        val epTitle = this.selectFirst(".ep-title")?.text()?.trim() ?: return null
        val epInfo  = this.selectFirst(".ep-info")?.text()?.trim() ?: ""

        // "Series - 1. Sezon 9. Bölüm" formatında birleştir
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

        // content-card (grid kartları)
        document.select("li.content-card a.card-link, a.card-link").forEach { el ->
            el.toSearchResponse()?.let { items.add(it) }
        }

        // trending-item (trend slider)
        if (items.isEmpty()) {
            document.select("a.trending-item").forEach { el ->
                el.toSearchResponse()?.let { items.add(it) }
            }
        }

        // episode-list-item (son bölümler)
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

        // 1. AJAX arama endpoint'i
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

        // 2. HTML arama (fallback)
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
        val document = app.get(url).document

        val jsonLd = document.select("script[type='application/ld+json']")
            .mapNotNull { it.data() }
            .firstOrNull { it.contains("\"TVSeries\"") || it.contains("\"Movie\"") }

        // Başlık
        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?: document.selectFirst("h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim()
            ?: jsonLd?.let { Regex(""""name"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: return null

        // Poster
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(
                document.selectFirst(".series-hero")?.attr("style")
                    ?.let { Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1) }
            )
            ?: jsonLd?.let {
                Regex(""""image"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.let { p -> fixUrl(p) }
            }

        // Açıklama
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p.series-description")?.text()?.trim()
            ?: document.selectFirst("p.movie-description")?.text()?.trim()
            ?: jsonLd?.let { Regex(""""description"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        // Etiketler
        val tags = document.select(".info-value.categories a, .categories a, a[href*='/kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it != "-" }

        // Yıl
        val year = document.selectFirst(".info-row:contains(Yıl) .info-value")?.text()?.trim()?.toIntOrNull()
            ?: jsonLd?.let {
                Regex(""""datePublished"\s*:\s*"?(\d{4})"?""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        return when {
            url.contains("/dizi/") || url.contains("/anime/") -> {
                val episodes = parseEpisodes(document)
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
     * Bölüm listesi:
     *   <a href="/bolum/..." class="detail-episode-item">
     *     <div class="detail-episode-title">Alıkara 1.Sezon 1.Bölüm</div>
     *     <div class="detail-episode-subtitle">1. Sezon 1. Bölüm</div>
     *   </a>
     */
    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        data class EpData(
            val href: String,
            val name: String?,
            val season: Int,
            val episode: Int
        )

        val epDataList = document.select(
            "a.detail-episode-item, .detail-episode-list a[href*='/bolum/']"
        ).mapNotNull { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null

            val subtitle = el.selectFirst(".detail-episode-subtitle")?.text()?.trim()
                ?: el.text().trim()

            val season = Regex("""(\d+)\.\s*Sezon""").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("""(\d+)\.\s*Bölüm""").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val epName = el.selectFirst(".detail-episode-title")?.text()?.trim()

            EpData(href, epName, season, episode)
        }.distinctBy { it.href }

        return epDataList.map { data ->
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
        Log.d("DPO2", "data » $data")

        // 1. Dizipal kendi player'ı (AES şifreli config)
        try {
            DizipalPlayer2().getUrl(data, "$mainUrl/", subtitleCallback, callback)
            Log.d("DPO2", "DizipalPlayer2 çağrıldı.")
            return true
        } catch (e: Exception) {
            Log.d("DPO2", "DizipalPlayer2 hatası » ${e.message}")
        }

        // 2. Yedek: iframe / meta / video
        try {
            val document = app.get(data).document

            val embedUrl = document.selectFirst("iframe[src]")?.attr("src")
                ?.takeIf { it.contains("player") || it.contains("embed") || it.contains("video") }
                ?: document.selectFirst("meta[property='og:video']")?.attr("content")
                ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

            if (!embedUrl.isNullOrBlank()) {
                val fixedUrl = fixUrl(embedUrl)
                Log.d("DPO2", "Yedek embedUrl » $fixedUrl")

                try {
                    if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) {
                        Log.d("DPO2", "loadExtractor başarılı.")
                        return true
                    }
                } catch (e: Exception) {
                    Log.d("DPO2", "loadExtractor hatası » ${e.message}")
                }
            }

            val videoSrc = document.selectFirst("video[src]")?.attr("src")
                ?: document.selectFirst("video source[src]")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                val fixedVideo = fixUrl(videoSrc)
                Log.d("DPO2", "Doğrudan video » $fixedVideo")
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
            Log.d("DPO2", "Yedek yöntem hatası » ${e.message}")
        }

        return false
    }
}
