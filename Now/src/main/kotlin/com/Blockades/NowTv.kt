package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Suppress("unused")
class NowTv : MainAPI() {
    override var mainUrl = "https://www.nowtv.com.tr"
    override var name = "NOW TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class JsonLdItem(
        @JsonProperty("@type") val type: String? = null,
        val name: String? = null,
        val url: String? = null,
        val image: Any? = null,
        val description: String? = null,
        val actor: List<LdActor>? = null,
        val itemListElement: List<LdListItem>? = null,
        val episodeNumber: String? = null,
        val partOfSeason: String? = null
    )

    data class LdActor(val name: String? = null)

    data class LdListItem(val item: LdItemRef? = null)

    data class LdItemRef(
        @JsonProperty("@id") val id: String? = null,
        val name: String? = null,
        val image: Any? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-arsivi" to "Diziler",
        "$mainUrl/program-arsivi" to "Programlar",
        "$mainUrl/now-spor" to "Spor"
    )

    private fun getJsonLd(document: Document): List<JsonLdItem> {
        val jsonLdScripts = document.select("script[type=application/ld+json]")
        val items = mutableListOf<JsonLdItem>()
        for (script in jsonLdScripts) {
            try {
                val json = script.data().trim()
                if (json.startsWith("[")) {
                    items.addAll(jsonMapper.readValue<List<JsonLdItem>>(json))
                } else if (json.startsWith("{")) {
                    items.add(jsonMapper.readValue<JsonLdItem>(json))
                }
            } catch (_: Exception) {
                // Geçersiz JSON-LD bloğunu yoksay
            }
        }
        return items
    }

    private fun extractImageUrl(image: Any?): String? {
        return when (image) {
            is String -> fixImageUrl(image)
            is List<*> -> image.firstOrNull()?.let { extractImageUrl(it) }
            else -> null
        }
    }

    private fun fixImageUrl(image: String?): String? {
        if (image.isNullOrBlank()) return null
        return when {
            image.startsWith("//") -> "https:$image"
            image.startsWith("/") -> "$mainUrl$image"
            else -> image
        }
    }

    private fun mapToSearchResponse(item: JsonLdItem): SearchResponse? {
        val title = item.name ?: return null
        val href = item.url ?: item.itemListElement?.firstOrNull()?.item?.id ?: return null
        val poster = extractImageUrl(item.image)
            ?: extractImageUrl(item.itemListElement?.firstOrNull()?.item?.image)

        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    // HTML'den arama sonucu oluşturma
    private fun mapElementToSearchResponse(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a[href*='/izle']") ?: return null
        val href = linkElement.attr("href")
        val title = element.selectFirst(".program-name strong, .program-name")?.text()?.trim()
            ?: linkElement.text().trim()
        val poster = element.selectFirst("img")?.let {
            fixImageUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }

        if (title.isBlank() || href.isBlank()) return null

        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document

        // Önce JSON-LD'yi dene
        val jsonLdItems = getJsonLd(document)
        val showsFromJsonLd = jsonLdItems
            .flatMap { item ->
                val direct = if (item.type == "TVSeries" || item.type == "Movie") listOf(item) else emptyList()
                val fromList = item.itemListElement?.mapNotNull { it.item }?.map {
                    JsonLdItem(
                        type = "TVSeries",
                        name = it.name,
                        url = it.id,
                        image = it.image
                    )
                } ?: emptyList()
                direct + fromList
            }
            .mapNotNull { mapToSearchResponse(it) }
            .distinctBy { it.url }

        if (showsFromJsonLd.isNotEmpty()) {
            return newHomePageResponse(request.name, showsFromJsonLd)
        }

        // JSON-LD yoksa HTML'yi ayrıştır
        val showsFromHtml = document.select(".list-item, .poster").mapNotNull { mapElementToSearchResponse(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, showsFromHtml)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document

        // Arama sayfası da muhtemelen benzer bir yapıda, önce JSON-LD'yi dene
        val jsonLdItems = getJsonLd(document)
        val resultsFromJsonLd = jsonLdItems
            .filter { it.type == "TVSeries" || it.type == "Movie" }
            .mapNotNull { mapToSearchResponse(it) }
            .distinctBy { it.url }

        if (resultsFromJsonLd.isNotEmpty()) {
            return resultsFromJsonLd
        }

        // JSON-LD yoksa HTML'yi ayrıştır
        return document.select(".list-item, .poster").mapNotNull { mapElementToSearchResponse(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)

        // JSON-LD'de TVSeries veya TVEpisode ara
        val seriesInfo = jsonLdItems.find { it.type == "TVSeries" || it.type == "TVEpisode" }
        val title = seriesInfo?.name
            ?: document.selectFirst("h1, .program-name strong")?.text()?.trim()
            ?: return null
        val description = seriesInfo?.description
            ?: document.selectFirst(".program-desc, .desc p")?.text()?.trim()
        val poster = extractImageUrl(seriesInfo?.image)
            ?: document.selectFirst("img[src*='/i/thumbnail/']")?.let {
                fixImageUrl(it.attr("src"))
            }

        val episodes = mutableListOf<Episode>()

        // Bölümleri bulmak için birden fazla seçici dene
        val episodeSelectors = listOf(
            "section.videos:contains(BÖLÜMLER) .thumb a[href*='/bolum/']",
            ".sport-latest-thumbs .thumb a[href*='/bolum/']",
            ".list-item a[href*='/bolum/']",
            "a[href*='/bolum/']"
        )

        for (selector in episodeSelectors) {
            document.select(selector).forEach { element ->
                val epUrl = element.attr("href")
                var epTitle = element.selectFirst(".program-name strong, .thumb-meta .program-name, .desc")
                    ?.text()?.trim()
                if (epTitle.isNullOrBlank()) {
                    epTitle = element.selectFirst("img")?.attr("alt")?.trim()
                }
                if (epTitle.isNullOrBlank()) {
                    epTitle = element.text().trim()
                }

                val epImage = element.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

                if (epUrl.isNotBlank() && !epTitle.isNullOrBlank() &&
                    (epTitle.contains("bölüm", ignoreCase = true) || selector.contains("bolum"))
                ) {
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.posterUrl = fixImageUrl(epImage)
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) break // Bölüm bulunduysa diğer seçicileri deneme
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.plot = description
            this.posterUrl = poster
            this.tags = seriesInfo?.actor?.mapNotNull { it.name }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Doğrudan video kaynağını kontrol et (video source etiketi)
        val directVideoUrl = document.selectFirst("video source")?.attr("src")
        if (!directVideoUrl.isNullOrBlank() && directVideoUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                directVideoUrl,
                data,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }

        // 2. Sayfadaki JavaScript'ten video_code'u bul
        var videoCode: String? = null
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptData = script.data()
            // ADMPlayer.init çağrısındaki video nesnesini ara
            if (scriptData.contains("ADMPlayer.init")) {
                // video: {...} kısmını yakalamaya çalış
                val videoRegex = """video\s*:\s*(\{.*?\})""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = videoRegex.find(scriptData)
                if (match != null) {
                    val videoJson = match.groupValues[1]
                    try {
                        // Jackson ile parse et
                        val videoNode = jsonMapper.readTree(videoJson)
                        videoCode = videoNode.get("video_code")?.asText()
                        if (!videoCode.isNullOrBlank()) {
                            break
                        }
                    } catch (_: Exception) {
                        // JSON parse edilemezse devam et
                    }
                }
            }
        }

        // 3. Eğer video_code bulunduysa URL'yi oluştur
        if (!videoCode.isNullOrBlank()) {
            // URL yapısını sayfadaki örneklerden çıkarıyoruz.
            // Örnek: https://www.nowtv.com.tr/Gundem-Futbol/bolum/16
            // video_code: 20092026GUNDEMFUTBOL
            // Yeni URL: https://tdywsbbzdx.erbvr.com/Gundem-Futbol/bolum/16/20092026GUNDEMFUTBOL.smil/playlist.m3u8
            val path = data.removePrefix(mainUrl).removePrefix("/").removeSuffix("/")
            val constructedUrl = "https://tdywsbbzdx.erbvr.com/$path/$videoCode.smil/playlist.m3u8"

            M3u8Helper.generateM3u8(
                name,
                constructedUrl,
                data,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }

        // 4. Eğer yukarıdakiler çalışmazsa, sayfada m3u8 içeren başka bir kaynak var mı diye bak
        val possibleUrls = document.select("script").mapNotNull { script ->
            val scriptData = script.data()
            val m3u8Regex = """(https?://[^"']+\.m3u8[^"']*)""".toRegex()
            m3u8Regex.find(scriptData)?.value
        }.distinct()

        for (url in possibleUrls) {
            M3u8Helper.generateM3u8(
                name,
                url,
                data,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }

        return false
    }
}
