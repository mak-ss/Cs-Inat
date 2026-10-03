package com.Blockades

import android.util.Log
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
    private val TAG = "NowTv"

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
        "$mainUrl/program-arsivi" to "Programlar"
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
        Log.d(TAG, "getMainPage: ${request.name} - ${request.data}")
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
        Log.d(TAG, "load: $url")
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
     * ADMPlayer.init çağrısından video_id (referenceId) ve video_code değerlerini çeker.
     */
    private data class PlayerData(val videoId: String?, val videoCode: String?)

    private fun extractPlayerData(document: Document): PlayerData {
        var videoId: String? = null
        var videoCode: String? = null

        for (script in document.select("script")) {
            val scriptData = script.data()
            if (!scriptData.contains("ADMPlayer.init")) continue

            if (videoId == null) {
                val refRegex = """referenceId\s*:\s*['"](\d+)['"]""".toRegex()
                videoId = refRegex.find(scriptData)?.groupValues?.get(1)
            }

            if (videoCode == null) {
                val codeRegex = """"video_code"\s*:\s*"([^"]+)"""".toRegex()
                videoCode = codeRegex.find(scriptData)?.groupValues?.get(1)
            }

            if (videoId == null) {
                val idRegex = """"id"\s*:\s*(\d+)""".toRegex()
                videoId = idRegex.find(scriptData)?.groupValues?.get(1)
            }

            if (videoId != null && videoCode != null) break
        }

        return PlayerData(videoId, videoCode)
    }

    /**
     * Sayfa HTML'inde erbvr.com domain'ine ait token'lı m3u8 linkini bulur.
     */
    private fun findErbvrM3u8(html: String): String? {
        val regex = """https?://[a-z0-9]+\.erbvr\.com/[^"'\s\\<>]*?\.m3u8[^"'\s\\<>]*""".toRegex()
        return regex.find(html)?.value?.replace("\\/", "/")
    }

    /**
     * Sayfa HTML'inde herhangi bir m3u8 linkini bulur (fallback).
     */
    private fun findAnyM3u8(html: String): String? {
        val regex = """https?://[^"'\s\\<>]+?\.m3u8[^"'\s\\<>]*""".toRegex()
        return regex.find(html)?.value?.replace("\\/", "/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks called with: $data")

        val document = app.get(data).document
        val html = document.html()

        // YÖNTEM 1: Sayfa kaynağında erbvr.com token'lı m3u8 linkini ara
        val erbvrUrl = findErbvrM3u8(html)
        if (!erbvrUrl.isNullOrBlank()) {
            Log.d(TAG, "Found erbvr m3u8 in page source: $erbvrUrl")
            M3u8Helper.generateM3u8(
                name,
                erbvrUrl,
                data,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            ).forEach(callback)
            return true
        }

        // YÖNTEM 2: Sayfa kaynağında herhangi bir m3u8 linki ara
        val anyM3u8 = findAnyM3u8(html)
        if (!anyM3u8.isNullOrBlank()) {
            Log.d(TAG, "Found generic m3u8 in page source: $anyM3u8")
            M3u8Helper.generateM3u8(
                name,
                anyM3u8,
                data,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            ).forEach(callback)
            return true
        }

        // YÖNTEM 3: ADMPlayer verisinden token'sız link kur (403 verebilir ama denemeye değer)
        val playerData = extractPlayerData(document)
        Log.d(TAG, "PlayerData: videoId=${playerData.videoId}, videoCode=${playerData.videoCode}")

        if (playerData.videoCode != null) {
            val path = data.removePrefix(mainUrl).trim('/')
            val normalizedPath = path.replace("/bolum/", "/bolumler/")
            val cdnUrl = "https://tdywsbbzdx.erbvr.com/$normalizedPath/${playerData.videoCode}.smil/playlist.m3u8"
            Log.d(TAG, "Trying token-less CDN URL (may 403): $cdnUrl")

            M3u8Helper.generateM3u8(
                name,
                cdnUrl,
                data,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl
                )
            ).forEach(callback)
            return true
        }

        // YÖNTEM 4: WebViewResolver ile dinamik m3u8 yakala
        Log.d(TAG, "Static methods failed, trying WebViewResolver...")
        try {
            val foundVideo = WebViewResolver(
                Regex("""\.m3u8""")
            ).resolveUsingWebView(
                requestCreator("GET", data, referer = mainUrl)
            ).first

            if (foundVideo != null) {
                val streamUrl = foundVideo.url.toString()
                Log.d(TAG, "WebViewResolver found: $streamUrl")
                M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    data,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "Origin" to mainUrl
                    )
                ).forEach(callback)
                return true
            } else {
                Log.e(TAG, "WebViewResolver returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebViewResolver error: ${e.message}")
        }

        Log.e(TAG, "No stream URL found for: $data")
        return false
    }
}
