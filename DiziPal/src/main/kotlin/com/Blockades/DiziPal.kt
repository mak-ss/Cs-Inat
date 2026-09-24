// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal10.com.tr/"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override var sequentialMainPage = true

    // ─────────────────────────────────────────────────────────────
    //  CLOUDFLARE BYPASS HEADER'LARI
    // ─────────────────────────────────────────────────────────────
    private val defaultHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                             "AppleWebKit/537.36 (KHTML, like Gecko) " +
                             "Chrome/122.0.0.0 Safari/537.36",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;" +
                             "q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT"             to "1",
        "Connection"      to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"  to "document",
        "Sec-Fetch-Mode"  to "navigate",
        "Sec-Fetch-Site"  to "none",
        "Sec-Fetch-User"  to "?1"
    )

    private val ajaxHeaders = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                              "AppleWebKit/537.36 (KHTML, like Gecko) " +
                              "Chrome/122.0.0.0 Safari/537.36",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language"  to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest"   to "empty",
        "Sec-Fetch-Mode"   to "cors",
        "Sec-Fetch-Site"   to "same-origin"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                        to "Son Bölümler",
        "${mainUrl}/diziler"                         to "Yeni Diziler",
        "${mainUrl}/filmler"                         to "Yeni Filmler",
        "${mainUrl}/animeler"                        to "Animeler",
        "${mainUrl}/platform/netflix"                to "Netflix",
        "${mainUrl}/platform/exxen"                  to "Exxen",
        "${mainUrl}/platform/blutv"                  to "BluTV",
        "${mainUrl}/platform/disney"                 to "Disney+",
        "${mainUrl}/platform/prime-video"            to "Amazon Prime",
        "${mainUrl}/platform/tabii"                  to "Tabii",
        "${mainUrl}/platform/gain"                   to "Gain",
        "${mainUrl}/platform/hbomax"                 to "HBOMax",
    )

    // ─────────────────────────────────────────────────────────────
    //  ANA SAYFA
    // ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            url = request.data,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val home = when {
            // Son Bölümler
            request.data.contains("/bolumler") ->
                document.select("div.episodes-grid-home > a.episode-row-home")
                    .mapNotNull { it.sonBolumler() }

            // Diziler / Filmler / Anime / Platform
            else ->
                document.select("div.homepage-grid > a.homepage-card")
                    .mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    // ─────────────────────────────────────────────────────────────
    //  ELEMENT PARSER — SON BÖLÜMLER
    // ─────────────────────────────────────────────────────────────
    private fun Element.sonBolumler(): SearchResponse? {
        val name   = this.selectFirst(".episode-row-title")?.text()?.trim() ?: return null
        val detail = this.selectFirst(".episode-row-detail")?.text()?.trim() ?: ""

        val episode = detail.replace(
            Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm"""),
            "$1x$2"
        )
        val title = if (episode.isNotEmpty()) "$name $episode" else name

        val href = fixUrlNull(this.attr("href")) ?: return null

        val imgElement = this.selectFirst(".episode-row-thumb img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        )

        // /anime/xxx/1-sezon/1-bolum → /anime/xxx
        val seriesUrl = href.replace(Regex("""/\d+-sezon/\d+-bolum.*$"""), "")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ELEMENT PARSER — DİZİ/FİLM/ANİME KARTI
    // ─────────────────────────────────────────────────────────────
    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return null
        val href  = fixUrlNull(this.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl  = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        )
        val year = this.selectFirst("time.meta-year")?.text()?.trim()?.toIntOrNull()

        val badge = this.selectFirst(".homepage-card-badges .badge")?.text()?.trim() ?: ""

        return when {
            badge.equals("ANİME", ignoreCase = true) || href.contains("/anime/") ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                    this.year      = year
                }

            badge.equals("FİLM", ignoreCase = true) || href.contains("/film/") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year      = year
                }

            else ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year      = year
                }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  JSON RESULT → SEARCH RESPONSE
    // ─────────────────────────────────────────────────────────────
    private fun DizipalSearchResult.toPostSearchResult(): SearchResponse? {
        val title = this.title ?: return null
        val href  = this.url ?: return null

        return when {
            href.contains("/anime/") ->
                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = this@toPostSearchResult.poster
                    this.year      = this@toPostSearchResult.year
                }

            this.type.equals("Film", ignoreCase = true) || href.contains("/film/") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = this@toPostSearchResult.poster
                    this.year      = this@toPostSearchResult.year
                }

            else ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = this@toPostSearchResult.poster
                    this.year      = this@toPostSearchResult.year
                }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ARAMA
    // ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"

        val responseRaw = app.get(
            url = searchUrl,
            headers = ajaxHeaders,
            referer = "$mainUrl/"
        )

        val body = responseRaw.text.trim()
        if (body.isEmpty()) return emptyList()

        // 1) JSON dene
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                val jsonResponse = AppUtils.parseJson<DizipalSearchData>(body)
                val list = mutableListOf<SearchResponse>()
                jsonResponse.results?.forEach { item ->
                    item.toPostSearchResult()?.let { list.add(it) }
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                Log.w("DZP", "JSON parse başarısız: ${e.message}")
            }
        }

        // 2) HTML fallback
        return parseHtmlSearch(body)
    }

    private fun parseHtmlSearch(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select(
            "a.homepage-card, a.search-result-item, li.search-result, " +
            "a.similar-card, a.trend-card"
        ).mapNotNull { el ->
            val title = el.selectFirst(
                "h3.homepage-card-title, h3.trend-card-title, " +
                ".similar-card-title, h3, .title"
            )?.text()?.trim() ?: return@mapNotNull null

            val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val img  = el.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })

            val year = el.selectFirst("time.meta-year, .similar-card-year, .grid-card-year")
                ?.text()?.trim()?.toIntOrNull()

            when {
                href.contains("/anime/") -> newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = poster; this.year = year
                }
                href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster; this.year = year
                }
                else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster; this.year = year
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────────────────────────────────────────
    //  DETAY SAYFASI (LOAD)
    // ─────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        // Bölüm URL'si ise ana diziye yönlendir
        val bolumMatch = Regex("""/\d+-sezon/\d+-bolum""").find(url)
        if (bolumMatch != null) {
            val seriesUrl = url.substringBefore(bolumMatch.value)
            Log.d("DZP", "Bölüm URL → dizi URL: $seriesUrl")
            return load(seriesUrl)
        }

        val document = app.get(
            url = url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")
            ?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("time.meta-year, .watch-mini-hero-year")
                ?.text()?.trim()?.toIntOrNull()

        val description = document
            .selectFirst("p.series-description, meta[property=og:description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()

        val tags = document
            .select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }

        val duration: Int? = null

        return when {
            // ANİME
            url.contains("/anime/") -> {
                val title = document
                    .selectFirst("h1.series-title, h1.watch-mini-hero-title")
                    ?.text()?.trim()
                    ?.removeSuffix(" izle")
                    ?: return null

                val episodes = parseEpisodes(document)

                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.year      = year
                    this.plot      = description
                    this.tags      = tags
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }

            // DİZİ
            url.contains("/dizi/") -> {
                val title = document.selectFirst("h1.series-title")?.text()?.trim()
                    ?: return null

                val episodes = parseEpisodes(document)

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year      = year
                    this.plot      = description
                    this.tags      = tags
                    this.duration  = duration
                }
            }

            // FİLM
            else -> {
                val title = document.selectFirst("h1.series-title, h1.movie-title")
                    ?.text()?.trim()
                    ?: document.selectFirst("meta[property=og:title]")
                        ?.attr("content")?.substringBefore(" izle")?.trim()
                    ?: return null

                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year      = year
                    this.plot      = description
                    this.tags      = tags
                    this.duration  = duration
                }
            }
        }
    }

    /**
     * Dizi detay sayfasındaki bölümleri parse eder.
     */
    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        return document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
            val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
            val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim()
                ?: return@mapNotNull null

            val subtitle = anchor.selectFirst("div.detail-episode-subtitle")
                ?.text()?.trim() ?: ""

            val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

            newEpisode(epHref) {
                this.name    = epName
                this.episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                this.season  = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  VİDEO LİNKLERİ
    // ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. BÖLÜM SAYFASINI GET
        val getResponse = app.get(
            url = data,
            headers = defaultHeaders + mapOf(
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            ),
            referer = "$mainUrl/"
        )

        val html     = getResponse.text
        val document = getResponse.document

        Log.d("DZP", "HTML uzunluğu: ${html.length}")

        // 2. EMBED URL'İ ÇIKAR
        var embedPath: String? = document
            .selectFirst("iframe#series-iframe")
            ?.attr("data-src")
            ?.substringBefore("&autoplay")

        if (embedPath.isNullOrEmpty()) {
            Log.d("DZP", "iframe data-src yok, episodesData deneniyor...")
            embedPath = extractEmbedFromEpisodesData(html, data)
        }

        if (embedPath.isNullOrEmpty()) {
            Log.e("DZP", "Embed yolu bulunamadı! HTML ilk 500: ${html.take(500)}")
            return false
        }

        val embedUrl = fixUrl(embedPath)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // 3. EMBED SAYFASINI GET
        val embedResponse = app.get(
            url = embedUrl,
            referer = data,
            headers = defaultHeaders
        )
        val embedSource = embedResponse.text
        Log.d("DZP", "Embed uzunluğu: ${embedSource.length}")

        // 4. IMAGESTOO ÖZEL AKIŞI
        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val apiUrl  = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API » $apiUrl")

            val apiResp = app.post(
                url = apiUrl,
                referer = embedUrl,
                headers = ajaxHeaders + mapOf("Accept" to "*/*")
            )

            val cookieToken = apiResp.cookies["fireplayer_player"]
            val sessionCookie = if (!cookieToken.isNullOrEmpty()) {
                "fireplayer_player=$cookieToken"
            } else {
                apiResp.headers["Set-Cookie"]
                    ?.split(";")?.firstOrNull()?.let { "$it;" } ?: ""
            }

            val securedLink = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(apiResp.text)?.groupValues?.getOrNull(1)

            if (securedLink == null) {
                Log.e("DZP", "securedLink bulunamadı!")
                return false
            }

            val finalUrl = fixUrl(securedLink.replace("\\/", "/"))
            Log.d("DZP", "✅ Imagestoo M3U8 » $finalUrl")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Dizipal (Imagestoo)",
                    url    = finalUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Cookie"     to sessionCookie
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // 5. NORMAL EMBED — m3u8 / file / sources ara
        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(embedSource)
            ?: Regex("""(?:file|src|url)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)
        if (extractedUrl == null) {
            Log.e("DZP", "m3u8 bulunamadı! İlk 400: ${embedSource.take(400)}")
            return false
        }

        val finalM3u8Url = when {
            extractedUrl.contains(".html") && extractedUrl.contains("embed-") -> {
                val id = Regex("""embed-([^.]+)\.html""")
                    .find(extractedUrl)?.groupValues?.getOrNull(1)
                if (id != null) {
                    "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/" +
                            "${id}_,n,h,.urlset/master.m3u8"
                } else extractedUrl
            }
            extractedUrl.startsWith("http") -> extractedUrl
            else -> fixUrl(extractedUrl)
        }

        Log.d("DZP", "✅ M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = "Dizipal",
                url    = finalM3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.headers = mapOf("User-Agent" to userAgent)
                this.quality = Qualities.Unknown.value
            }
        )

        // 6. ALTYAZILAR
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
                .findAll(tracksBlock).forEach { m ->
                    val item    = m.groupValues[1]
                    val fileUrl = Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""")
                        .find(item)?.groupValues?.getOrNull(1)
                    val label   = Regex("""label\s*:\s*["']([^"']+)["']""")
                        .find(item)?.groupValues?.getOrNull(1) ?: "Unknown"

                    if (fileUrl != null &&
                        (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))
                    ) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = label,
                                url  = fixUrl(fileUrl)
                            )
                        )
                    }
                }
        }

        return true
    }

    /**
     * window.episodesData JS objesinden ilgili bölümün tokenını çıkarır.
     * Örn URL: /anime/kingdom-2/1-sezon/1-bolum → season=1, episode=1
     */
    private fun extractEmbedFromEpisodesData(html: String, episodeUrl: String): String? {
        val match = Regex("""/(\d+)-sezon/(\d+)-bolum""").find(episodeUrl) ?: return null
        val season  = match.groupValues[1]
        val episode = match.groupValues[2]

        val dataMatch = Regex(
            """window\.episodesData\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: run {
            Log.d("DZP", "episodesData bloğu bulunamadı")
            return null
        }

        val jsonStr = dataMatch.groupValues[1]

        return try {
            val root = AppUtils.parseJson<Map<String, Map<String, Map<String, Any?>>>>(jsonStr)
            val epData = root[season]?.get(episode) ?: run {
                Log.d("DZP", "S$season E$episode bulunamadı (episodesData)")
                return null
            }
            (epData["iframe_url_encrypted"] as? String)?.replace("\\/", "/")
        } catch (e: Exception) {
            Log.w("DZP", "episodesData parse hatası: ${e.message}")
            null
        }
    }
}
