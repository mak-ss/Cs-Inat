// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CizgiveDizi : MainAPI() {

    override var mainUrl = "https://cizgivedizi.com"
    override var name = "CizgiveDizi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        "cizgi" to "Çizgi Diziler",
        "anime" to "Animeler",
        "dizi" to "Diziler",
        "film" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/?page=$page"

        val okHttpClient = app.baseClient
        val httpRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        val response = okHttpClient.newCall(httpRequest).execute()
        val bodyString = response.body?.string()

        if (bodyString.isNullOrEmpty()) return null

        val document = org.jsoup.Jsoup.parse(bodyString, url)
        val poolDataScript = document.selectFirst("script#poolData")?.data()
        val homePageList = mutableListOf<HomePageList>()

        if (poolDataScript != null) {
            val items = parsePoolData(poolDataScript)
            val filteredItems = items.filter { pair ->
                pair.second == request.data
            }.map { pair ->
                pair.first
            }

            if (filteredItems.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, filteredItems))
            } else {
                homePageList.add(HomePageList(request.name, items.map { it.first }))
            }
        } else {
            val items = document.select("a.item").mapNotNull { element ->
                element.toSearchResponse()
            }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, items))
            }
        }

        return newHomePageResponse(homePageList, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document

        val results = document.select("a.item").mapNotNull { element ->
            element.toSearchResponse()
        }

        if (results.isEmpty()) {
            val poolDataScript = document.selectFirst("script#poolData")?.data()
            if (poolDataScript != null) {
                val items = parsePoolData(poolDataScript)
                return items.filter { pair ->
                    pair.first.name.contains(query, ignoreCase = true)
                }.map { it.first }
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Bilinmiyor"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.poster-img")?.attr("src")
        val finalPoster = fixPoster(poster)

        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val genres = document.select("div.genres a").map { it.text() }

        val tvType: TvType = when {
            url.contains("/film/") -> TvType.Movie
            url.contains("/dizi/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            url.contains("/cizgi/") -> TvType.Cartoon
            else -> TvType.TvSeries
        }

        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select("div.bolum-list a, div.episode-list a, a.bolum")

        episodeElements.forEach { element ->
            val episodeName = element.text()
            val episodeUrl = element.attr("href")
            if (episodeUrl.isNotEmpty()) {
                val fullEpisodeUrl = if (episodeUrl.startsWith("http")) episodeUrl
                                     else "$mainUrl$episodeUrl"
                episodes.add(
                    newEpisode(fullEpisodeUrl) {
                        this.name = episodeName
                    }
                )
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = finalPoster
                this.plot = description
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = finalPoster
                this.plot = description
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        val elements = document.select("iframe, video source, a.video-link")

        elements.forEach { element ->
            val videoUrl = when {
                element.tagName() == "iframe" -> element.attr("src")
                element.tagName() == "source" -> element.attr("src")
                else -> element.attr("href")
            }

            if (videoUrl.isNotEmpty()) {
                val fullUrl = if (videoUrl.startsWith("http")) videoUrl
                              else "$mainUrl$videoUrl"

                try {
                    loadExtractor(fullUrl, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (e: Exception) {
                    // yut
                }
            }
        }

        return found
    }

    private fun fixPoster(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.contains("wsrv.nl")) return url
        return if (url.contains(".avif", ignoreCase = true)) {
            "https://wsrv.nl/?url=${URLEncoder.encode(url, "UTF-8")}&output=jpg&w=500"
        } else {
            url
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.attr("data-name").ifEmpty {
            this.selectFirst("p.title")?.text() ?: return null
        }

        val poster = this.attr("data-poster").ifEmpty {
            this.selectFirst("img.poster-img")?.attr("src")
                ?: this.selectFirst("img")?.attr("src")
        }
        val finalPoster = fixPoster(poster)

        val type = this.attr("data-type")
        val tvType: TvType = when (type) {
            "film" -> TvType.Movie
            "dizi" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "cizgi" -> TvType.Cartoon
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, "$mainUrl$href", tvType) {
            this.posterUrl = finalPoster
        }
    }

    private fun parsePoolData(jsonString: String): List<Pair<SearchResponse, String>> {
        val items = mutableListOf<Pair<SearchResponse, String>>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val dataset = obj.optJSONObject("dataset")
                if (dataset != null) {
                    val name = dataset.optString("name", "")
                    val href = obj.optString("href", "")
                    val poster = obj.optString("poster", "")
                    val type = dataset.optString("type", "cizgi")

                    if (name.isEmpty() || href.isEmpty()) continue

                    val tvType: TvType = when (type) {
                        "film" -> TvType.Movie
                        "dizi" -> TvType.TvSeries
                        "anime" -> TvType.Anime
                        "cizgi" -> TvType.Cartoon
                        else -> TvType.Movie
                    }

                    val fullPosterUrl = if (poster.startsWith("http")) {
                        poster
                    } else if (poster.isNotEmpty()) {
                        "$mainUrl$poster"
                    } else {
                        null
                    }
                    val finalPosterUrl = fixPoster(fullPosterUrl)

                    val searchResponse = newMovieSearchResponse(
                        name,
                        if (href.startsWith("http")) href else "$mainUrl$href",
                        tvType
                    ) {
                        this.posterUrl = finalPosterUrl
                    }

                    items.add(Pair(searchResponse, type))
                }
            }
        } catch (e: Exception) {
            // yut
        }
        return items
    }
}
