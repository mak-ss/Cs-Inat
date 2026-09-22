package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Headers
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1"
    )

    private suspend fun safeGet(url: String, referer: String = mainUrl): NiceResponse? {
        return try {
            val res = app.get(url, headers = browserHeaders, referer = referer)
            Log.d(name, "HTTP ${res.code} -> $url")
            res
        } catch (e: Exception) {
            Log.e(name, "safeGet hatası: ${e.message} -> $url")
            null
        }
    }

    private fun isCloudflareChallenge(body: String, headers: Headers?): Boolean {
        if (headers?.get("cf-mitigated") == "challenge") return true
        if (body.contains("challenges.cloudflare.com")) return true
        if (body.contains("_cf_chl_opt")) return true
        if (body.contains("Just a moment")) return true
        if (body.contains("Attention Required")) return true
        return false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        Log.d(name, "getMainPage URL: $url")

        return try {
            val response = safeGet(url) ?: return newHomePageResponse(emptyList())
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "getMainPage: Cloudflare challenge algılandı")
                return newHomePageResponse(emptyList())
            }

            val document = response.document

            val items: List<SearchResponse> = document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
                .take(60)

            Log.d(name, "getMainPage: ${items.size} öğe bulundu")
            if (items.isEmpty()) return newHomePageResponse(emptyList())

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}", e)
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"
        Log.d(name, "search URL: $url")

        return try {
            val response = safeGet(url) ?: return emptyList()
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "search: Cloudflare challenge algılandı")
                return emptyList()
            }

            response.document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load URL: $url")

        return try {
            val response = safeGet(url) ?: return null
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "load: Cloudflare challenge algılandı")
                return null
            }

            val document = response.document

            val type = when {
                url.contains("/movie/")  -> TvType.Movie
                url.contains("/film/")   -> TvType.Movie
                url.contains("/series/") -> TvType.TvSeries
                url.contains("/dizi/")   -> TvType.TvSeries
                url.contains("/anime/")  -> TvType.Anime
                else -> return null
            }

            val rawTitle = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val title = rawTitle.substringBefore("—").substringBefore(" - ").trim()

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

            val actors: List<ActorData> = document.select("section#cast-heading + div a.group").mapNotNull { element ->
                val actorName = element.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
                val image = element.selectFirst("img")?.attr("src")
                ActorData(Actor(actorName, image))
            }

            val trailerUrl: String? = document.selectFirst("iframe[src*=youtube]")?.attr("src")

            when (type) {
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, type, url) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { s -> Score.from10(s.toString()) }
                        this.actors = actors
                        if (trailerUrl != null) addTrailer(trailerUrl)
                    }
                }
                else -> {
                    val episodes = mutableListOf<Episode>()
                    val currentSeason = Regex("""/season/(\d+)""")
                        .find(url)?.groupValues?.get(1)?.toIntOrNull()

                    val episodeLinks = document
                        .select("aside a[href*='/season/'][href*='/episode/']")
                        .ifEmpty {
                            document.select("a[href*='/season/'][href*='/episode/']")
                        }

                    Log.d(name, "load: ${episodeLinks.size} bölüm linki bulundu")

                    episodeLinks.forEach { epEl ->
                        val href = epEl.attr("href")
                        if (href.isBlank()) return@forEach

                        val epUrl = if (href.startsWith("http")) href else "$mainUrl$href"

                        val sNum = Regex("""/season/(\d+)""")
                            .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        val eNum = Regex("""/episode/(\d+)""")
                            .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()

                        val epName = epEl.selectFirst("p")?.text()?.trim()
                            ?: epEl.text().trim().take(80)

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = sNum ?: currentSeason
                                this.episode = eNum
                            }
                        )
                    }

                    val uniqueEpisodes = episodes.distinctBy { ep -> ep.data }
                    Log.d(name, "load: ${uniqueEpisodes.size} tekil bölüm")

                    newTvSeriesLoadResponse(title, url, type, uniqueEpisodes) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { s -> Score.from10(s.toString()) }
                        this.actors = actors
                        if (trailerUrl != null) addTrailer(trailerUrl)
                    }
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
            val response = safeGet(data) ?: return false
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "loadLinks: Cloudflare challenge algılandı")
                return false
            }

            val document = response.document

            val playerId = document.selectFirst("[data-pv]")?.attr("data-pv")
            if (playerId.isNullOrEmpty()) {
                Log.e(name, "loadLinks: data-pv bulunamadı")
                return false
            }
            Log.d(name, "loadLinks: playerId = $playerId")

            val scriptSrc = document.selectFirst("script[src*='pilavyerplay']")?.attr("src")
                ?: "https://play2.pilavyerplay.top/assets/js/core.js"
            val playerHost = scriptSrc.substringBefore("/assets/")
            Log.d(name, "loadLinks: playerHost = $playerHost")

            val candidateUrls = listOf(
                "$playerHost/embed/$playerId",
                "$playerHost/player/$playerId",
                "$playerHost/v/$playerId",
                "$playerHost/e/$playerId",
                "$playerHost/?id=$playerId",
                "$playerHost/play/$playerId"
            )

            for (embedUrl in candidateUrls) {
                try {
                    Log.d(name, "loadLinks: Deneniyor -> $embedUrl")
                    val embedRes = app.get(
                        embedUrl,
                        headers = browserHeaders + mapOf(
                            "Accept" to "*/*",
                            "Sec-Fetch-Dest" to "iframe",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "cross-site"
                        ),
                        referer = data
                    )

                    if (embedRes.code !in 200..299) {
                        Log.d(name, "loadLinks: ${embedRes.code} -> $embedUrl")
                        continue
                    }

                    val embedBody = embedRes.text
                    val embedDoc = embedRes.document

                    val directStream = embedDoc.selectFirst("video source")?.attr("src")
                        ?: embedDoc.selectFirst("video")?.attr("src")
                        ?: embedDoc.selectFirst("source[src]")?.attr("src")

                    if (!directStream.isNullOrEmpty() && directStream.startsWith("http")) {
                        Log.d(name, "loadLinks: Doğrudan stream -> $directStream")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = directStream,
                                type = if (directStream.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }

                    embedDoc.selectFirst("iframe[src]")?.let { iframe ->
                        val iframeUrl = iframe.attr("src")
                        if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube")) {
                            Log.d(name, "loadLinks: iframe -> $iframeUrl")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = iframeUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
                    }

                    Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""")
                        .find(embedBody)?.let { m ->
                            val streamUrl = m.groupValues[1].replace("\\/", "/")
                            Log.d(name, "loadLinks: JS'den stream -> $streamUrl")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = streamUrl,
                                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                           else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }

                    Regex("""(?:source|file|src)\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""")
                        .find(embedBody)?.let { m ->
                            val streamUrl = m.groupValues[1].replace("\\/", "/")
                            Log.d(name, "loadLinks: pattern stream -> $streamUrl")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = streamUrl,
                                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                           else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }

                } catch (e: Exception) {
                    Log.e(name, "loadLinks: Deneme başarısız ($embedUrl): ${e.message}")
                }
            }

            Log.e(name, "loadLinks: Hiçbir kaynak bulunamadı (playerId=$playerId, host=$playerHost)")
            false
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}", e)
            false
        }
    }

    private fun Element.toCardSearchResponse(): SearchResponse? {
        val rawHref = this.attr("href")
        if (rawHref.isBlank()) return null

        val href = if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref"

        if (href.contains("/season/") || href.contains("/episode/")) return null

        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: this.selectFirst("p")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: return null

        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("data-lazy-src")

        val type = when {
            href.contains("/movie/")  -> TvType.Movie
            href.contains("/film/")   -> TvType.Movie
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/dizi/")   -> TvType.TvSeries
            href.contains("/anime/")  -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }
}
