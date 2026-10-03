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

    // /ajax/stream yanıtı için veri sınıfı
    data class StreamResponse(
        val code: Int? = null,
        @JsonProperty("video_url") val videoUrl: String? = null
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

        val showsFromHtml = document.select(".list-item, .poster")
            .mapNotNull { mapElementToSearchResponse(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, showsFromHtml)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document

        val jsonLdItems = getJsonLd(document)
        val resultsFromJsonLd = jsonLdItems
            .filter { it.type == "TVSeries" || it.type == "Movie" }
            .mapNotNull { mapToSearchResponse(it) }
            .distinctBy { it.url }

        if (resultsFromJsonLd.isNotEmpty()) {
            return resultsFromJsonLd
        }

        return document.select(".list-item, .poster")
            .mapNotNull { mapElementToSearchResponse(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)

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
            if (episodes.isNotEmpty()) break
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.plot = description
            this.posterUrl = poster
            this.tags = seriesInfo?.actor?.mapNotNull { it.name }
        }
    }

    /**
     * Sayfadaki JavaScript'ten video_id (referenceId) çeker.
     * Örn: ADMPlayer.init({... referenceId: '136284', ...})
     */
    private fun extractVideoId(document: Document): String? {
        for (script in document.select("script")) {
            val scriptData = script.data()
            if (scriptData.contains("ADMPlayer.init")) {
                val regex = """referenceId\s*:\s*['"](\d+)['"]""".toRegex()
                val match = regex.find(scriptData)
                if (match != null) {
                    return match.groupValues[1]
                }
                // Alternatif: video: {..., "id": 136284, ...}
                val videoIdRegex = """"id"\s*:\s*(\d+)""".toRegex()
                val videoMatch = videoIdRegex.find(scriptData)
                if (videoMatch != null) {
                    return videoMatch.groupValues[1]
                }
            }
        }
        return null
    }

    /**
     * /ajax/stream endpoint'ine video_id gönderip gerçek m3u8 URL'sini (token'lı) alır.
     */
    private suspend fun fetchStreamUrl(videoId: String): String? {
        return try {
            val response = app.post(
                "$mainUrl/ajax/stream",
                data = mapOf("video_id" to videoId),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            ).text

            val parsed = jsonMapper.readValue<StreamResponse>(response)
            if (parsed.code == 200) parsed.videoUrl else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. YÖNTEM: Sayfadaki video_id'yi bul → /ajax/stream → gerçek m3u8 linkini al
        // Bu, token'lı (st=...&e=...&sid=...) URL'yi üretmenin tek güvenilir yoludur.
        val videoId = extractVideoId(document)
        if (videoId != null) {
            val streamUrl = fetchStreamUrl(videoId)
            if (!streamUrl.isNullOrBlank()) {
                M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    data,
                    headers = mapOf("Referer" to mainUrl)
                ).forEach(callback)
                return true
            }
        }

        // 2. YÖNTEM: Doğrudan video source etiketi (varsa)
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

        // 3. YÖNTEM: Sayfa kaynağında hazır m3u8 (token'lı) URL var mı?
        val m3u8Regex = """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex()
        for (script in document.select("script")) {
            val match = m3u8Regex.find(script.data())
            if (match != null) {
                M3u8Helper.generateM3u8(
                    name,
                    match.value,
                    data,
                    headers = mapOf("Referer" to mainUrl)
                ).forEach(callback)
                return true
            }
        }

        return false
    }
}
