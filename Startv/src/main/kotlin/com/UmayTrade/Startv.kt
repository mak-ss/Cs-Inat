package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.parseDate

@Suppress("unused")
class StarTVProvider : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
override var name = "Star TV"
override val hasMainPage = true
override var lang = "tr"
override val supportedTypes = setOf(TvType.TvSeries)

    private val posterBaseUrl = "https://img-s.mncdn.com"
override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/programlar" to "Programlar"
)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val shows = document.select("div.show-card").mapNotNull {
            val title = it.select("h3").text().trim()
            val href = it.select("a").attr("href")
            val image = it.select("img").attr("data-src")
            val poster = if (image.startsWith("/")) "$posterBaseUrl${image.removePrefix("/")}" else image

            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, shows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=$query"
val document = app.get(url).document
        return document.select("div.search-result-item").map {
            val title = it.select("h4").text().trim()
            val href = it.select("a").attr("href")
            val image = it.select("img").attr("data-src")
            val poster = if (image.startsWith("/")) "$posterBaseUrl${image.removePrefix("/")}" else image

            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.select("h1").text().trim()
        val description = document.select("div.description").text().trim()
        val image = document.select("meta[property='og:image']").attr("content")
        val episodes = mutableListOf<Episode>()

        document.select("div.episode-item").forEach { ep ->
            val epTitle = ep.select("h4").text().trim()
            val epUrl = ep.select("a").attr("href")
            val epImage = ep.select("img").attr("data-src")
            val releaseText = ep.select("span.date").text().trim()
            val releaseDate = parseDate(releaseText)

            episodes.add(
                newEpisode(epUrl) {
                    name = epTitle
                    posterUrl = if (epImage.startsWith("/")) "$posterBaseUrl${epImage.removePrefix("/")}" else epImage
                    this.date = releaseDate
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            plot = description
            posterUrl = image
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val videoUrl = document.select("video source").attr("src")
        val referer = mainUrl

        if (videoUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                videoUrl,
                data,
                headers = mapOf("Referer" to referer)
            ).forEach { callback(it) }
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = referer,
                    quality = 0,
                    headers = mapOf("Referer" to referer)
                )
            )
        }
        return true
}
}

