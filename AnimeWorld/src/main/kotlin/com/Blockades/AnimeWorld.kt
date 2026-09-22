// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Document

class AnimeWorld : MainAPI() {
    override var mainUrl = "https://www.animeworld.ac"
    override var name = "AnimeWorld"
    override val hasMainPage = true
    override var lang = "it"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "${mainUrl}/updated" to "Nuovi Episodi",
        "${mainUrl}/animes" to "Anime",
        "${mainUrl}/ongoing" to "In Corso",
        "${mainUrl}/movies" to "Film Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val home = document.select("div.film-list div.item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.name") ?: return null
        val title = titleElement.text() ?: return null
        val href = fixUrlNull(titleElement.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val isDub = this.selectFirst("div.status div.dub") != null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(isDub)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "${mainUrl}/filter?sort=0&keyword=${query}"
        } else {
            "${mainUrl}/filter?sort=0&keyword=${query}&page=$page"
        }

        val results = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            .select("div.film-list div.item")
            .mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val document = res.document
        val realUrl = res.url

        val title = document.selectFirst("h2.title")?.text()?.trim() 
            ?: document.selectFirst("h1.title")?.text()?.trim() 
            ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb img")?.attr("src"))
        val year = document.select("dl.meta dd")
            .firstOrNull { it.text().contains("20") || it.text().contains("19") }?.text()?.trim()
            ?.takeLast(4)?.toIntOrNull()
        val tags = document.select("dl.meta dd a[href*=/genre/]").map { it.text() }
        val rating = document.selectFirst("span#average-vote")?.text()?.trim()?.toDoubleOrNull()
        val duration = document.select("dl.meta dd").firstOrNull { it.text().contains("min/ep") }
            ?.text()?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull()
        val plot = document.selectFirst("div.desc")?.text()?.trim()
        val status = when (document.select("dl.meta dd a[href*=/status/]").text().trim()) {
            "Finito" -> ShowStatus.Completed
            "In corso" -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = document.select("ul.episodes li.episode a").mapNotNull {
            val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
            val epNum = it.attr("data-episode-num").toIntOrNull() ?: it.text().trim().toIntOrNull()
            newEpisode(epHref) {
                this.episode = epNum
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, realUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            this.plot = plot
            this.duration = duration
            this.showStatus = status
            this.recommendations = recommendations(document)
            rating?.let { this.score = Score.from10(it) }
        }
    }

    private fun recommendations(document: Document): List<SearchResponse> {
        return document.select("div.interesting div.item").mapNotNull {
            val onerititle = it.selectFirst("a.name")?.text() ?: return@mapNotNull null
            val onerihref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val oneriposter = fixUrlNull(it.selectFirst("img")?.attr("src"))
            newAnimeSearchResponse(onerititle, onerihref, TvType.Anime) { this.posterUrl = oneriposter }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AnimeWorld", "loadLinks URL = $data")

        val epDoc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
        var foundLinks = false

        // 1. data-id ile API üzerinden Grabber linki çek
        val epId = epDoc.selectFirst("ul.episodes li.episode a.active")?.attr("data-id")
            ?: epDoc.selectFirst("ul.episodes li.episode a")?.attr("data-id")

        if (!epId.isNullOrEmpty()) {
            val apiUrl = "${mainUrl}/api/episode/info?id=$epId&alt=0"
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to data,
                "X-Requested-With" to "XMLHttpRequest"
            )

            try {
                val response = app.get(apiUrl, headers = headers).parsedSafe<EpisodeInfo>()
                val grabberUrl = response?.grabber

                if (!grabberUrl.isNullOrEmpty()) {
                    if (grabberUrl.contains(".mp4") || grabberUrl.contains(".m3u8")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = grabberUrl,
                                type = if (grabberUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        foundLinks = true
                    } else {
                        loadExtractor(grabberUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeWorld", "API isteği başarısız: ${e.message}")
            }
        }

        // 2. Eğer API'den link gelmediyse Sayfadaki Iframe ve Player Linklerini tara (Fallback)
        if (!foundLinks) {
            val iframeSrc = fixUrlNull(epDoc.selectFirst("iframe#player-embed, iframe#player, div#player iframe")?.attr("src"))
            if (!iframeSrc.isNullOrEmpty()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                foundLinks = true
            } else {
                val downloadLink = fixUrlNull(epDoc.selectFirst("a#download-link, a.download-link")?.attr("href"))
                if (!downloadLink.isNullOrEmpty()) {
                    loadExtractor(downloadLink, data, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    data class EpisodeInfo(
        @JsonProperty("grabber") val grabber: String? = null
    )
}
