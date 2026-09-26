package com.UmayTrade

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    override var lang = "tr"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val liveStreamUrl = "https://dogus.daioncdn.net/startv/startv_720p.m3u8?&sid=8sa1zezrv6wm&app=a20ac41e-bdc3-4aa1-934d-26b484480ac9&ce=3"
    private val defaultPoster = "https://upload.wikimedia.org/wikipedia/commons/5/55/Star_TV.png"

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // A) Canlı Yayın
        val liveItem = newLiveSearchResponse(
            "Star TV Canlı Yayın", 
            "$mainUrl/canli-yayin", 
            TvType.Live
        ) {
            this.posterUrl = defaultPoster
        }
        homeCategories.add(HomePageList("Canlı TV", listOf(liveItem)))

        // B) Diziler Sayfası
        runCatching {
            val dizilerDoc = Jsoup.connect("$mainUrl/dizi")
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            val dizilerList = dizilerDoc.select("div.card-series").mapNotNull { element ->
                val title = element.select("h3").text().ifEmpty { return@mapNotNull null }
                val href = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            }
            if (dizilerList.isNotEmpty()) {
                homeCategories.add(HomePageList("Diziler", dizilerList))
            }
        }

        // C) Programlar Sayfası
        runCatching {
            val programlarDoc = Jsoup.connect("$mainUrl/program")
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            val programlarList = programlarDoc.select("div.card-series").mapNotNull { element ->
                val title = element.select("h3").text().ifEmpty { return@mapNotNull null }
                val href = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            }
            if (programlarList.isNotEmpty()) {
                homeCategories.add(HomePageList("Programlar", programlarList))
            }
        }

        return newHomePageResponse(homeCategories)
    }

    // 2. ARAMA MOTORU
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama?q=$query"
        val doc = Jsoup.connect(searchUrl)
            .userAgent(userAgent)
            .ignoreContentType(true)
            .get()

        return doc.select("div.search-result-item").mapNotNull { element ->
            val title = element.select(".title").text().ifEmpty { return@mapNotNull null }
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster) ?: defaultPoster
            }
        }
    }

    // 3. DETAY SAYFASI
    override suspend fun load(url: String): LoadResponse {
        // Canlı yayın tespiti
        if (url.contains("canli-yayin") || url.contains("daioncdn") || url.contains(".m3u8")) {
            return newLiveStreamLoadResponse(
                name = "Star TV Canlı",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = defaultPoster
            }
        }

        // Dizi/Program Detayı
        val doc = Jsoup.connect(url)
            .userAgent(userAgent)
            .ignoreContentType(true)
            .get()

        val title = doc.select("h1.detail-title").text().ifEmpty { "Star TV" }
        val description = doc.select("div.detail-description").text()
        val poster = doc.select("div.detail-banner img").attr("src")

        val episodesUrl = if (url.endsWith("/bolumler")) url else "$url/bolumler"
        val episodes = runCatching {
            val episodesDoc = Jsoup.connect(episodesUrl)
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            episodesDoc.select("div.episode-item").mapIndexed { index, element ->
                val epTitle = element.select(".ep-title").text().ifEmpty { "${index + 1}. Bölüm" }
                val epHref = element.select("a").attr("href")
                val epPoster = element.select("img").attr("src")

                newEpisode(fixUrl(epHref)) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = fixUrlNull(epPoster) ?: defaultPoster
                }
            }
        }.getOrDefault(emptyList())

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.plot = description
            this.posterUrl = fixUrlNull(poster) ?: defaultPoster
        }
    }

    // 4. VİDEO LİNK YÜKLEME
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("canli-yayin") || data.contains("daioncdn") || data.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Star TV Canlı HD",
                    url = liveStreamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://www.startv.com.tr/"
                    this.headers = mapOf("User-Agent" to userAgent)
                    this.quality = Qualities.P720.value
                }
            )
            return true
        }

        runCatching {
            val doc = Jsoup.connect(data)
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            val m3u8Url = doc.select("iframe.player-frame").attr("src")

            if (m3u8Url.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Star TV Stream",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to userAgent)
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        return false
    }
}
