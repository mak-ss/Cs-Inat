// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val document = app.get(url).document

        // poolData script'i içindeki JSON'u al
        val poolDataScript = document.selectFirst("script#poolData")?.data()
        val homePageList = mutableListOf<HomePageList>()

        if (!poolDataScript.isNullOrEmpty()) {
            val items = parsePoolData(poolDataScript)
            val filteredItems = items.filter { pair -> pair.second == request.data }.map { it.first }
            if (filteredItems.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, filteredItems))
            } else {
                homePageList.add(HomePageList(request.name, items.map { it.first }))
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
        val document = app.get("$mainUrl/arama?q=$query").document
        return document.select("a.item").mapNotNull { it.toSearchResponse() }
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

        // Tür tespiti
        val tvType: TvType = when {
            url.contains("/film/") -> TvType.Movie
            url.contains("/dizi/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            url.contains("/cizgi/") -> TvType.Cartoon
            else -> TvType.TvSeries
        }

        // ⚠️ KRİTİK: Bölümler div.ep-list içindeki div.ep-group > a.ep-card
        // Ama script type="text/plain" içinde SAKLI olanlar da var, onları da al!
        val episodes = mutableListOf<Episode>()

        // 1) Doğrudan DOM'daki bölümler (aktif sezon)
        document.select("div.ep-list a.ep-card, div.ep-group a.ep-card").forEach { element ->
            val epUrl = element.attr("href")
            val epName = element.selectFirst("div.ep-title span")?.text()
                ?: element.selectFirst(".ep-title")?.text()?.trim()
                ?: element.text().take(80)

            if (epUrl.isNotEmpty()) {
                val fullUrl = if (epUrl.startsWith("http")) epUrl else "$mainUrl$epUrl"
                episodes.add(newEpisode(fullUrl) { this.name = epName })
            }
        }

        // 2) script type="text/plain" içindeki gizli bölümler
        document.select("script[type=text/plain]").forEach { script ->
            val rawHtml = script.data()
            if (rawHtml.contains("ep-card")) {
                val inner = org.jsoup.Jsoup.parse(rawHtml)
                inner.select("a.ep-card").forEach { element ->
                    val epUrl = element.attr("href")
                    val epName = element.selectFirst("div.ep-title span")?.text()
                        ?: element.selectFirst(".ep-title")?.text()?.trim()
                        ?: element.text().take(80)
                    if (epUrl.isNotEmpty()) {
                        val fullUrl = if (epUrl.startsWith("http")) epUrl else "$mainUrl$epUrl"
                        // Aynı URL zaten eklenmediyse
                        if (episodes.none { it.data == fullUrl }) {
                            episodes.add(newEpisode(fullUrl) { this.name = epName })
                        }
                    }
                }
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = fixPoster(poster)
                this.plot = description
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = fixPoster(poster)
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

        // HTML'de görünen iframe'ler
        document.select("iframe[src], video source[src], a.video-link").forEach { element ->
            val videoUrl = when {
                element.tagName() == "iframe" -> element.attr("src")
                element.tagName() == "source" -> element.attr("src")
                else -> element.attr("href")
            }
            if (videoUrl.isNotEmpty()) {
                val fullUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl"
                try {
                    loadExtractor(fullUrl, mainUrl, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        }

        // ⚠️ Bu sitede video kaynakları AJAX ile yükleniyor:
        // /diziyeni/video_sources.php?s=<dizi>&b=<bolum>
        // Bölüm URL'sinden dizi ve bölüm ID'sini çıkar
        // Örn: /dizi/sb/sungerbob_karepantolon/1/eleman_araniyor...
        val regex = Regex("""/dizi/([^/]+)/[^/]+/([^/]+)""")
        val match = regex.find(data)
        if (match != null) {
            val diziId = match.groupValues[1]
            val bolumId = match.groupValues[2]
            val apiUrl = "$mainUrl/diziyeni/video_sources.php?s=$diziId&b=$bolumId"
            try {
                val apiResponse = app.get(apiUrl).text
                val json = org.json.JSONObject(apiResponse)
                if (json.optBoolean("ok")) {
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val src = sources.getJSONObject(i)
                            val embed = src.optString("embed", "")
                            if (embed.isNotEmpty()) {
                                try {
                                    loadExtractor(embed, mainUrl, subtitleCallback, callback)
                                    found = true
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return found
    }

    private fun fixPoster(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.contains("wsrv.nl")) return url
        return if (url.contains(".avif", ignoreCase = true)) {
            "https://wsrv.nl/?url=${URLEncoder.encode(url, "UTF-8")}&output=jpg&w=500"
        } else url
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null
        val title = this.attr("data-name").ifEmpty {
            this.selectFirst("p.title")?.text() ?: return null
        }
        val poster = this.attr("data-poster").ifEmpty {
            this.selectFirst("img.poster-img")?.attr("src") ?: this.selectFirst("img")?.attr("src")
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
            this.posterUrl = fixPoster(poster)
        }
    }

    private fun parsePoolData(jsonString: String): List<Pair<SearchResponse, String>> {
        val items = mutableListOf<Pair<SearchResponse, String>>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val dataset = obj.optJSONObject("dataset") ?: continue
                val name = dataset.optString("name", "")
                val href = obj.optString("href", "")
                val poster = obj.optString("poster", "")
                val type = dataset.optString("type", "cizgi")
                if (name.isEmpty() || href.isEmpty()) continue

                val tvType = when (type) {
                    "film" -> TvType.Movie
                    "dizi" -> TvType.TvSeries
                    "anime" -> TvType.Anime
                    "cizgi" -> TvType.Cartoon
                    else -> TvType.Movie
                }
                val fullPosterUrl = if (poster.startsWith("http")) poster
                    else if (poster.isNotEmpty()) "$mainUrl$poster" else null
                val searchResponse = newMovieSearchResponse(
                    name, if (href.startsWith("http")) href else "$mainUrl$href", tvType
                ) { this.posterUrl = fixPoster(fullPosterUrl) }

                items.add(Pair(searchResponse, type))
            }
        } catch (_: Exception) {}
        return items
    }
}
