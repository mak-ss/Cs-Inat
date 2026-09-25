package com.Blockades

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1432.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/diziler" to "Yeni Diziler",
        "${mainUrl}/filmler" to "Yeni Filmler",
        "${mainUrl}/platform/netflix" to "Netflix",
        "${mainUrl}/platform/exxen" to "Exxen",
        "${mainUrl}/platform/prime-video" to "Prime Video",
        "${mainUrl}/platform/gain" to "Gain",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/hbo-max" to "HBO Max",
        "${mainUrl}/platform/tabii" to "tabii",
        "${mainUrl}/platform/apple-tv" to "Apple TV",
        "${mainUrl}/animeler" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=${page}"
        }

        val doc = app.get(targetUrl).document
        val items = doc.select("a[href*='/dizi/'], a[href*='/film/']").mapNotNull { el ->
            parseSearchElement(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    fun parseSearchElement(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/")) return null
        if (href.endsWith("/diziler") || href.endsWith("/filmler")) return null

        val title = element.attr("title").ifBlank {
            element.selectFirst(".title, h2, h3, h4, span.title, div.name, .film-title, .dizi-title")?.text()?.trim()
                ?: element.text().trim()
        }
        if (title.isBlank() || title.equals("Diziler", ignoreCase = true) || title.equals("Filmler", ignoreCase = true)) return null

        // Önce element içinde img ara, yoksa parent ve kardeş elementlere bak
        var imgEl = element.selectFirst("img.lazy, img[data-src], img[data-lazy-src], .poster img, .cover img, .afis img, img")
        if (imgEl == null) {
            imgEl = element.parent()?.selectFirst("img")
        }
        if (imgEl == null) {
            imgEl = element.parent()?.parent()?.selectFirst("img")
        }

        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.ifBlank { null }
                ?: imgEl?.attr("data-lazy-src")?.ifBlank { null }
                ?: imgEl?.attr("data-original")?.ifBlank { null }
                ?: imgEl?.attr("src")?.ifBlank { null }
        )

        val isSeries = href.contains("/dizi/")
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val targetUrl = "${mainUrl}/api/search.php?q=${encodedQuery}"

        val response = app.get(
            targetUrl,
            headers = mapOf(
                "Referer" to "${mainUrl}/",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<DiziPalSearchRoot>()

        val items = response?.results?.mapNotNull { res ->
            val title = res.title ?: return@mapNotNull null
            val slug = res.slug ?: return@mapNotNull null
            val isSeries = res.type == "series"
            val href = if (isSeries) "${mainUrl}/dizi/${slug}" else "${mainUrl}/film/${slug}"
            val poster = fixUrlNull(res.poster)
            val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = poster
                }
            }
        }?.distinctBy { it.url } ?: emptyList()

        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        return parseLoadMetadata(doc, url)
    }

    suspend fun parseLoadMetadata(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.substringBefore(" izle")?.trim() ?: return null

        // Poster için daha kapsamlı selector'lar
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst("div.cover img, div.poster img, .afis img, .film-poster img, img.poster, .poster-image img, .detail-poster img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )

        val description = doc.selectFirst("div.summary, div.overview, p.description, meta[property='og:description'], div.plot, div.aciklama")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }

        val year = doc.selectFirst("span.year, div.year, a[href*='yil/'], span[itemprop='datePublished']")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("span.imdb-rating, div.rating, span.score, span[itemprop='ratingValue']")?.text()?.trim()

        val tags = doc.select("a[href*='/tur/'], a[href*='/kategori/'], a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val isSeries = url.contains("/dizi/")

        if (isSeries) {
            val episodes = doc.select("a[href*='/bolum/'], a[href*='/episode/']").mapNotNull { epEl ->
                val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val epText = epEl.text().trim()

                val seasonNum = Regex("(\\d+)\\.\\s*Sezon").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("-?(\\d+)-sezon").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("-?(\\d+)-bolum").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val epName = epText.ifBlank { "${seasonNum}. Sezon ${epNum}. Bölüm" }

                newEpisode(epHref) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = Score.from10(score)
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.score = Score.from10(score)
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "${mainUrl}/").document

        val candidateUrls = mutableListOf<String>()

        // Script içindeki base64 encodedContent
        for (s in doc.select("script")) {
            val text = s.data()
            val encodedMatch = Regex("const\\s+encodedContent\\s*=\\s*'([A-Za-z0-9+/=]+)'").find(text)
            if (encodedMatch != null) {
                val b64 = encodedMatch.groupValues[1]
                try {
                    val decoded = String(base64DecodeArray(b64), StandardCharsets.UTF_8)
                    val iframeSrc = Regex("src=\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
                    if (iframeSrc != null) {
                        candidateUrls.add(fixUrl(iframeSrc))
                    }
                } catch (_: Exception) {
                }
            }
        }

        // Doğrudan iframe src
        for (iframe in doc.select("iframe[src]")) {
            val src = fixUrlNull(iframe.attr("src")) ?: continue
            if (src.isNotBlank() && !src.startsWith("about:")) {
                candidateUrls.add(src)
            }
        }

        // data-src iframe
        for (iframe in doc.select("iframe[data-src]")) {
            val src = fixUrlNull(iframe.attr("data-src")) ?: continue
            if (src.isNotBlank() && !src.startsWith("about:")) {
                candidateUrls.add(src)
            }
        }

        // data-video veya benzeri attribute'lar
        for (el in doc.select("[data-video], [data-iframe], [data-url]")) {
            val src = el.attr("data-video").ifBlank { el.attr("data-iframe") }.ifBlank { el.attr("data-url") }
            if (src.isNotBlank()) {
                val fixed = fixUrlNull(src)
                if (fixed != null) candidateUrls.add(fixed)
            }
        }

        if (candidateUrls.isEmpty()) return false

        var anyFound = false
        val mutex = Mutex()

        coroutineScope {
            candidateUrls.distinct().forEach { candidate ->
                launch {
                    try {
                        val found = if (candidate.contains("videoplay.vip")) {
                            resolveVideoPlay(candidate, data, subtitleCallback, callback)
                        } else {
                            loadExtractor(candidate, "${mainUrl}/", subtitleCallback, callback)
                        }
                        if (found) {
                            mutex.withLock { anyFound = true }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        return anyFound
    }

    private suspend fun resolveVideoPlay(
        videoPlayUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val resp = app.get(videoPlayUrl, referer = refererUrl).text
            val m3uPath = Regex("const\\s+src\\s*=\\s*'([^']+)'").find(resp)?.groupValues?.get(1) ?: return false

            val fullM3u = if (m3uPath.startsWith("http")) m3uPath else "https://videoplay.vip${m3uPath}"

            // Altyazıları parse et
            val tracksJson = Regex("const\\s+tracksData\\s*=\\s*(\\{.*?\\});").find(resp)?.groupValues?.get(1)
            if (tracksJson != null) {
                val tracks = AppUtils.tryParseJson<VideoPlayTracks>(tracksJson)
                tracks?.subtitles?.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    val subFull = if (subUrl.startsWith("http")) subUrl
                    else "https://videoplay.vip/play.m3u8?id=${sub.id}&p=${URLEncoder.encode(subUrl, "UTF-8")}"
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = sub.name ?: "Türkçe",
                            url = subFull
                        )
                    )
                }
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fullM3u,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://videoplay.vip/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    data class DiziPalSearchRoot(
        @JsonProperty("results") val results: List<DiziPalSearchResult>? = null
    )

    data class DiziPalSearchResult(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    data class VideoPlayTracks(
        @JsonProperty("subtitles") val subtitles: List<VideoPlaySubtitle>? = null
    )

    data class VideoPlaySubtitle(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
