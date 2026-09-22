package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl              = "https://dizibal.org"
    override var name                 = "DiziBal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes"  to "Animeler"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    private suspend fun safeGet(url: String, referer: String = mainUrl): Document? {
        return try {
            val res = app.get(url, headers = browserHeaders, referer = referer)
            if (isCloudflareChallenge(res.text)) {
                Log.e(name, "safeGet: Cloudflare challenge algılandı -> $url")
                null
            } else {
                res.document
            }
        } catch (e: Exception) {
            Log.e(name, "safeGet hatası: ${e.message} -> $url")
            null
        }
    }

    private fun isCloudflareChallenge(body: String): Boolean {
        return body.contains("challenges.cloudflare.com") || 
               body.contains("_cf_chl_opt") || 
               body.contains("Just a moment") || 
               body.contains("Attention Required")
    }

    private fun Element.toCardSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        val fullUrl = fixUrl(href)

        val title = this.selectFirst("h2, h3, .title, span")?.text()?.trim()
            ?: this.attr("title").ifEmpty { this.text() }.trim()
        if (title.isBlank()) return null

        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }?.let { fixUrl(it) }

        return newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        return try {
            val document = safeGet(url) ?: return newHomePageResponse(emptyList())

            val items: List<SearchResponse> = document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
                .take(60)

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"

        return try {
            val document = safeGet(url) ?: return emptyList()

            document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = safeGet(url) ?: return null
            val body = document.html()

            val type = when {
                url.contains("/movie/") || url.contains("/film/") -> TvType.Movie
                url.contains("/series/") || url.contains("/dizi/") -> TvType.TvSeries
                url.contains("/anime/") -> TvType.Anime
                else -> TvType.TvSeries
            }

            val rawTitle = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val title = rawTitle.substringBefore("—").substringBefore(" - ").substringBefore(" izle").trim()

            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img[src*='/storage/']")?.attr("src")

            val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("p.whitespace-pre-line")?.text()?.trim()

            val year = Regex(""""datePublished"\s*:\s*"(\d{4})""")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()

            val score = Regex("""IMDB Puanı[^0-9]*([0-9]+[.,][0-9]+)""")
                .find(body)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()

            val tags: List<String> = document.select("a[href*='/tur/']")
                .map { element -> element.text().trim() }
                .filter { tag -> tag.isNotBlank() }
                .distinct()

            val trailerUrl: String? = document.selectFirst("iframe[src*=youtube]")?.attr("src")

            if (type == TvType.TvSeries || type == TvType.Anime) {
                val episodes = mutableListOf<Episode>()
                document.select("a[href*='/season/'][href*='/episode/']").forEach { ep ->
                    val epUrl = fixUrl(ep.attr("href"))
                    val epName = ep.text().trim()
                    val sNum = Regex("""season/(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val eNum = Regex("""episode/(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = if (epName.isNotBlank()) epName else "$eNum. Bölüm"
                            this.season = sNum
                            this.episode = eNum
                        }
                    )
                }

                newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { it.data }) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.score = score?.let { Score.from10(it) }
                    this.tags = tags
                    addTrailer(trailerUrl)
                }
            } else {
                newMovieLoadResponse(title, url, type, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.score = score?.let { Score.from10(it) }
                    this.tags = tags
                    addTrailer(trailerUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(name, "load hatası: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks URL: $data")

        return try {
            val document = safeGet(data) ?: return false
            val body = document.html()
            var linkFound = false

            // 1. Pilavyer & Diğer iframe Kaynaklarını Çözümleme
            val iframeElements = document.select("iframe[src]")
            for (iframe in iframeElements) {
                val iframeUrl = fixUrl(iframe.attr("src"))
                
                if (iframeUrl.contains("pilavyerplay") || iframeUrl.contains("play2.pilavyerplay")) {
                    try {
                        val playerRes = app.get(iframeUrl, headers = mapOf(
                            "User-Agent" to browserHeaders["User-Agent"]!!,
                            "Referer" to data
                        )).text

                        // Embed JS içerisinden m3u8 linkini çekme
                        val m3u8Matches = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").findAll(playerRes)
                        for (match in m3u8Matches) {
                            val streamUrl = match.groupValues[1].replace("\\/", "/")
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - Player",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = iframeUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            linkFound = true
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Player iframe ayrıştırma hatası: ${e.message}")
                    }
                } else if (!iframeUrl.contains("youtube") && !iframeUrl.contains("googletagmanager")) {
                    // Genel Extractor desteği (varsa sistemdeki varsayılan extractor'ları çağırır)
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    linkFound = true
                }
            }

            // 2. Doğrudan HTML5 Video / Source kontrolü
            document.select("video source, video").forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("data-src") }
                if (src.isNotBlank() && src.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = src,
                            type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.P1080.value
                        }
                    )
                    linkFound = true
                }
            }

            // 3. Sayfa içi doğrudan M3U8 Regex Taraması
            val streamMatches = Regex("""(https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""").findAll(body)
            for (match in streamMatches) {
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                if (!streamUrl.contains("favicon")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.P1080.value
                        }
                    )
                    linkFound = true
                }
            }

            linkFound
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}", e)
            false
        }
    }
}
