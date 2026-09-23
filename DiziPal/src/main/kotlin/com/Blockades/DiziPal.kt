package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode as CloudstreamEpisode

class Dizipal : MainAPI() {
    override var mainUrl = "https://dizipal.com2133" // Güncel alan adını buraya yazabilirsin
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override async fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = mutableListOf<HomePageList>()

        // Son Eklenenler / Popüler Listesi
        val movies = document.select("div.poster-grid div.poster, div.movie-item").mapNotNull {
            val title = it.selectFirst("div.title, h3, a")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }

        if (movies.isNotEmpty()) {
            items.add(HomePageList("Öne Çıkanlar", movies))
        }

        return newHomePageResponse(items)
    }

    override async fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.poster-grid div.poster, div.search-result").mapNotNull {
            val title = it.selectFirst("div.title, h3")?.text() ?: return@mapNotNull null
            val href = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src"))

            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override async fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title, h1")?.text()?.trim() ?: "Bilinmeyen İçerik"
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.description, div.overview")?.text()

        val isTvSeries = document.select("div.episodes, div.seasons").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<CloudstreamEpisode>()
            document.select("div.episode-item, a.episode").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach
                val epTitle = ep.selectFirst("span.title, div.name")?.text() ?: ep.text()
                val seasonNum = ep.attr("data-season").toIntOrNull()
                val episodeNum = ep.attr("data-episode").toIntOrNull()

                episodes.add(
                    CloudstreamEpisode(
                        data = epHref,
                        name = epTitle,
                        season = seasonNum,
                        episode = episodeNum
                    )
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override async fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1. Doğrudan iframe veya player embed linklerini kontrol et
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null) {
            val iframeDoc = app.get(fixUrl(iframeSrc)).document
            parseVideoConfig(iframeDoc, subtitleCallback, callback)
        } else {
            parseVideoConfig(document, subtitleCallback, callback)
        }

        return true
    }

    private fun parseVideoConfig(
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Altyazı .vtt dosyalarını ayıkla
        doc.select("track[kind=subtitles], track[kind=captions]").forEach { track ->
            val subUrl = fixUrlNull(track.attr("src"))
            val label = track.attr("label").ifEmpty { "Türkçe" }
            if (subUrl != null) {
                subtitleCallback(SubtitleFile(label, subUrl))
            }
        }

        // data-cfg veya script içerisindeki .m3u8 linklerini yakala
        val scriptData = doc.select("script").html()
        val m3u8Regex = Regex("""https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*""")
        val match = m3u8Regex.find(scriptData)

        if (match != null) {
            val streamUrl = match.value
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }
}
