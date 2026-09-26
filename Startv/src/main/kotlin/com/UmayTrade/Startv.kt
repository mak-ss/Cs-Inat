package com.UmayTrade

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Startv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    override var lang = "tr"
    override val hasMainPage = true

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // A) Canlı Yayın Bölümü
        val liveItem = newLiveSearchResponse("Star TV Canlı Yayın", "$mainUrl/canli-yayin", TvType.Live) {
            this.posterUrl = "https://www.startv.com.tr/assets/img/star-og-image.jpg"
        }
        homeCategories.add(HomePageList("Canlı TV", listOf(liveItem)))

        // B) Diziler Sayfası
        runCatching {
            val dizilerDoc = Jsoup.connect("$mainUrl/dizi").get()
            val dizilerList = dizilerDoc.select("div.card-series").mapNotNull { element ->
                val title = element.select("h3").text().ifEmpty { return@mapNotNull null }
                val href = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                newTvSeriesSearchResponse(title, mainUrl + href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            if (dizilerList.isNotEmpty()) {
                homeCategories.add(HomePageList("Diziler", dizilerList))
            }
        }

        // C) Programlar Sayfası
        runCatching {
            val programlarDoc = Jsoup.connect("$mainUrl/program").get()
            val programlarList = programlarDoc.select("div.card-series").mapNotNull { element ->
                val title = element.select("h3").text().ifEmpty { return@mapNotNull null }
                val href = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                newTvSeriesSearchResponse(title, mainUrl + href, TvType.TvSeries) {
                    this.posterUrl = poster
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
        val doc = Jsoup.connect(searchUrl).get()

        return doc.select("div.search-result-item").mapNotNull { element ->
            val title = element.select(".title").text().ifEmpty { return@mapNotNull null }
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")

            newTvSeriesSearchResponse(title, if (href.startsWith("http")) href else mainUrl + href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // 3. DETAY SAYFASI VE BÖLÜMLER
    override suspend fun load(url: String): LoadResponse {
        if (url.contains("canli-yayin")) {
            return newLiveStreamLoadResponse("Star TV Canlı", url, TvType.Live, url)
        }

        val doc = Jsoup.connect(url).get()
        val title = doc.select("h1.detail-title").text().ifEmpty { "Star TV Dizi" }
        val description = doc.select("div.detail-description").text()
        val poster = doc.select("div.detail-banner img").attr("src")

        val episodesUrl = if (url.endsWith("/bolumler")) url else "$url/bolumler"
        val episodes = runCatching {
            val episodesDoc = Jsoup.connect(episodesUrl).get()
            episodesDoc.select("div.episode-item").mapIndexed { index, element ->
                val epTitle = element.select(".ep-title").text().ifEmpty { "${index + 1}. Bölüm" }
                val epHref = element.select("a").attr("href")
                val epPoster = element.select("img").attr("src")

                newEpisode(mainUrl + epHref) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = epPoster
                }
            }
        }.getOrDefault(emptyList())

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    // 4. VİDEO LİNK YÜKLEME (GÜNCELLENMİŞ SİGNATURA)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Canlı Yayın Linki
        if (data.contains("canli-yayin")) {
            val liveM3u8 = "https://canli.startv.com.tr/startv/startv.m3u8"
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Star TV Canlı HD",
                    url = liveM3u8,
                    isM3u8 = true
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // Dizi / Program Bölümü Linki
        val doc = Jsoup.connect(data).get()
        val m3u8Url = doc.select("iframe.player-frame").attr("src")

        if (m3u8Url.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Star TV Bölüm Stream",
                    url = m3u8Url,
                    isM3u8 = true
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
