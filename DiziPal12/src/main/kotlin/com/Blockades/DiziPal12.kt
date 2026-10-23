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

    // Sadece platformlar
    override val mainPage = mainPageOf(
        "$mainUrl/platform/netflix"      to "Netflix",
        "$mainUrl/platform/prime-video"  to "Prime Video",
        "$mainUrl/platform/disney"       to "Disney+",
        "$mainUrl/platform/hbomax"       to "HBOMax",
        "$mainUrl/platform/blutv"        to "BluTV",
        "$mainUrl/platform/exxen"        to "Exxen",
        "$mainUrl/platform/tabii"        to "Tabii",
        "$mainUrl/platform/apple-tv"     to "Apple TV+",
        "$mainUrl/platform/gain"         to "Gain",
        "$mainUrl/platform/mubi"         to "Mubi",
        "$mainUrl/platform/tod"          to "TOD",
        "$mainUrl/platform/tv"           to "TV+",
        "$mainUrl/platform/hulu"         to "Hulu",
        "$mainUrl/platform/crunchyroll"  to "Crunchyroll"
    )

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

    // ---------- ANASAYFA (Platform sayfaları) ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("DiziPal12", "getMainPage: ${request.data} page=$page")
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        // Platform sayfaları: article.grid-card > a.grid-card-link
        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach

            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim()
                ?: article.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach

            // Poster: img[src] doğrudan (lazyload yok)
            val img = article.selectFirst("img")
            val poster = cleanPoster(
                img?.attr("src"),
                img?.attr("data-src")
            )

            // İçerik türü: badge class'ından
            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") -> TvType.Anime
                badgeClass.contains("badge-film")  -> TvType.Movie
                badgeClass.contains("badge-dizi")  -> TvType.TvSeries
                href.contains("/anime/")           -> TvType.Anime
                href.contains("/film/")            -> TvType.Movie
                else                               -> TvType.TvSeries
            }

            val year = article.selectFirst("span.grid-card-year")?.text()?.trim()?.toIntOrNull()
            val rating = article.selectFirst("span.grid-card-rating")?.text()?.trim()
                ?.replace("star", "")?.trim()?.toDoubleOrNull()

            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            items.add(resp)
        }

        Log.d("DiziPal12", "items: ${items.size} (page $page)")

        // Pagination: sayfada pagination nav varsa hasNext=true
        val hasNext = document.selectFirst("nav.pagination button.pag-next:not(.disabled)") != null
            || document.selectFirst("nav.pagination a[rel=next]") != null
            || page < 20

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ---------- ARAMA ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        Log.d("DiziPal12", "search: $url")
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        // Arama sonuçları hem homepage-card hem grid-card olabilir
        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim() ?: return@forEach
            val img = article.selectFirst("img")
            val poster = cleanPoster(img?.attr("src"), img?.attr("data-src"))
            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") || href.contains("/anime/") -> TvType.Anime
                badgeClass.contains("badge-film")  || href.contains("/film/")  -> TvType.Movie
                else -> TvType.TvSeries
            }
            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster }
            }
            results.add(resp)
        }

        // Fallback: ana sayfa kartları
        if (results.isEmpty()) {
            document.select("a.homepage-card").forEach { el ->
                val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val img = el.selectFirst("img")
                val poster = cleanPoster(img?.attr("data-src"), img?.attr("src"))
                val type = if (href.contains("/anime/")) TvType.Anime
                           else if (href.contains("/film/")) TvType.Movie
                           else TvType.TvSeries
                results.add(newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster })
            }
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

        // Poster: series-poster img src, fallback og:image
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

        Log.d("DiziPal12", "load title=$title poster=$poster year=$year")

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
        Log.d("DiziPal12", "episodes: ${episodes.size}")

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
            if (directM3u8 != null) {
                Log.d("DiziPal12", "directM3u8=$directM3u8")
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

            // 3) episodesData içindeki embed token
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
