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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-grid-home > a.episode-row-home").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.homepage-grid > a.homepage-card").mapNotNull { it.kartOgesi() }
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val title = selectFirst("span.episode-row-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = selectFirst("img")?.attr("src")

        // Bölüm URL'sinden dizi URL'sini çıkar: /dizi/xxx/1-sezon/1-bolum -> /dizi/xxx
        val seriesUrl = href.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.kartOgesi(): SearchResponse? {
        val title = selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(attr("href")) ?: return null
        val poster = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")
        val isAnime = href.contains("/anime/")
        val type = if (isAnime) TvType.Anime else TvType.TvSeries

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document

        return document.select("div.homepage-grid > a.homepage-card, div.episodes-grid-home > a.episode-row-home").mapNotNull {
            val title = it.selectFirst("h3.homepage-card-title, span.episode-row-title")?.text()?.trim()
                ?: return@mapNotNull null
            val href = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")

            if (href.contains("/film/")) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else if (href.contains("/dizi/") || href.contains("/anime/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // Bölüm sayfasından dizi detayına yönlendir
        if (url.contains("/sezon/") || url.contains("/bolum")) {
            val seriesUrl = url.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("div.series-poster img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("p.series-summary")?.text()?.trim()
        val year = document.selectFirst("div.series-data-item:contains(Yapım Yılı) time")?.text()?.toIntOrNull()

        val tags = document.select("div.series-badges a.series-badge").map { it.text().trim() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // Dizi bölümlerini çıkar
        val episodes = mutableListOf<Episode>()
        document.select("div.episodes-grid > div.episode-card > a.episode-card-link").forEach { epAnchor ->
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document

            // mntplayer iframe'ini ara
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")

            if (iframeSrc != null) {
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // Doğrudan m3u8 varsa
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(document.html())?.value
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

            false
        } catch (e: Exception) {
            Log.e("DiziPal12", "loadLinks HATA » ${e.message}", e)
            false
        }
    }
}
