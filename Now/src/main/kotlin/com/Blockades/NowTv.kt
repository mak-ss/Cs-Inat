package com.Blockades

import android.content.Context
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

    /**
     * Plugin tarafından enjekte edilecek Android Context.
     */
    var appContext: Context? = null

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

    /**
     * Sabit dizi listesi. "Diziler" sekmesi için kullanılır.
     */
    private val featuredSeries = listOf(
        Triple("Anne Yarısı", "https://www.nowtv.com.tr/Anne-Yarisi/izle", "https://www.nowtv.com.tr/Anne-Yarisi/izle"),
        Triple("Sevdam Karadeniz", "https://www.nowtv.com.tr/Sevdam-Karadeniz/izle", "https://www.nowtv.com.tr/Sevdam-Karadeniz/izle"),
        Triple("Ömür Usta", "https://www.nowtv.com.tr/Omur-Usta/izle", "https://www.nowtv.com.tr/Omur-Usta/izle"),
        Triple("Yeraltı", "https://www.nowtv.com.tr/Yeralti/izle", "https://www.nowtv.com.tr/Yeralti/izle"),
        Triple("Halef: Köklerin Çağrısı", "https://www.nowtv.com.tr/Halef-Koklerin-Cagrisi/izle", "https://www.nowtv.com.tr/Halef-Koklerin-Cagrisi/izle")
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

        // "Diziler" sekmesi için sabit listeyi göster
        if (request.name == "Diziler") {
            val fixedList = featuredSeries.map { (title, url, _) ->
                newTvSeriesSearchResponse(title, url) {
                    this.posterUrl = null
                }
            }
            Log.d(TAG, "Returning ${fixedList.size} featured series")
            return newHomePageResponse(request.name, fixedList)
        }

        // Diğer sekmeler için normal HTML kazıma
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

    private fun findErbvrM3u8(html: String): String? {
        val regex = """https?://[a-z0-9]+\.erbvr\.com/[^"'\s\\<>]*?\.m3u8[^"'\s\\<>]*""".toRegex()
        return regex.find(html)?.value?.replace("\\/", "/")
    }

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

        // YÖNTEM 3: ADMPlayer verisinden token'sız link kur
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

        // YÖNTEM 4: Özel WebView拦截
        if (appContext != null) {
            Log.d(TAG, "Static methods failed, trying custom WebView interception...")
            val webViewUrl = interceptM3u8WithWebView(data)
            if (!webViewUrl.isNullOrBlank()) {
                Log.d(TAG, "Custom WebView found: $webViewUrl")
                M3u8Helper.generateM3u8(
                    name,
                    webViewUrl,
                    data,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "Origin" to mainUrl
                    )
                ).forEach(callback)
                return true
            } else {
                Log.e(TAG, "Custom WebView returned null")
            }
        } else {
            Log.w(TAG, "appContext null, WebView interception skipped.")
        }

        Log.e(TAG, "No stream URL found for: $data")
        return false
    }

    private suspend fun interceptM3u8WithWebView(pageUrl: String): String? {
        val context = appContext ?: run {
            Log.e(TAG, "appContext null, WebView oluşturulamıyor")
            return null
        }

        return try {
            val webView = android.webkit.WebView(context)
            var capturedUrl: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.contains(".m3u8") && capturedUrl == null) {
                        Log.d(TAG, "WebView intercepted: $url")
                        capturedUrl = url
                        latch.countDown()
                    }
                    return null
                }
            }

            webView.post { webView.loadUrl(pageUrl) }
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            webView.stopLoading()
            webView.destroy()

            capturedUrl
        } catch (e: Exception) {
            Log.e(TAG, "WebView interception error: ${e.message}")
            null
        }
    }
}
