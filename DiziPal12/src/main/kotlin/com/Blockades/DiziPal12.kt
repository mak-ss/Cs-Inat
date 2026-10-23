package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal12 : MainAPI() {
    override var mainUrl = "https://dizipal12.com.tr"
    override var name = "DiziPal12"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/bolumler" to "Son Bölümler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/trend-1" to "Trend"
    )

    // ---------- ANASAYFA ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        // Son Bölümler sayfası
        if (request.data.contains("/bolumler")) {
            document.select("a.episode-row-home").forEach { el ->
                val title = el.selectFirst("span.episode-row-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val poster = el.selectFirst("img")?.attr("src")
                // /dizi/xxx/1-sezon/1-bolum -> /dizi/xxx
                val seriesUrl = href.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
                items.add(newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        // Trend carousel (trend-1 sayfası)
        if (request.data.contains("/trend")) {
            document.select("a.trend-card").forEach { el ->
                val title = el.selectFirst("h3.trend-card-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val poster = el.selectFirst("img")?.attr("src")
                val type = if (href.contains("/anime/")) TvType.Anime
                           else if (href.contains("/film/")) TvType.Movie
                           else TvType.TvSeries
                items.add(newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = poster
                })
            }
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        // Diziler / Filmler / Animeler / Platform / Kategori sayfaları
        document.select("a.homepage-card").forEach { el ->
            val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            // data-src öncelikli (lazyload), yoksa src
            val img = el.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            val type = if (href.contains("/anime/")) TvType.Anime
                       else if (href.contains("/film/")) TvType.Movie
                       else TvType.TvSeries

            items.add(newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            })
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ---------- ARAMA ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select("a.homepage-card").forEach { el ->
            val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            val img = el.selectFirst("img")
            val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            val type = if (href.contains("/anime/")) TvType.Anime
                       else if (href.contains("/film/")) TvType.Movie
                       else TvType.TvSeries

            results.add(newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            })
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- DETAY ----------
    override suspend fun load(url: String): LoadResponse? {
        // Bölüm URL'sinden dizi detayına yönlendir
        if (url.contains("/sezon/") && url.contains("/bolum")) {
            val seriesUrl = url.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?: return null

        // Poster: series-poster içindeki img
        val poster = document.selectFirst("div.series-poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("p.series-summary")?.text()?.trim()
        val year = document.selectFirst("div.series-data-item:contains(Yapım Yılı) time")?.text()?.toIntOrNull()
            ?: document.selectFirst("time[datetime]")?.text()?.toIntOrNull()

        val tags = document.select("div.series-badges a.series-badge").map { it.text().trim() }

        // Film mi?
        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Dizi/Anime bölümleri — detay sayfasındaki episode-card-link'ler
        val episodes = mutableListOf<Episode>()
        document.select("a.episode-card-link").forEach { epAnchor ->
            val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epTitle = epAnchor.attr("data-title").ifBlank {
                epAnchor.selectFirst("div.episode-title")?.text()?.trim() ?: "Bölüm"
            }
            val epSeason = epAnchor.attr("data-season").toIntOrNull()
            val epEpisode = epAnchor.attr("data-episode").toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.season = epSeason
                this.episode = epEpisode
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ---------- İZLEME LİNKLERİ ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = bölüm sayfası URL'si (/dizi/xxx/1-sezon/1-bolum)
            val document = app.get(data).document

            // 1) Doğrudan oynatıcı iframe'i (montana-frame)
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='vod.']")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                Log.d("DiziPal12", "iframe bulundu: $iframeSrc")
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // 2) Sayfa içinde doğrudan m3u8 varsa
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(document.html())?.value
            if (directM3u8 != null) {
                Log.d("DiziPal12", "m3u8 bulundu: $directM3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "DiziPal12 HLS",
                        url = directM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // 3) window.episodesData içindeki iframe_url_encrypted'i dene
            val episodesDataMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(document.html())?.groupValues?.getOrNull(1)
            if (episodesDataMatch != null) {
                // Geçerli bölümün token'ını al
                val tokenMatch = Regex("""/embed/\?token=([^"\\]+)""").find(episodesDataMatch)
                if (tokenMatch != null) {
                    val embedUrl = "$mainUrl/embed/?token=${tokenMatch.groupValues[1]}"
                    MntPlayer12().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            Log.e("DiziPal12", "Oynatıcı bulunamadı: $data")
            false
        } catch (e: Exception) {
            Log.e("DiziPal12", "loadLinks HATA » ${e.message}", e)
            false
        }
    }
}
