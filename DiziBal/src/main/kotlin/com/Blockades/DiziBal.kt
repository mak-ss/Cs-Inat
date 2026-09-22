package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        "$mainUrl/animes" to "Animeler"
    )

    // Cloudflare tespit yardımcıları
    private fun isCloudflareChallenge(body: String, headers: Map<String, List<String>>): Boolean {
        if (headers["cf-mitigated"]?.firstOrNull() == "challenge") return true
        if (body.contains("challenges.cloudflare.com")) return true
        if (body.contains("_cf_chl_opt")) return true
        if (body.contains("cf-challenge")) return true
        if (body.contains("Just a moment")) return true
        if (body.contains("Attention Required")) return true
        return false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        Log.d(name, "getMainPage URL: $url")

        return try {
            val response = app.get(url)
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "getMainPage: Cloudflare challenge algılandı, host solver'a bırakılıyor")
                return null
            }

            val document = response.document
            val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
            Log.d(name, "getMainPage: ${items.size} öğe bulundu")

            if (items.isEmpty()) {
                Log.e(name, "getMainPage: öğe bulunamadı, HTML yapısı değişmiş olabilir")
                return null
            }

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"
        Log.d(name, "search URL: $url")

        return try {
            val response = app.get(url)
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "search: Cloudflare challenge algılandı")
                return emptyList()
            }

            val document = response.document
            val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
            Log.d(name, "search: ${items.size} sonuç bulundu")
            items
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load URL: $url")

        return try {
            val response = app.get(url)
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "load: Cloudflare challenge algılandı")
                return null
            }

            val document = response.document

            val type = when {
                url.contains("/movie/") -> TvType.Movie
                url.contains("/series/") -> TvType.TvSeries
                url.contains("/anime/") -> TvType.Anime
                else -> return null
            }

            val title = document.selectFirst("h1.font-display")?.text()?.trim()
                ?: return null

            val poster = document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            val plot = document.selectFirst("p.whitespace-pre-line")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")

            val year = document.select("div:contains(Yapım Yılı) dd")
                .firstOrNull()?.text()?.trim()?.toIntOrNull()

            val score = document.select("div:contains(IMDB Puanı) dd")
                .firstOrNull()?.text()?.replace("★", "")?.trim()?.toDoubleOrNull()

            val tags = document.select("div.flex.flex-wrap.gap-2 a.rounded-badge")
                .map { it.text().trim() }

            val actors = document.select("section#cast-heading + div a.group").mapNotNull {
                val actorName = it.selectFirst("p.text-\\[13px\\]")?.text()?.trim() ?: return@mapNotNull null
                val image = it.selectFirst("img")?.attr("src")
                Actor(actorName, image)
            }

            val trailer = document.selectFirst("iframe[src*=youtube]")?.attr("src")

            when (type) {
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, type, url) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { Score.from10(it.toString()) }
                        addActors(actors)
                        if (trailer != null) addTrailer(trailer)
                    }
                }
                else -> {
                    val episodes = mutableListOf<Episode>()

                    // Bölüm linklerini topla - birden fazla olası seçici dene
                    val episodeElements = document.select("div#bolumler a.group").ifEmpty {
                        document.select("a[href*='/bolum/']").ifEmpty {
                            document.select("a.group[href*='bolum']")
                        }
                    }

                    episodeElements.forEach { epElement ->
                        val epUrl = epElement.attr("href")
                        if (epUrl.isBlank()) return@forEach

                        val epName = epElement.selectFirst("p.text-sm")?.text()?.trim()
                            ?: epElement.selectFirst("h3")?.text()?.trim()
                            ?: epElement.text().trim()

                        val epInfo = epElement.selectFirst("p.text-xs")?.text()?.trim()
                        val (seasonNum, episodeNum) = parseEpisodeInfo(epInfo)

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = episodeNum
                        })
                    }

                    Log.d(name, "load: ${episodes.size} bölüm bulundu")

                    newTvSeriesLoadResponse(title, url, type, episodes) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { Score.from10(it.toString()) }
                        addActors(actors)
                        if (trailer != null) addTrailer(trailer)
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
            val response = app.get(data)
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "loadLinks: Cloudflare challenge algılandı, host solver'a bırakılıyor")
                return false
            }

            val document = response.document

            // data-pv attribute'una sahip elementi bul - birden fazla varyant dene
            val playerElement = document.selectFirst("[data-pv]")
                ?: document.selectFirst("[data-player]")
                ?: document.selectFirst("[data-video]")
                ?: document.selectFirst("[data-src]")

            val playerId = playerElement?.attr("data-pv")
                ?: playerElement?.attr("data-player")
                ?: playerElement?.attr("data-video")
                ?: playerElement?.attr("data-src")

            if (playerId.isNullOrEmpty()) {
                Log.e(name, "loadLinks: Player ID bulunamadı")

                // Alternatif: doğrudan iframe veya video kaynağı ara
                val directIframe = document.selectFirst("iframe[src]")
                if (directIframe != null) {
                    val iframeUrl = directIframe.attr("src")
                    if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube")) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = iframeUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
                return false
            }

            Log.d(name, "loadLinks: Player ID: $playerId")

            // Player ID ile embed URL oluştur - olası domainleri dene
            val possibleEmbedUrls = listOf(
                "https://pilavyerplay.top/embed/$playerId",
                "https://pilavyerplay.top/player/$playerId",
                "https://pilavyerplay.top/v/$playerId"
            )

            for (embedUrl in possibleEmbedUrls) {
                try {
                    Log.d(name, "loadLinks: Embed deneniyor: $embedUrl")
                    val embedResponse = app.get(embedUrl, referer = data)
                    val embedBody = embedResponse.text

                    if (isCloudflareChallenge(embedBody, embedResponse.headers)) {
                        Log.e(name, "loadLinks: Embed sayfasında Cloudflare challenge")
                        continue
                    }

                    val embedDoc = embedResponse.document

                    // Doğrudan video kaynağı
                    val streamUrl = embedDoc.selectFirst("video source")?.attr("src")
                        ?: embedDoc.selectFirst("video")?.attr("src")
                        ?: embedDoc.selectFirst("source[src]")?.attr("src")

                    if (!streamUrl.isNullOrEmpty()) {
                        Log.d(name, "loadLinks: Stream bulundu: $streamUrl")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = streamUrl,
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }

                    // iframe ara
                    val iframe = embedDoc.selectFirst("iframe[src]")
                    if (iframe != null) {
                        val iframeUrl = iframe.attr("src")
                        if (iframeUrl.isNotBlank()) {
                            Log.d(name, "loadLinks: iframe bulundu: $iframeUrl")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = iframeUrl,
                                    type = if (iframeUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
                    }

                    // JavaScript içinden m3u8/mp4 URL ara
                    val jsUrlRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""")
                    val jsMatch = jsUrlRegex.find(embedBody)
                    if (jsMatch != null) {
                        val jsUrl = jsMatch.groupValues[1]
                        Log.d(name, "loadLinks: JS içinden stream bulundu: $jsUrl")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = jsUrl,
                                type = if (jsUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }

                } catch (e: Exception) {
                    Log.e(name, "loadLinks: Embed denemesi başarısız ($embedUrl): ${e.message}")
                }
            }

            Log.e(name, "loadLinks: Hiçbir kaynak bulunamadı")
            false
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}", e)
            false
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("h2")?.text()?.trim()
            ?: this.selectFirst("p.font-semibold")?.text()?.trim()
            ?: return null

        val poster = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("img")?.attr("data-src")

        val type = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            href.contains("/film/") -> TvType.Movie
            href.contains("/dizi/") -> TvType.TvSeries
            else -> TvType.TvSeries
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun parseEpisodeInfo(info: String?): Pair<Int?, Int?> {
        if (info == null) return null to null
        val regex = Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""")
        val match = regex.find(info)
        return if (match != null) {
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            season to episode
        } else {
            null to null
        }
    }
}
