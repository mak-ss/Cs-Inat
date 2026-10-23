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

    // Poster URL temizleme: data: placeholder, SVG ve boş değerleri filtrele
    private fun cleanPoster(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            if (c.startsWith("data:")) continue
            if (c.endsWith(".svg")) continue
            if (c.contains("placeholder")) continue
            return c
        }
        return null
    }

    private fun Element.bestPoster(): String? {
        val img = selectFirst("img") ?: return null
        return cleanPoster(
            img.attr("data-src"),
            img.attr("data-srcset").substringBefore(" ").takeIf { it.isNotBlank() },
            img.attr("src")
        )
    }

    // ---------- ANASAYFA ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("DiziPal12", "getMainPage: ${request.data}")
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        if (request.data.contains("/bolumler")) {
            document.select("a.episode-row-home").forEach { el ->
                val title = el.selectFirst("span.episode-row-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val poster = el.selectFirst("img")?.attr("src")
                val seriesUrl = href.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
                items.add(newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = cleanPoster(poster)
                })
            }
            Log.d("DiziPal12", "bolumler items: ${items.size}")
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        if (request.data.contains("/trend")) {
            document.select("a.trend-card").forEach { el ->
                val title = el.selectFirst("h3.trend-card-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val poster = el.bestPoster()
                val type = if (href.contains("/anime/")) TvType.Anime
                           else if (href.contains("/film/")) TvType.Movie
                           else TvType.TvSeries
                items.add(newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = poster
                })
            }
            Log.d("DiziPal12", "trend items: ${items.size}")
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        document.select("a.homepage-card").forEach { el ->
            val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            val poster = el.bestPoster()
            val type = if (href.contains("/anime/")) TvType.Anime
                       else if (href.contains("/film/")) TvType.Movie
                       else TvType.TvSeries
            items.add(newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            })
        }
        Log.d("DiziPal12", "homepage items: ${items.size}")
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    // ---------- ARAMA ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        Log.d("DiziPal12", "search: $url")
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select("a.homepage-card").forEach { el ->
            val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            val poster = el.bestPoster()
            val type = if (href.contains("/anime/")) TvType.Anime
                       else if (href.contains("/film/")) TvType.Movie
                       else TvType.TvSeries
            results.add(newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            })
        }
        Log.d("DiziPal12", "search results: ${results.size}")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- DETAY ----------
    override suspend fun load(url: String): LoadResponse? {
        Log.d("DiziPal12", "load: $url")

        if (url.contains("/sezon/") && url.contains("/bolum")) {
            val seriesUrl = url.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?: return null

        // Poster: series-poster içindeki img (data-src öncelikli)
        val posterImg = document.selectFirst("div.series-poster img")
        val poster = cleanPoster(
            posterImg?.attr("src"),
            posterImg?.attr("data-src"),
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("p.series-summary")?.text()?.trim()
        val year = document.selectFirst("div.series-data-item:contains(Yapım Yılı) time")?.text()?.toIntOrNull()
            ?: document.selectFirst("time[datetime]")?.text()?.toIntOrNull()

        val tags = document.select("div.series-badges a.series-badge").map { it.text().trim() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

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
        Log.d("DiziPal12", "load episodes: ${episodes.size}, poster=$poster")

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
        Log.d("DiziPal12", "loadLinks data=$data")
        return try {
            val document = app.get(data).document
            val html = document.html()

            // 1) Doğrudan iframe
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='vod.']")?.attr("src")
                ?: document.selectFirst("iframe[src]")?.attr("src")

            Log.d("DiziPal12", "iframeSrc=$iframeSrc")
            if (!iframeSrc.isNullOrBlank()) {
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // 2) HTML içinde doğrudan m3u8
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value
            Log.d("DiziPal12", "directM3u8=$directM3u8")
            if (directM3u8 != null) {
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

            // 3) episodesData içindeki iframe_url_encrypted token
            val episodesDataMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
            if (episodesDataMatch != null) {
                val tokenMatch = Regex("""\\/embed\\/\?token=([^"\\]+)""").find(episodesDataMatch)
                    ?: Regex("""/embed/\?token=([^"\\]+)""").find(episodesDataMatch)
                if (tokenMatch != null) {
                    val embedUrl = "$mainUrl/embed/?token=${tokenMatch.groupValues[1]}"
                    Log.d("DiziPal12", "embedUrl=$embedUrl")
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
