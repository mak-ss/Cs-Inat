package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal10.com.tr"
    override var name = "DiziPal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        private val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to "https://dizipal10.com.tr/"
        )
    }

    // ── Main Page ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allPages = mutableListOf<HomePageList>()

        val doc = try {
            app.get(mainUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(allPages)
        }

        // 1. Trend Diziler / Filmler
        val trendCards = doc.select("a.trend-card")
        val trendItems = trendCards.mapNotNull { parseCard(it) }.distinctBy { it.url }
        if (trendItems.isNotEmpty()) {
            allPages.add(HomePageList("Trendler", trendItems))
        }

        // 2. Son Eklenen İçerikler (Homepage Grid)
        val gridCards = doc.select("a.homepage-card, div.homepage-grid a")
        val gridItems = gridCards.mapNotNull { parseCard(it) }.distinctBy { it.url }
        if (gridItems.isNotEmpty()) {
            allPages.add(HomePageList("Son Eklenenler", gridItems))
        }

        // 3. Genel Bölüm Taraması (Yedek Seçici)
        if (allPages.isEmpty()) {
            val generalCards = doc.select("a[href*='/film/'], a[href*='/filmler/'], a[href*='/dizi/'], a[href*='/diziler/'], a[href*='/anime/']")
            val items = generalCards.mapNotNull { parseCard(it) }.distinctBy { it.url }
            if (items.isNotEmpty()) {
                allPages.add(HomePageList("Öne Çıkanlar", items))
            }
        }

        return newHomePageResponse(allPages)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encodedQuery"

        val doc = try {
            app.get(searchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }

        val cards = doc.select("a.homepage-card, a.trend-card, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
        return cards.mapNotNull { parseCard(it) }.distinctBy { it.url }
    }

    // ── Load Details ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = defaultHeaders).document

        val rawTitle = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        } ?: "İçerik"

        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("img[src*='image.tmdb.org']")?.attr("src")
            ?: doc.selectFirst("img[data-src*='image.tmdb.org']")?.attr("data-src")
            ?: doc.selectFirst("img")?.attr("src")

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("p, div.description, div.summary")?.text()?.trim()

        val year = Regex("""(20\d\d|19\d\d)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/kategori/'], a[href*='/tur/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val ratingText = doc.selectFirst(".grid-card-rating, .meta-rating, span:contains(.)")?.text()
        val score = ratingText?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val isMovie = url.contains("/filmler/") || url.contains("/film/")

        if (isMovie) {
            val episodes = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster?.let { fixUrl(it) }
                }
            )

            return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it) }
            }
        }

        // Dizi / Anime Detayları ve Bölüm Ayıklama
        val episodes = mutableListOf<Episode>()
        val epLinks = doc.select("a[href*='/sezon/'], a[href*='/bolum/'], a.episode-row-home, div[class*='episode'] a")

        if (epLinks.isNotEmpty()) {
            for (card in epLinks) {
                val epHref = fixUrl(card.attr("href"))
                val epImg = card.selectFirst("img")?.let { 
                    it.attr("src").ifEmpty { it.attr("data-src") } 
                }?.let { fixUrl(it) }

                // URL Kalıbı: /dizi/ornek-dizi/1-sezon/3-bolum
                val sNum = Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?:sezon|season)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val epNum = Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?:bolum|episode)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val epNameText = card.selectFirst("h3, .title, .episode-row-title, span")?.text()?.trim()
                val epName = if (!epNameText.isNullOrBlank() && !epNameText.equals(title, ignoreCase = true)) {
                    "$epNum. Bölüm - $epNameText"
                } else {
                    "$sNum. Sezon $epNum. Bölüm"
                }

                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = sNum
                    this.episode = epNum
                    this.posterUrl = epImg ?: poster?.let { fixUrl(it) }
                })
            }
        } else {
            episodes.add(newEpisode(url) {
                this.name = "$title - 1. Bölüm"
                this.season = 1
                this.episode = 1
                this.posterUrl = poster?.let { fixUrl(it) }
            })
        }

        val type = if (url.contains("/anime")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { it.data }) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = score?.let { Score.from10(it) }
        }
    }

    // ── Load Links ──────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchPage = try {
            app.get(data, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var found = false
        val pageHtml = watchPage.html()

        // 1. M3U8 Regex Taraması
        val m3u8Matches = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(pageHtml)
        for (match in m3u8Matches) {
            val streamUrl = match.groupValues[1]
            if (streamUrl.contains(".m3u8")) {
                callback(
                    ExtractorLink(
                        source = "DiziPal",
                        name = "DiziPal HLS",
                        url = streamUrl,
                        referer = data,
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to defaultHeaders["User-Agent"]!!
                        )
                    )
                )
                found = true
            }
        }

        // 2. Iframe ve Oynatıcı Taraması
        val iframes = watchPage.select("iframe[src], iframe[data-src]").map {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        for (iframe in iframes) {
            val fullIframe = fixUrl(iframe)
            if (fullIframe.isBlank() || fullIframe.contains("youtube.com") || fullIframe.contains("google")) continue

            if (loadExtractor(fullIframe, data, subtitleCallback) { link ->
                callback(
                    ExtractorLink(
                        link.source ?: "DiziPal",
                        "DiziPal - ${link.name}",
                        link.url ?: "",
                        link.referer ?: mainUrl,
                        link.quality,
                        link.headers ?: emptyMap(),
                        link.extractorData,
                        link.type,
                        link.audioTracks ?: emptyList()
                    )
                )
            }) {
                found = true
            }
        }

        return found
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parseCard(element: Element): SearchResponse? {
        val anchor = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/account/") || href.contains("/platform/")) return null

        val img = element.selectFirst("img")
        val poster = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val title = element.selectFirst("h3, .trend-card-title, .homepage-card-title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: anchor.text().trim()

        val cleanName = cleanTitle(title)
        if (cleanName.isBlank()) return null

        val scoreText = element.selectFirst(".grid-card-rating, .meta-rating, span:contains(.)")?.text()
        val cardScore = scoreText?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val fullUrl = fixUrl(href)
        return when {
            fullUrl.contains("/film/") || fullUrl.contains("/filmler/") -> newMovieSearchResponse(cleanName, fullUrl, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
            fullUrl.contains("/anime") -> newTvSeriesSearchResponse(cleanName, fullUrl, TvType.Anime) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
            else -> newTvSeriesSearchResponse(cleanName, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("(?i)\\s*(?:dizi|film|anime)\\s*izle"), "")
            .replace(Regex("(?i)\\s*izle.*"), "")
            .trim()
    }
}
