package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultPoster = "https://upload.wikimedia.org/wikipedia/commons/5/55/Star_TV.png"

    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        homeCategories.add(
            HomePageList(
                "Canlı TV",
                listOf(
                    newLiveSearchResponse(
                        "Star TV Canlı Yayın",
                        "$mainUrl/canli-yayin",
                        TvType.Live
                    ) {
                        this.posterUrl = defaultPoster
                    }
                )
            )
        )

        runCatching {
            val dizilerDoc = app.get("$mainUrl/dizi", headers = headers).document
            val dizilerList = dizilerDoc.select("div.poster-card > a[href*=/dizi/]").mapNotNull { linkElement ->
                val href = linkElement.attr("href")
                val imgEl = linkElement.selectFirst("figure > img")
                val title = imgEl?.attr("alt")?.trim() ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val poster = imgEl.attr("src").ifEmpty { imgEl.attr("data-src") }

                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            }.distinctBy { it.url }

            if (dizilerList.isNotEmpty()) {
                homeCategories.add(HomePageList("Güncel Diziler", dizilerList))
            }
        }

        return newHomePageResponse(homeCategories)
    }

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama?q=$query"
        return runCatching {
            val doc = app.get(searchUrl, headers = headers).document
            doc.select("div.poster-card > a[href*=/dizi/]").mapNotNull { linkElement ->
                val href = linkElement.attr("href")
                val imgEl = linkElement.selectFirst("figure > img")
                val title = imgEl?.attr("alt")?.trim() ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val poster = imgEl.attr("src").ifEmpty { imgEl.attr("data-src") }

                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            }.distinctBy { it.url }
        }.getOrElse { emptyList() }
    }

    // 3. DETAY
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("canli-yayin")) {
            return newLiveStreamLoadResponse(
                name = "Star TV Canlı",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = defaultPoster
            }
        }

        val doc = app.get(url, headers = headers).document

        val jsonLdScript = doc.selectFirst("script[type=application/ld+json]")?.html()
        var title = doc.selectFirst("h1")?.text()?.trim() ?: "Star TV Dizisi"
        var description = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: defaultPoster

        if (!jsonLdScript.isNullOrBlank()) {
            runCatching {
                val jsonObj = JSONObject(jsonLdScript)
                val graph = jsonObj.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) {
                        val item = graph.getJSONObject(i)
                        if (item.optString("@type") == "TVSeries") {
                            item.optString("name").takeIf { it.isNotBlank() }?.let { title = it }
                            item.optString("description").takeIf { it.isNotBlank() }?.let { description = it }
                            item.optString("image").takeIf { it.isNotBlank() }?.let { poster = it }
                            break
                        }
                    }
                }
            }
        }

        val episodes = mutableListOf<Episode>()
        val episodesPageUrl = "$url/bolumler"
        val episodesDoc = app.get(episodesPageUrl, headers = headers).document

        episodesDoc.select("div.video-card, div.poster-card").forEach { element ->
            val linkEl = element.selectFirst("a") ?: return@forEach
            val epHref = linkEl.attr("href")
            if (!epHref.contains("/bolumler/")) return@forEach

            val imgEl = element.selectFirst("img")
            val epTitle = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: linkEl.text().trim()
            val epPoster = imgEl?.attr("src")?.ifEmpty { imgEl?.attr("data-src") }

            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            val fullUrl = fixUrl(epHref)
            if (episodes.none { it.data == fullUrl }) {
                episodes.add(
                    newEpisode(fullUrl) {
                        this.name = epTitle
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = fixUrlNull(epPoster) ?: defaultPoster
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "$title - İzle"
                this.season = 1
                this.episode = 1
                this.posterUrl = fixUrlNull(poster) ?: defaultPoster
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
            this.plot = description
            this.posterUrl = fixUrlNull(poster) ?: defaultPoster
        }
    }

    // 4. VİDEO ÇÖZÜMLEME  ← BURASI DÜZELTİLDİ
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return StarTvExtractor().getUrl(data, subtitleCallback, callback)
    }
}
