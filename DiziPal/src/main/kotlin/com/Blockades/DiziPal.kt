package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1432.com"
    override var name = "DiziPal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        private val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to "https://dizipal1583.com/"
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

        // DiziPal ana sayfasındaki dinamik kategorileri/bölümleri tara
        val sections = doc.select("section, div.section-title, div.col-12, div.module")
        for (sec in sections) {
            val titleEl = sec.selectFirst("h2, h3, .title, .section-title") ?: continue
            val rawTitle = titleEl.text().trim()
            if (rawTitle.isBlank() || rawTitle.contains("DiziPal", ignoreCase = true)) continue

            val cards = sec.select("article, div.item, div.movie-poster, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
            val items = cards.mapNotNull { parseCard(it) }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                allPages.add(HomePageList(rawTitle, items))
            }
        }

        // Sayfada belirgin bölümler yoksa tüm kartları genel listeye ekle
        if (allPages.isEmpty()) {
            val generalCards = doc.select("article, div.item, a[href*='/dizi/'], a[href*='/film/']")
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
            // POST / AJAX arama yöntemini fallback olarak dene
            try {
                app.post(
                    "$mainUrl/api/search",
                    data = mapOf("q" to query),
                    headers = defaultHeaders
                ).document
            } catch (_: Exception) {
                return emptyList()
            }
        }

        val cards = doc.select("article, div.item, div.poster, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
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
            ?: doc.selectFirst("div.poster img, img.lazy, img.cover")?.attr("src")
            ?: doc.selectFirst("img[src*='/posters/']")?.attr("src")

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("div.description, p.overview, div.summary")?.text()?.trim()

        val year = Regex("""(20\d\d|19\d\d)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/tur/'], a[href*='/genre/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val ratingText = doc.selectFirst(".rating, .imdb, span.score")?.text()
        val score = ratingText?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val isMovie = url.contains("/film/") || url.contains("/movie/")

        if (isMovie) {
            val watchUrl = if (url.endsWith("/izle")) url else "${url.removeSuffix("/")}/izle"
            val episodes = listOf(
                newEpisode(watchUrl) {
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

        // Dizi / Anime işlemleri
        val episodes = mutableListOf<Episode>()
        val seasonElements = doc.select("div.seasons, div.season-list, select.season-select option, a[href*='sezon']")

        val epLinks = doc.select("a[href*='/bolum/'], a[href*='/episode/'], div.episode-list a")

        if (epLinks.isNotEmpty()) {
            for (card in epLinks) {
                val epHref = fixUrl(card.attr("href"))
                val epImg = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                val sNum = Regex("""(?:sezon|season)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)x\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epNum = Regex("""(?:bolum|episode)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\d+x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epNameText = card.selectFirst(".title, .ep-title, span")?.text()?.trim()
                val epName = if (!epNameText.isNullOrBlank()) {
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
            // Tekil/Düz liste tespiti
            episodes.add(newEpisode(url) {
                this.name = "$title - 1. Bölüm"
                this.season = 1
                this.episode = 1
                this.posterUrl = poster?.let { fixUrl(it) }
            })
        }

        val type = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

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

        // 1. DiziPal JS/M3U8 Stream veya Iframe extraction
        val pageHtml = watchPage.html()
        
        // M3U8 regex taraması
        val m3u8Matches = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").findAll(pageHtml)
        for (match in m3u8Matches) {
            val streamUrl = match.groupValues[1]
            if (streamUrl.contains("master.m3u8") || streamUrl.contains("index") || streamUrl.contains(".m3u8")) {
                callback(
                    ExtractorLink(
                        source = "DiziPal",
                        name = "DiziPal Main HLS",
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

        // 2. Embedded Iframe ve Player Çözümleri
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
        if (href.isBlank() || href.contains("/sezon/") || href.contains("/bolum/")) return null

        val img = element.selectFirst("img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("srcset")?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() }

        val rawName = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst(".title, h2, h3, h4, p, span")?.text()
            ?: anchor.text()

        val title = cleanTitle(rawName)
        if (title.isBlank()) return null

        val ratingRaw = element.selectFirst(".rating, .imdb, [class*='score']")?.text()?.trim()
        val cardScore = ratingRaw?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val fullUrl = fixUrl(href)
        return when {
            fullUrl.contains("/film/") || fullUrl.contains("/movie/") -> newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
            fullUrl.contains("/anime/") -> newTvSeriesSearchResponse(title, fullUrl, TvType.Anime) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
            else -> newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("(?i)\\s*(?:dizi|film|anime)\\s*izle"), "")
            .replace(Regex("(?i)\\s*izle.*"), "")
            .replace(Regex("(?i)\\s*\\(\\d{4}\\).*"), "")
            .trim()
    }
}
