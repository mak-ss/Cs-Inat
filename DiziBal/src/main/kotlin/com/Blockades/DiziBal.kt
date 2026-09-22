package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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

    // Tarayıcı benzeri başlıklar — Cloudflare için kritik
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

    // Güvenli GET — HTTP kodunu loglar, Cloudflare tespitini döner
    private suspend fun safeGet(url: String, referer: String = mainUrl): Response? {
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
        if (body.contains("cf-challenge")) return true
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
            val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
            Log.d(name, "getMainPage: ${items.size} öğe bulundu")

            if (items.isEmpty()) {
                Log.e(name, "getMainPage: öğe bulunamadı, HTML yapısı değişmiş olabilir")
                return newHomePageResponse(emptyList())
            }

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

            val title = document.selectFirst("h1.font-display")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null

            val poster = document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            val plot = document.selectFirst("p.whitespace-pre-line")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")

            val year = document.selectFirst("div:contains(Yapım Yılı) dd")?.text()?.trim()?.toIntOrNull()
                ?: document.selectFirst("div:contains(Yıl) dd")?.text()?.trim()?.toIntOrNull()

            val score = document.selectFirst("div:contains(IMDB Puanı) dd")
                ?.text()?.replace("★", "")?.trim()?.toDoubleOrNull()

            val tags = document.select("div.flex.flex-wrap.gap-2 a.rounded-badge")
                .map { it.text().trim() }

            val actors = document.select("section#cast-heading + div a.group").mapNotNull {
                val actorName = it.selectFirst("p.text-\\[13px\\]")?.text()?.trim()
                    ?: return@mapNotNull null
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
                            ?: epElement.text().trim()

                        val (seasonNum, episodeNum) = parseEpisodeInfo(epInfo)
                            ?: parseEpisodeFromName(epName)

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
            val response = safeGet(data, referer = mainUrl) ?: return false
            val body = response.text

            if (isCloudflareChallenge(body, response.headers)) {
                Log.e(name, "loadLinks: Cloudflare challenge algılandı")
                return false
            }

            val document = response.document

            // 1) Player ID'yi bul — birden fazla öznitelik denenir
            val playerId = listOf(
                "[data-pv]", "[data-player]", "[data-video]", "[data-src]",
                "[data-id]", "[data-hash]"
            ).firstNotNullOfOrNull { selector ->
                document.selectFirst(selector)?.let { el ->
                    el.attr("data-pv").ifBlank { null }
                        ?: el.attr("data-player").ifBlank { null }
                        ?: el.attr("data-video").ifBlank { null }
                        ?: el.attr("data-src").ifBlank { null }
                        ?: el.attr("data-id").ifBlank { null }
                        ?: el.attr("data-hash").ifBlank { null }
                }
            }

            // 2) Eğer player ID yoksa doğrudan iframe dene
            if (playerId.isNullOrEmpty()) {
                Log.e(name, "loadLinks: Player ID bulunamadı, iframe deneniyor")

                document.select("iframe[src]").forEach { iframe ->
                    val iframeUrl = iframe.attr("src")
                    if (iframeUrl.isNotBlank() && !iframeUrl.contains("youtube")) {
                        Log.d(name, "loadLinks: Doğrudan iframe bulundu: $iframeUrl")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = iframeUrl,
                                type = if (iframeUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
                return true
            }

            Log.d(name, "loadLinks: Player ID: $playerId")

            // 3) Bilinen embed URL şablonlarını dene
            val possibleEmbedUrls = listOf(
                "https://pilavyerplay.top/embed/$playerId",
                "https://pilavyerplay.top/player/$playerId",
                "https://pilavyerplay.top/v/$playerId",
                "https://pilavyerplay.top/e/$playerId"
            )

            for (embedUrl in possibleEmbedUrls) {
                try {
                    Log.d(name, "loadLinks: Embed deneniyor: $embedUrl")
                    val embedResponse = safeGet(embedUrl, referer = data) ?: continue
                    val embedBody = embedResponse.text

                    if (isCloudflareChallenge(embedBody, embedResponse.headers)) {
                        Log.e(name, "loadLinks: Embed sayfasında Cloudflare challenge")
                        continue
                    }

                    val embedDoc = embedResponse.document

                    // Video/source etiketi
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
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }

                    // iframe
                    embedDoc.selectFirst("iframe[src]")?.let { iframe ->
                        val iframeUrl = iframe.attr("src")
                        if (iframeUrl.isNotBlank()) {
                            Log.d(name, "loadLinks: iframe bulundu: $iframeUrl")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = iframeUrl,
                                    type = if (iframeUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                           else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
                    }

                    // JS içinden m3u8/mp4 yakala
                    val jsRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""")
                    jsRegex.find(embedBody)?.let { match ->
                        val jsUrl = match.groupValues[1]
                        Log.d(name, "loadLinks: JS içinden stream bulundu: $jsUrl")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = jsUrl,
                                type = if (jsUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                       else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }

                    // Base64/obfuscated kaynakları da dene
                    val b64Regex = Regex("""source:\s*['"]([^'"]+)['"]""")
                    b64Regex.find(embedBody)?.let { match ->
                        val src = match.groupValues[1].replace("\\/", "/")
                        if (src.startsWith("http")) {
                            Log.d(name, "loadLinks: source: pattern bulundu: $src")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = src,
                                    type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8
                                           else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            return true
                        }
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

    // Birden fazla format desteği: "1. Sezon 5. Bölüm", "S01E05", "1x05"
    private fun parseEpisodeInfo(info: String?): Pair<Int?, Int?>? {
        if (info.isNullOrBlank()) return null

        // "1. Sezon 5. Bölüm"
        Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)
            .find(info)?.let {
                return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            }

        // "S01E05" veya "s1e5"
        Regex("""[Ss](\d+)\s*[Ee](\d+)""").find(info)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }

        // "1x05"
        Regex("""(\d+)\s*[xX]\s*(\d+)""").find(info)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }

        // "5. Bölüm" (sezon yok)
        Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(info)?.let {
            return null to it.groupValues[1].toIntOrNull()
        }

        return null
    }

    // Bölüm adından sezon/bölüm çıkarmayı dene
    private fun parseEpisodeFromName(name: String): Pair<Int?, Int?> {
        return parseEpisodeInfo(name) ?: (null to null)
    }
}
