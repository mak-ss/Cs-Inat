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

        // Sayfadaki bölümleri tara
        val sections = doc.select("section")
        for (sec in sections) {
            val titleEl = sec.selectFirst("h2, h3, .section-title, .homepage-section-title") ?: continue
            val rawTitle = titleEl.text().trim()
            if (rawTitle.isBlank()) continue

            val cards = sec.select("a[href*='/filmler/'], a[href*='/film/'], a[href*='/diziler/'], a[href*='/dizi/'], a[href*='/anime/']")
            val items = cards.mapNotNull { parseCard(it) }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                allPages.add(HomePageList(rawTitle, items))
            }
        }

        if (allPages.isEmpty()) {
            val generalCards = doc.select("a[href*='/filmler/'], a[href*='/film/'], a[href*='/diziler/'], a[href*='/dizi/'], a[href*='/anime/']")
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

        val cards = doc.select("a[href*='/filmler/'], a[href*='/film/'], a[href*='/diziler/'], a[href*='/dizi/'], a[href*='/anime/']")
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
            ?: doc.selectFirst("img.watch-mini-hero-poster, img[src*='/storage/posters/']")?.attr("src")
            ?: doc.selectFirst("img")?.attr("src")

        val plot = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: doc.selectFirst("p, div.description, div.summary")?.text()?.trim()

        val year = Regex("""(20\d\d|19\d\d)""").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        val tags = doc.select("a[href*='/tur/'], a[href*='/kategori/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }.distinct()

        val ratingText = doc.selectFirst(".watch-mini-hero-rating, span:contains(.)")?.text()
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

        // Dizi / Anime Detayları
        val episodes = mutableListOf<Episode>()

        // 1. DOM Üzerinden Bölüm Bağlantılarını Çekme
        val epLinks = doc.select("div.watch-episodes-list a, a[href*='/bolum/'], a[href*='/episode/'], div[class*='episode'] a")

        for (card in epLinks) {
            val epHref = fixUrl(card.attr("href"))
            val epImg = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            val sNum = Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?:sezon|season)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epNum = Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[/-](\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epNameText = card.selectFirst(".watch-episode-title-text, h3, .title")?.text()?.trim()
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

        // 2. window.episodesData İçindeki JSON Verisinden Bölüm Çıkarma
        val scriptContent = doc.select("script").html()
        if (scriptContent.contains("window.episodesData")) {
            val jsonMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""").find(scriptContent)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                val seasonRegex = Regex(""""(\d+)":\s*\{([^}]+)\}""")
                val epRegex = Regex(""""(\d+)":\s*\{"title":"([^"]*)","iframe_url":"([^"]*)","iframe_url_encrypted":"([^"]*)"""")

                for (sMatch in seasonRegex.findAll(jsonStr)) {
                    val sNum = sMatch.groupValues[1].toIntOrNull() ?: 1
                    val seasonBody = sMatch.groupValues[2]

                    for (eMatch in epRegex.findAll(seasonBody)) {
                        val eNum = eMatch.groupValues[1].toIntOrNull() ?: 1
                        val epTitle = eMatch.groupValues[2].ifBlank { "S${sNum}E${eNum}" }
                        val epUrl = "$mainUrl/dizi/3/$sNum-sezon/$eNum-bolum"

                        if (episodes.none { it.season == sNum && it.episode == eNum }) {
                            episodes.add(newEpisode(epUrl) {
                                this.name = "$eNum. Bölüm - $epTitle"
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = poster?.let { fixUrl(it) }
                            })
                        }
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "$title - 1. Bölüm"
                this.season = 1
                this.episode = 1
                this.posterUrl = poster?.let { fixUrl(it) }
            })
        }

        val type = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { "${it.season}-${it.episode}" }) {
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

        // 1. data-src ve src İçindeki Embed/Iframe Linklerini Yakalama
        val iframeElements = watchPage.select("iframe[src], iframe[data-src]")
        for (el in iframeElements) {
            val rawSrc = el.attr("data-src").ifEmpty { el.attr("src") }
            if (rawSrc.isBlank()) continue

            val fullIframeUrl = fixUrl(rawSrc)
            if (fullIframeUrl.contains("youtube.com") || fullIframeUrl.contains("google")) continue

            if (loadExtractor(fullIframeUrl, data, subtitleCallback) { link ->
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

        // 2. M3U8 Regex Taraması
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

        return found
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parseCard(element: Element): SearchResponse? {
        val anchor = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = anchor.attr("href")
        if (href.isBlank() || href == "#" || href.contains("/kayit") || href.contains("/giris") || href.contains("/account/")) return null

        val img = element.selectFirst("img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }

        val title = element.selectFirst("h3, .trend-card-title, .similar-card-title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: anchor.text().trim()

        val cleanName = cleanTitle(title)
        if (cleanName.isBlank()) return null

        val scoreText = element.selectFirst(".grid-card-rating, span:contains(.)")?.text()
        val cardScore = scoreText?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        val fullUrl = fixUrl(href)
        return when {
            fullUrl.contains("/filmler/") || fullUrl.contains("/film/") -> newMovieSearchResponse(cleanName, fullUrl, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
                if (cardScore != null) this.score = Score.from10(cardScore)
            }
            fullUrl.contains("/anime/") -> newTvSeriesSearchResponse(cleanName, fullUrl, TvType.Anime) {
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
        return raw.replace(Regex("(?i)\\s*(?:dizi|film|anime)?\\s*izle"), "")
            .replace(Regex("(?i)\\s*izle.*"), "")
            .trim()
    }
}
