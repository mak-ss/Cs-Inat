package com.UmayTrade

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class StarTVProvider : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    override var lang = "tr"
    override val hasMainPage = true

    // 1. ANA SAYFA: Canlı Yayın, Diziler ve Programlar
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // A) Canlı Yayın Bölümü
        val liveItem = SearchResponse(
            name = "Star TV Canlı Yayın",
            url = "$mainUrl/canli-yayin",
            apiName = this.name,
            type = TvType.Live,
            posterUrl = "https://www.startv.com.tr/assets/img/star-og-image.jpg"
        )
        homeCategories.add(HomePageList("Canlı TV", listOf(liveItem)))

        // B) Diziler Sayfasını Kazıma (Scrape)
        val dizilerDoc = Jsoup.connect("$mainUrl/dizi").get()
        val dizilerList = dizilerDoc.select("div.card-series").mapNotNull { element ->
            val title = element.select("h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
            
            TvSeriesSearchResponse(
                name = title,
                url = mainUrl + href,
                apiName = this.name,
                type = TvType.TvSeries,
                posterUrl = poster
            )
        }
        if (dizilerList.isNotEmpty()) {
            homeCategories.add(HomePageList("Diziler", dizilerList))
        }

        // C) Programlar Sayfasını Kazıma
        val programlarDoc = Jsoup.connect("$mainUrl/program").get()
        val programlarList = programlarDoc.select("div.card-series").mapNotNull { element ->
            val title = element.select("h3").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
            
            TvSeriesSearchResponse(
                name = title,
                url = mainUrl + href,
                apiName = this.name,
                type = TvType.TvSeries,
                posterUrl = poster
            )
        }
        if (programlarList.isNotEmpty()) {
            homeCategories.add(HomePageList("Programlar", programlarList))
        }

        return HomePageResponse(homeCategories)
    }

    // 2. ARAMA MOTORU: Arama Çubuğundan Dizi/Program Bulma
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama?q=$query"
        val doc = Jsoup.connect(searchUrl).get()

        return doc.select("div.search-result-item").mapNotNull { element ->
            val title = element.select(".title").text()
            val href = element.select("a").attr("href")
            val poster = element.select("img").attr("src")

            TvSeriesSearchResponse(
                name = title,
                url = if (href.startsWith("http")) href else mainUrl + href,
                apiName = this.name,
                type = TvType.TvSeries,
                posterUrl = poster
            )
        }
    }

    // 3. DETAY SAYFASI: Dizi Özeti ve Bölümlerin Listelenmesi
    override suspend fun load(url: String): LoadResponse {
        // Eğer seçilen sayfa Canlı Yayın ise:
        if (url.contains("canli-yayin")) {
            return LiveStreamLoadResponse(
                name = "Star TV Canlı",
                url = url,
                apiName = this.name,
                dataUrl = url
            )
        }

        // Dizi veya Program Detayı İse:
        val doc = Jsoup.connect(url).get()
        val title = doc.select("h1.detail-title").text()
        val description = doc.select("div.detail-description").text()
        val poster = doc.select("div.detail-banner img").attr("src")

        // Diziye ait bölümleri çekme (Örn: /dizi/yali-capkini/bolumler)
        val episodesUrl = if (url.endsWith("/bolumler")) url else "$url/bolumler"
        val episodesDoc = Jsoup.connect(episodesUrl).get()

        val episodes = episodesDoc.select("div.episode-item").mapIndexed { index, element ->
            val epTitle = element.select(".ep-title").text()
            val epHref = element.select("a").attr("href")
            val epPoster = element.select("img").attr("src")

            Episode(
                data = mainUrl + epHref,
                name = epTitle,
                season = 1,
                episode = index + 1,
                posterUrl = epPoster
            )
        }

        return TvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            episodes = episodes,
            plot = description,
            posterUrl = poster
        )
    }

    // 4. VİDEO BÖLÜM VEYA CANLI YAYIN LİNKİNİ OYNATICIYA VERME
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        handler: PlaylistUtils,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // A) Canlı Yayın Linki
        if (data.contains("canli-yayin")) {
            val liveM3u8 = "https://canli.startv.com.tr/startv/startv.m3u8"
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Star TV Canlı HD",
                    url = liveM3u8,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        // B) Dizi / Program Bölümü Linki (Sayfa içindeki Player/m3u8 parsing)
        val doc = Jsoup.connect(data).get()
        // Star TV'nin video player iframe veya script tag'inden .m3u8 adresini ayıklıyoruz
        val m3u8Url = doc.select("iframe.player-frame").attr("src") 
            // Veya script içerisindeki regex ile file: "..." bağlantısı çekilir

        if (m3u8Url.isNotEmpty()) {
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "Star TV Bölüm Stream",
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.FullHd.value,
                    isM3u8 = true
                )
            )
            return true
        }

        return false
    }
}
