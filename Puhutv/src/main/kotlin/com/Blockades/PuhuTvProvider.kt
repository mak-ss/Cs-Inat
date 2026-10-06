package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PuhuTvProvider : MainAPI() {

    override var mainUrl = "https://puhutv.com"
    override var name = "PuhuTV"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Documentary
    )

    override val mainPage = mainPageOf(
        "$mainUrl/puhutv-orijinal" to "PuhuTV Orijinal",
        "$mainUrl/yerli-diziler" to "Yerli Diziler",
        "$mainUrl/uzak-dogu-ruzgari" to "Uzak Doğu Rüzgarı"
    )

    private fun cleanUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun posterOf(element: Element): String? {
        val img = if (element.tagName() == "img") {
            element
        } else {
            element.selectFirst("img") ?: return null
        }

        val attributes = listOf(
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-image",
            "src"
        )

        for (attribute in attributes) {
            val value = img.attr(attribute).trim()
            if (value.isNotEmpty() && !value.startsWith("data:image")) {
                return cleanUrl(value)
            }
        }

        val srcSet = img.attr("srcset").trim()
        if (srcSet.isNotEmpty()) {
            val bestImage = srcSet
                .split(",")
                .map { it.trim() }
                .lastOrNull()
                ?.split(" ")
                ?.firstOrNull()

            if (!bestImage.isNullOrEmpty()) {
                return cleanUrl(bestImage)
            }
        }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data,
            headers = PuhuTvExtractor.headers
        ).document

        val homeItems = mutableListOf<HomePageList>()
        val bannerList = mutableListOf<SearchResponse>()

        val bannerCards = document.select(
            "div.swiper-slide, div.knzCCj, div.cHPtuV, div.bulpKy, article, .content-card, .new-episodes"
        )

        bannerCards.forEach { card ->
            val title = card.selectFirst(
                "h3, .bRGkdh, .spot-title, .content-name, .title, p"
            )
                ?.text()
                ?.trim()

            val href = card.selectFirst("a")
                ?.attr("href")
                ?.trim()

            val poster = posterOf(card)

            if (!title.isNullOrEmpty() && !href.isNullOrEmpty()) {
                val fullUrl = cleanUrl(href)
                bannerList.add(
                    newTvSeriesSearchResponse(
                        title,
                        fullUrl,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        if (bannerList.isNotEmpty()) {
            homeItems.add(
                HomePageList(
                    request.name,
                    bannerList.distinctBy { it.url }
                )
            )
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery"

        val document = app.get(
            searchUrl,
            headers = PuhuTvExtractor.headers
        ).document

        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        document.select(
            "div.hqfabl, div.cHPtuV, div.bulpKy, article, .content-card, a[href*='/dizi/'], a[href*='-detay']"
        ).forEach { element ->

            val anchor = if (element.tagName() == "a") {
                element
            } else {
                element.selectFirst("a")
            }

            val href = anchor?.attr("href")?.trim() ?: return@forEach
            val fullUrl = cleanUrl(href)

            if (!seen.add(fullUrl)) return@forEach

            val title = element.selectFirst(
                ".content-name, h3, p, .title"
            )
                ?.text()
                ?.trim()
                ?: anchor.text().trim()

            if (title.isEmpty()) return@forEach

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    fullUrl,
                    TvType.TvSeries
                ) {
                    this.posterUrl = posterOf(element)
                }
            )
        }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(
            url,
            headers = PuhuTvExtractor.headers
        ).document

        val title = document.selectFirst("h1, .bRGkdh, .spot-title, .content-title")
            ?.text()
            ?.trim()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
            ?: "Bilinmeyen Başlık"

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:image:url']")
                ?.attr("content")
            ?: document.selectFirst("img")
                ?.let { posterOf(it) }

        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".cCERCv, .ieOOnD, .description")
                ?.text()
                ?.trim()

        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()

        document.select(
            "a[href*='-bolum-izle'], a[href*='-detay'], .new-episodes, .episode-details"
        ).forEachIndexed { index, element ->

            val anchor = if (element.tagName() == "a") element else element.selectFirst("a")
            val epUrl = anchor?.attr("href")?.trim() ?: element.attr("href").trim()

            if (epUrl.isEmpty()) return@forEachIndexed

            val fullEpUrl = cleanUrl(epUrl)
            if (!seen.add(fullEpUrl)) return@forEachIndexed

            val rawTitle = element.selectFirst("h3, p, .title, .episode-title")
                ?.text()
                ?.trim()
                ?: anchor?.text()?.trim()

            val episodeNumber = extractEpisodeNumber(fullEpUrl, rawTitle, index + 1)
            val epTitle = rawTitle?.takeIf { it.isNotBlank() } ?: "$episodeNumber. Bölüm"
            val epPoster = posterOf(element) ?: poster

            episodes.add(
                newEpisode(fullEpUrl) {
                    name = epTitle
                    season = 1
                    episode = episodeNumber
                    posterUrl = epPoster
                }
            )
        }

        if (episodes.isEmpty()) {
            document.select("a[href]").forEachIndexed { index, anchor ->
                val href = anchor.attr("href").trim()
                if (href.contains("-bolum-izle", ignoreCase = true)) {
                    val fullEpUrl = cleanUrl(href)
                    if (seen.add(fullEpUrl)) {
                        val episodeNumber = extractEpisodeNumber(fullEpUrl, anchor.text().trim(), index + 1)
                        episodes.add(
                            newEpisode(fullEpUrl) {
                                name = "$episodeNumber. Bölüm"
                                season = 1
                                episode = episodeNumber
                                posterUrl = poster
                            }
                        )
                    }
                }
            }
        }

        episodes.sortWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun extractEpisodeNumber(
        url: String,
        title: String?,
        fallback: Int
    ): Int {

        val urlMatch = Regex("""-(\d+)-bolum-izle""").find(url)
        if (urlMatch != null) {
            return urlMatch.groupValues[1].toIntOrNull() ?: fallback
        }

        val titleMatch = Regex("""(?:bölüm|bolum)\s*(\d+)""", RegexOption.IGNORE_CASE).find(title ?: "")
        return titleMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallback
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        PuhuTvExtractor().getUrl(
            url = data,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }
}
