package com.UmayTrade

import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    
    override var lang = "tr"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val liveStreamUrl = "https://dogus.daioncdn.net/startv/startv_720p.m3u8?&sid=8sa1zezrv6wm&app=a20ac41e-bdc3-4aa1-934d-26b484480ac9&ce=3"
    private val defaultPoster = "https://upload.wikimedia.org/wikipedia/commons/5/55/Star_TV.png"

    // 1. ANA SAYFA VE DİZİLER LİSTESİ (/dizi)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // Canlı Yayın
        val liveItem = newLiveSearchResponse(
            "Star TV Canlı Yayın", 
            "$mainUrl/canli-yayin", 
            TvType.Live
        ) {
            this.posterUrl = defaultPoster
        }
        homeCategories.add(HomePageList("Canlı TV", listOf(liveItem)))

        // /dizi Bağlantısı Üzerinden Tüm Dizileri Scrape Etme
        runCatching {
            val dizilerDoc = Jsoup.connect("$mainUrl/dizi")
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            val dizilerList = dizilerDoc.select(
                "div.col-grid-item, div.swiper-slide, div.poster-card, div.card-series, a.card, div.card-item, div.grid-item"
            ).mapNotNull { element ->
                val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
                val imgEl = element.selectFirst("img")
                
                val title = imgEl?.attr("alt")?.ifEmpty { imgEl.attr("title") }
                    ?.ifEmpty { element.select(".title, .card-title, h3, h4").text() }
                    ?.ifEmpty { element.text() } ?: return@mapNotNull null

                val href = linkEl?.attr("href") ?: return@mapNotNull null
                val poster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }

                if (href.contains("canli-yayin")) null
                else newTvSeriesSearchResponse(title.trim(), fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            }.distinctBy { it.url }

            if (dizilerList.isNotEmpty()) {
                homeCategories.add(HomePageList("Tüm Diziler", dizilerList))
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

        return doc.select("div.col-grid-item, div.swiper-slide, div.poster-card, div.card-series, div.search-result-item").mapNotNull { element ->
            val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
            val imgEl = element.selectFirst("img")

            val title = imgEl?.attr("alt")?.ifEmpty { element.select(".title, h4").text() } ?: return@mapNotNull null
            val href = linkEl?.attr("href") ?: return@mapNotNull null
            val poster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }

            newTvSeriesSearchResponse(title.trim(), fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster) ?: defaultPoster
            }
        }.distinctBy { it.url }
    }

    // 3. DİZİ DETAYI VE BÖLÜMLER
    override suspend fun load(url: String): LoadResponse {
        if (url.contains("canli-yayin") || url.contains("daioncdn") || url.contains(".m3u8")) {
            return newLiveStreamLoadResponse(
                name = "Star TV Canlı",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = defaultPoster
            }
        }

        val doc = Jsoup.connect(url)
            .userAgent(userAgent)
            .ignoreContentType(true)
            .get()

        val title = doc.select("h1.detail-title, h1.title, h1").first()?.text()?.trim() ?: "Star TV Dizisi"
        val description = doc.select("div.news-body-content, div.detail-description, div.description, p.summary").text()
        val poster = doc.select("div.detail-banner img, img.poster, meta[property=og:image]").attr("src").ifEmpty {
            doc.select("meta[property=og:image]").attr("content")
        }

        val episodes = mutableListOf<Episode>()

        // JSON-LD Taraması
        val jsonLdElements = doc.select("script[type=application/ld+json]")
        for (element in jsonLdElements) {
            runCatching {
                val jsonText = element.html().trim()
                if (jsonText.startsWith("{")) {
                    val jsonObj = JSONObject(jsonText)
                    if (jsonObj.has("@graph")) {
                        val graphArray = jsonObj.getJSONArray("@graph")
                        for (i in 0 until graphArray.length()) {
                            val item = graphArray.getJSONObject(i)
                            if (item.optString("@type") == "VideoObject") {
                                val epName = item.optString("name")
                                val epUrl = item.optString("contentUrl").ifEmpty { item.optString("embedUrl") }
                                val epDesc = item.optString("description")
                                val thumbArray = item.optJSONArray("thumbnailUrl")
                                val epPoster = if (thumbArray != null && thumbArray.length() > 0) thumbArray.getString(0) else poster

                                if (epUrl.isNotBlank()) {
                                    val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                                    episodes.add(
                                        newEpisode(fixUrl(epUrl)) {
                                            this.name = epName
                                            this.description = epDesc
                                            this.season = 1
                                            this.episode = epNum
                                            this.posterUrl = fixUrlNull(epPoster) ?: defaultPoster
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // /bolumler Sayfası Taraması
        val episodesUrl = if (url.contains("/bolumler")) url else if (url.endsWith("/")) "${url}bolumler" else "$url/bolumler"
        runCatching {
            val episodesDoc = if (url.contains("/bolumler")) doc else Jsoup.connect(episodesUrl).userAgent(userAgent).ignoreContentType(true).get()

            episodesDoc.select("div.col-grid-item, div.swiper-slide, div.episode-item, div.poster-card, div.card-series, div.video-card").forEachIndexed { index, element ->
                val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
                val imgEl = element.selectFirst("img")
                val epHref = linkEl?.attr("href") ?: return@forEachIndexed

                if (!epHref.contains("/bolumler/") || epHref.contains("fragman")) return@forEachIndexed

                val epTitle = imgEl?.attr("alt")?.ifEmpty { element.select("h4, .video-card-title, .title").text() }?.ifEmpty { "${index + 1}. Bölüm" } ?: "${index + 1}. Bölüm"
                val epPoster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }
                val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle.trim()
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = fixUrlNull(epPoster) ?: defaultPoster
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "$title - İzle"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.plot = description
            this.posterUrl = fixUrlNull(poster) ?: defaultPoster
        }
    }

    // 4. M3U8 VİDEO ÇÖZÜMLEME
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

            var streamUrl = ""

            val iframeSrc = doc.select("iframe[src*=player], iframe[src*=video], iframe").attr("src")
            if (iframeSrc.isNotBlank()) {
                val iframeDoc = Jsoup.connect(fixUrl(iframeSrc))
                    .userAgent(userAgent)
                    .ignoreContentType(true)
                    .get()

                streamUrl = Regex("""(https?://[^\s"'<>]+?(?:\.m3u8|\.smil/?[^\s"'<>]*))""").find(iframeDoc.html())?.value ?: ""
            }

            if (streamUrl.isBlank()) {
                streamUrl = Regex("""(https?://[^\s"'<>]+?(?:mncdn|daioncdn)[^\s"'<>]+)""").find(doc.html())?.value ?: ""
            }

            if (streamUrl.isBlank()) {
                streamUrl = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)"*""").find(doc.html())?.value ?: ""
            }

            if (streamUrl.contains(".ts")) {
                streamUrl = streamUrl.replace(Regex("""/media_b\d+_\d+\.ts"""), "/playlist.m3u8")
            } else if (streamUrl.contains(".smil") && !streamUrl.contains(".m3u8")) {
                streamUrl = if (streamUrl.contains("?")) {
                    streamUrl.replace(".smil?", ".smil/playlist.m3u8?")
                } else {
                    "$streamUrl/playlist.m3u8"
                }
            }

            if (streamUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Star TV HD",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to "https://www.startv.com.tr"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        return false
    }
}
