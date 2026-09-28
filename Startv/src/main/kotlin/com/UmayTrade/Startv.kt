package com.UmayTrade

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

// Data classes for JSON-LD parsing
data class JsonLdSeries(
    val name: String?,
    val description: String?,
    val image: String?,
    val actor: List<JsonLdActor>?,
    val trailer: JsonLdTrailer?
)

data class JsonLdActor(val name: String?)
data class JsonLdTrailer(val embedUrl: String?)
data class JsonLdEpisode(val name: String?, val url: String?)
data class JsonLdItemList(val itemListElement: List<JsonLdListItem>?)
data class JsonLdListItem(val url: String?)
data class JsonLdGraph(
    @com.fasterxml.jackson.annotation.JsonProperty("@type") val type: String?,
    val name: String?,
    val description: String?,
    val image: Any?,
    val actor: List<JsonLdActor>?,
    val trailer: JsonLdTrailer?,
    val itemListElement: Any? // Can be a list of lists for episodes
)
data class JsonLdRoot(val graph: List<JsonLdGraph>?)

class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultPoster = "https://upload.wikimedia.org/wikipedia/commons/5/55/Star_TV.png"
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // 1. ANA SAYFA - Güncel Dizileri /dizi sayfasından çeker
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // Canlı Yayın
        homeCategories.add(
            HomePageList(
                "Canlı TV",
                listOf(newLiveSearchResponse("Star TV Canlı Yayın", "$mainUrl/canli-yayin", TvType.Live) { this.posterUrl = defaultPoster })
            )
        )

        // Tüm Diziler (/dizi sayfasından)
        val dizilerDoc = app.get("$mainUrl/dizi", headers = mapOf("User-Agent" to userAgent)).document
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

        return newHomePageResponse(homeCategories)
    }

    // 2. ARAMA MOTORU
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/arama?q=$query"
        val doc = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent)).document
        return doc.select("div.poster-card > a[href*=/dizi/]").mapNotNull { linkElement ->
            val href = linkElement.attr("href")
            val imgEl = linkElement.selectFirst("figure > img")
            val title = imgEl?.attr("alt")?.trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val poster = imgEl.attr("src").ifEmpty { imgEl.attr("data-src") }

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster) ?: defaultPoster
            }
        }.distinctBy { it.url }
    }

    // 3. DETAY SAYFASI VE BÖLÜMLER
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

        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val jsonLdScript = doc.selectFirst("script[type=application/ld+json]")?.html() ?: return null

        val jsonLdRoot = objectMapper.readValue(jsonLdScript, JsonLdRoot::class.java)
        val seriesInfo = jsonLdRoot.graph?.find { it.type == "TVSeries" }

        val title = seriesInfo?.name ?: doc.selectFirst("h1")?.text()?.trim() ?: "Star TV Dizisi"
        val description = seriesInfo?.description ?: doc.selectFirst("meta[name=description]")?.attr("content")
        
        val poster = when (val img = seriesInfo?.image) {
            is String -> img
            is List<*> -> img.firstOrNull() as? String
            else -> doc.selectFirst("meta[property=og:image]")?.attr("content")
        }

        // Bölümleri toplamak için daha sağlam bir yol: /bolumler sayfasına git.
        val episodes = mutableListOf<Episode>()
        val episodesPageUrl = "$url/bolumler"
        val episodesDoc = app.get(episodesPageUrl, headers = mapOf("User-Agent" to userAgent)).document
        
        val episodeElements = episodesDoc.select("div.video-card, div.poster-card")
        episodeElements.forEach { element ->
            val linkEl = element.selectFirst("a")
            val epHref = linkEl?.attr("href") ?: return@forEach
            if (!epHref.contains("/bolumler/")) return@forEach

            val imgEl = element.selectFirst("img")
            val epTitle = imgEl?.attr("alt")?.trim() ?: linkEl.text().trim()
            val epPoster = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") }
            
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
        
        // Eğer /bolumler sayfasından bölüm bulunamazsa, JSON-LD ItemList'i dene
        if (episodes.isEmpty()) {
             val itemList = seriesInfo?.itemListElement as? List<List<Map<String, String>>>
             itemList?.flatten()?.forEachIndexed { index, item ->
                 val epUrl = item["url"] ?: return@forEachIndexed
                 val epNum = Regex("""(\d+)-bolum""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                 episodes.add(newEpisode(epUrl) {
                     this.name = "$epNum. Bölüm"
                     this.season = 1
                     this.episode = epNum
                     this.posterUrl = fixUrlNull(poster) ?: defaultPoster
                 })
             }
        }

        if (episodes.isEmpty()) {
             episodes.add(newEpisode(url) { this.name = "$title - İzle" })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
            this.plot = description
            this.posterUrl = fixUrlNull(poster) ?: defaultPoster
        }
    }

    // 4. VİDEO ÇÖZÜMLEME
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        // Canlı yayın ise linki dinamik olarak bul
        if (data.contains("canli-yayin")) {
            val livePageDoc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
            val iframeUrl = livePageDoc.selectFirst("iframe#player-iframe, iframe[src*=player]")?.attr("src")
            if (!iframeUrl.isNullOrBlank()) {
                // Canlı yayın iframe'inin içinden m3u8 linkini bul
                val iframeDoc = app.get(iframeUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
                val streamUrl = m3u8Regex.find(iframeDoc.html())?.value
                
                if (!streamUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(source = this.name, name = "Star TV Canlı HD", url = streamUrl, type = ExtractorLinkType.M3U8) {
                            this.referer = data
                            this.headers = mapOf("User-Agent" to userAgent)
                            this.quality = Qualities.P1080.value
                        }
                    )
                    foundLinks = true
                }
            }
            if (foundLinks) return true
        }

        // Bölüm sayfasından video linklerini bul
        val episodeDoc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document

        // Ana yöntem: sayfadaki script içinden m3u8 veya mp4 linkini regex ile bul
        val videoUrlRegex = Regex("""(https?://[^\s"'<>]+?(?:\.m3u8|\.mp4)[^\s"'<>]*)""")
        val streamUrl = videoUrlRegex.find(episodeDoc.html())?.value

        if (!streamUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(source = this.name, name = "Star TV", url = streamUrl, type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4) {
                    this.referer = mainUrl
                    this.headers = mapOf("User-Agent" to userAgent)
                    this.quality = getQualityFromName(streamUrl)
                }
            )
            foundLinks = true
        }
        
        return foundLinks
    }
}
