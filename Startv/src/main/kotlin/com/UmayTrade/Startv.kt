package com.UmayTrade

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    // 1. ANA SAYFA VE /dizi KAPSAMLI SCRABBER
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

        // Tüm Dizileri Çekme (/dizi)
        runCatching {
            val dizilerDoc = Jsoup.connect("$mainUrl/dizi")
                .userAgent(userAgent)
                .ignoreContentType(true)
                .get()

            val dizilerList = dizilerDoc.select(
                "a[href*=/dizi/], div.col-grid-item, div.swiper-slide, div.poster-card, div.card-series, a.card"
            ).mapNotNull { element ->
                val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*=/dizi/]") ?: element.selectFirst("a")
                val imgEl = element.selectFirst("img")
                
                val href = linkEl?.attr("href") ?: return@mapNotNull null
                if (href.contains("canli-yayin") || href == "/dizi" || href == "/dizi/") return@mapNotNull null

                val title = imgEl?.attr("alt")?.ifEmpty { imgEl.attr("title") }
                    ?.ifEmpty { element.select(".title, .card-title, h3, h4").text() }
                    ?.ifEmpty { element.text() } ?: return@mapNotNull null

                val poster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }

                newTvSeriesSearchResponse(title.trim(), fixUrl(href), TvType.TvSeries) {
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

        return doc.select("a[href*=/dizi/], div.col-grid-item, div.swiper-slide, div.poster-card, div.card-series, div.search-result-item").mapNotNull { element ->
            val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
            val imgEl = element.selectFirst("img")

            val href = linkEl?.attr("href") ?: return@mapNotNull null
            val title = imgEl?.attr("alt")?.ifEmpty { element.select(".title, h4").text() } ?: return@mapNotNull null
            val poster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }

            newTvSeriesSearchResponse(title.trim(), fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster) ?: defaultPoster
            }
        }.distinctBy { it.url }
    }

    // 3. TÜM BÖLÜMLERİ TARAYAN DERİN DETAY SCRAPERI
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

        // 1. JSON-LD Şema Taraması
        parseJsonLdEpisodes(doc, poster, episodes)

        // 2. /bolumler Sayfalarını ve Tüm Sayfalandırmayı (Pagination) Derinlemesine Tarama
        val baseUrlForEpisodes = if (url.contains("/bolumler")) url else "${url.removeSuffix("/")}/bolumler"
        
        var currentPageUrl: String? = baseUrlForEpisodes
        var pageCount = 1
        val maxPages = 15 // Aşırı döngüyü önlemek için güvenlik sınırı

        while (currentPageUrl != null && pageCount <= maxPages) {
            runCatching {
                val epDoc = if (pageCount == 1 && url.contains("/bolumler")) doc else Jsoup.connect(currentPageUrl)
                    .userAgent(userAgent)
                    .ignoreContentType(true)
                    .get()

                val foundOnPage = parseEpisodesFromDoc(epDoc, episodes)

                // Sonraki Sayfa Bağlantısını Bulma
                val nextPageEl = epDoc.select("a.pagination-next, a[rel=next], a:contains(Sonraki), a:contains(>)").first()
                val nextHref = nextPageEl?.attr("href")

                if (!nextHref.isNullOrEmpty() && foundOnPage > 0) {
                    currentPageUrl = fixUrl(nextHref)
                    pageCount++
                } else if (pageCount == 1 && foundOnPage > 0) {
                    // Sayfalama linki yoksa sayfa parametresi ile dene (?page=2)
                    currentPageUrl = "$baseUrlForEpisodes?page=2"
                    pageCount++
                } else {
                    currentPageUrl = null
                }
            }.onFailure {
                currentPageUrl = null
            }
        }

        // 3. Yedek: Eğer hiç bölüm bulunamadıysa ana sayfadaki videoyu ekle
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

        // Bölüm numarasına göre sırala ve tekrarları temizle
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.plot = description
            this.posterUrl = fixUrlNull(poster) ?: defaultPoster
        }
    }

    // Yardımcı: HTML dokümanından bölüm kartlarını toplar
    private fun parseEpisodesFromDoc(doc: Document, episodes: MutableList<Episode>): Int {
        var count = 0
        doc.select("a[href*=/bolumler/], div.col-grid-item, div.swiper-slide, div.episode-item, div.poster-card, div.card-series, div.video-card").forEach { element ->
            val linkEl = if (element.tagName() == "a") element else element.selectFirst("a")
            val imgEl = element.selectFirst("img")
            val epHref = linkEl?.attr("href") ?: return@forEach

            if (!epHref.contains("/bolumler/") || epHref.contains("fragman")) return@forEach

            val epTitle = imgEl?.attr("alt")?.ifEmpty { element.select("h4, .video-card-title, .title").text() }?.ifEmpty { element.text() } ?: ""
            val epPoster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }
            val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() 
                ?: (episodes.size + 1)

            val fullUrl = fixUrl(epHref)
            if (episodes.none { it.data == fullUrl }) {
                episodes.add(
                    newEpisode(fullUrl) {
                        this.name = if (epTitle.isNotBlank()) epTitle.trim() else "$epNum. Bölüm"
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = fixUrlNull(epPoster) ?: defaultPoster
                    }
                )
                count++
            }
        }
        return count
    }

    // Yardımcı: JSON-LD Şeması Üzerinden Bölüm Toplama
    private fun parseJsonLdEpisodes(doc: Document, defaultPoster: String, episodes: MutableList<Episode>) {
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
                                val epPoster = if (thumbArray != null && thumbArray.length() > 0) thumbArray.getString(0) else defaultPoster

                                if (epUrl.isNotBlank() && !epUrl.contains("fragman")) {
                                    val epNum = Regex("""(\d+)\.\s*Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
                                    val fullUrl = fixUrl(epUrl)
                                    if (episodes.none { it.data == fullUrl }) {
                                        episodes.add(
                                            newEpisode(fullUrl) {
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
