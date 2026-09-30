// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

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
        val document = app.get(url).document

        val poolDataScript = document.selectFirst("script#poolData")?.data()

        val homePageList = mutableListOf<HomePageList>()

        if (poolDataScript != null) {
            val items = parsePoolData(poolDataScript)

            val filteredItems = when (request.data) {
                "cizgi" -> items.filter { it.type == "cizgi" }
                "anime" -> items.filter { it.type == "anime" }
                "dizi" -> items.filter { it.type == "dizi" }
                "film" -> items.filter { it.type == "film" }
                else -> items
            }

            if (filteredItems.isNotEmpty()) {
                homePageList.add(
                    HomePageList(
                        request.name,
                        filteredItems.map { it.toSearchResponse() }
                    )
                )
            }
        } else {
            val items = document.select("a.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, items))
            }
        }

        return newHomePageResponse(homePageList, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document

        val results = document.select("a.item").mapNotNull { it.toSearchResponse() }

        if (results.isEmpty()) {
            val poolDataScript = document.selectFirst("script#poolData")?.data()
            if (poolDataScript != null) {
                val items = parsePoolData(poolDataScript)
                return items.filter {
                    it.name.contains(query, ignoreCase = true)
                }.map { it.toSearchResponse() }
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

        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val genres = document.select("div.genres a").map { it.text() }

        val episodes = mutableListOf<Episode>()

        document.select("div.bolum-list a, div.episode-list a, a.bolum").forEach { element ->
            val episodeName = element.text()
            val episodeUrl = element.attr("href")
            if (episodeUrl.isNotEmpty()) {
                episodes.add(
                    newEpisode("$mainUrl$episodeUrl") {
                        this.name = episodeName
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "İzle"
                }
            )
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe, video source, a.video-link").forEach { element ->
            val videoUrl = when {
                element.tagName() == "iframe" -> element.attr("src")
                element.tagName() == "source" -> element.attr("src")
                else -> element.attr("href")
            }

            if (videoUrl.isNotEmpty()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        }

        return true
    }

    /**
     * HTML Element'ini SearchResponse'a çevirir.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.attr("data-name").ifEmpty {
            this.selectFirst("p.title")?.text() ?: return null
        }

        val poster = this.attr("data-poster").ifEmpty {
            this.selectFirst("img.poster-img")?.attr("src")
        }

        val type = this.attr("data-type")
        val tvType = when (type) {
            "film" -> TvType.Movie
            "dizi" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "cizgi" -> TvType.Cartoon
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, "$mainUrl$href", tvType) {
            this.posterUrl = poster
        }
    }

    /**
     * poolData JSON'unu parse eder ve doğrudan SearchResponse listesi döndürür.
     */
    private fun parsePoolData(jsonString: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val dataset = obj.optJSONObject("dataset")
                if (dataset != null) {
                    val name = dataset.optString("name", "")
                    val href = obj.optString("href", "")
                    val poster = obj.optString("poster", "")
                    val type = dataset.optString("type", "cizgi")

                    val tvType = when (type) {
                        "film" -> TvType.Movie
                        "dizi" -> TvType.TvSeries
                        "anime" -> TvType.Anime
                        "cizgi" -> TvType.Cartoon
                        else -> TvType.Movie
                    }

                    items.add(
                        newMovieSearchResponse(name, "$mainUrl$href", tvType) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }
}
