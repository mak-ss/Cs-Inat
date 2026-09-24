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

    // ! CloudFlare bypass
    override var sequentialMainPage = true
    // override var sequentialMainPageDelay       = 250L
    // override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                                          to "Son Bölümler",
        "${mainUrl}/diziler"                                           to "Yeni Diziler",
        "${mainUrl}/filmler"                                           to "Yeni Filmler",
        "${mainUrl}/animeler"                                          to "Animeler",
        "${mainUrl}/platform/netflix"                                  to "Netflix",
        "${mainUrl}/platform/exxen"                                    to "Exxen",
        "${mainUrl}/platform/blutv"                                    to "BluTV",
        "${mainUrl}/platform/disney"                                   to "Disney+",
        "${mainUrl}/platform/prime-video"                              to "Amazon Prime",
        "${mainUrl}/platform/tabii"                                    to "Tabii",
        "${mainUrl}/platform/gain"                                     to "Gain",
        "${mainUrl}/platform/hbomax"                                   to "HBOMax",
        "${mainUrl}/kategori/bilim-kurgu"                              to "Bilimkurgu",
        "${mainUrl}/kategori/komedi"                                   to "Komedi",
        "${mainUrl}/kategori/belgesel"                                 to "Belgesel",
    )

    // ─────────────────────────────────────────────────────────────
    //  ANA SAYFA
    // ─────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home = if (request.data.contains("/bolumler")) {
            // Son Bölümler: <div class="episodes-grid-home"> > <a class="episode-row-home">
            document.select("div.episodes-grid-home > a.episode-row-home")
                .mapNotNull { it.sonBolumler() }
        } else {
            // Diziler / Filmler / Anime / Kategori / Platform:
            // <div class="homepage-grid"> > <a class="homepage-card">
            document.select("div.homepage-grid > a.homepage-card")
                .mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    // ─────────────────────────────────────────────────────────────
    //  ELEMENT → SEARCH RESPONSE (Son Bölümler)
    // ─────────────────────────────────────────────────────────────
    private fun Element.sonBolumler(): SearchResponse? {
        val name    = this.selectFirst(".episode-row-title")?.text()?.trim() ?: return null
        val detail  = this.selectFirst(".episode-row-detail")?.text()?.trim() ?: ""

        // "1. Sezon 6. Bölüm" → "1x6"
        val episode = detail.replace(
            Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm"""),
            "$1x$2"
        )
        val title = if (episode.isNotEmpty()) "$name $episode" else name

        val href = fixUrlNull(this.attr("href")) ?: return null

        // Poster: .episode-row-thumb img (lazyload'lı olabilir)
        val imgElement = this.selectFirst(".episode-row-thumb img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        )

        // Bölüm URL'sini dizi URL'sine çevir
        // /dizi/xxx/1-sezon/6-bolum   → /dizi/xxx
        // /anime/xxx/1-sezon/6-bolum  → /anime/xxx
        val seriesUrl = href.replace(Regex("""/\d+-sezon/\d+-bolum.*$"""), "")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ELEMENT → SEARCH RESPONSE (Dizi / Film / Anime Kartı)
    // ─────────────────────────────────────────────────────────────
    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return null
        val href  = fixUrlNull(this.attr("href")) ?: return null

        val imgElement = this.selectFirst("img")
        val posterUrl  = fixUrlNull(
            imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        )
        val year = this.selectFirst("time.meta-year")?.text()?.trim()?.toIntOrNull()

        // Kart tipini badge veya URL'den tespit et
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

        return if (this.type.equals("Dizi", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year      = this@toPostSearchResult.year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
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
            searchUrl,
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        // JSON parse dene, başarısız olursa HTML parse et
        val body = responseRaw.text.trim()

        return try {
            val jsonResponse = AppUtils.parseJson<DizipalSearchData>(body)
            val list = mutableListOf<SearchResponse>()
            jsonResponse.results?.forEach { item ->
                item.toPostSearchResult()?.let { list.add(it) }
            }
            list
        } catch (e: Exception) {
            Log.w("DZP", "JSON parse başarısız, HTML fallback deneniyor: ${e.message}")
            parseHtmlSearch(body)
        }
    }

    private fun parseHtmlSearch(html: String): List<SearchResponse> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select("a.homepage-card, a.search-result-item, li.search-result").mapNotNull { el ->
            val title = el.selectFirst("h3.homepage-card-title, h3, .title")?.text()?.trim()
                ?: return@mapNotNull null
            val href = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val img  = el.selectFirst("img")
            val poster = fixUrlNull(img?.attr("data-src")?.ifEmpty { img.attr("src") })
            val year = el.selectFirst("time.meta-year")?.text()?.trim()?.toIntOrNull()

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
        // Bölüm linki yönlendirmesi: /dizi/xxx/1-sezon/6-bolum → /dizi/xxx
        if (url.contains(Regex("""/\d+-sezon/\d+-bolum"""))) {
            val seriesUrl = url.replace(Regex("""/\d+-sezon/\d+-bolum.*$"""), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year        = document.selectFirst("div.info-row:contains(Yıl) span.info-value")
            ?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("time.meta-year")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags        = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }

        val duration: Int? = null

        return when {
            // ANİME
            url.contains("/anime/") -> {
                val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
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
                val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null
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
                val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                    ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                        ?.substringBefore(" izle")?.trim()
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

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        return document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
            val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
            val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim()
                ?: return@mapNotNull null

            val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
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

        // 1. AŞAMA: GET → data-cfg token + çerezler
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val document    = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("DZP", "Bulunan Token » $configToken")
        Log.d("DZP", "Yakalanan Çerezler » $cookies")

        // 2. AŞAMA: Base64 decode
        val paddedToken  = configToken + "=".repeat((4 - configToken.length % 4) % 4)
        val decodedToken = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        Log.d("DZP", "Decoded Token » $decodedToken")

        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(decodedToken)
            ?.groupValues?.getOrNull(1)?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL token içinden alınamadı! Dönen yanıt: $decodedToken")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // 3. AŞAMA: imagestoo player
        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl =
                "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent"       to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "*/*"
                )
            )

            var sessionCookie = ""
            val playerToken = apiResponse.cookies["fireplayer_player"]

            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                val rawSetCookie = apiResponse.headers["Set-Cookie"]
                    ?: apiResponse.headers["set-cookie"]
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    rawSetCookie.split(";").firstOrNull()?.let { sessionCookie = "$it;" }
                }
            }
            Log.d("DZP", "Yakalanan Cookie » $sessionCookie")

            val responseText = apiResponse.text
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw == null) {
                Log.e("DZP", "Imagestoo API yanıtından videoSource çıkarılamadı!")
                return false
            }

            val finalM3u8Url = fixUrl(videoSourceRaw.replace("\\/", "/"))
            Log.d("DZP", "Imagestoo Çözülen Video Kaynağı » $finalM3u8Url")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Dizipal (Imagestoo)",
                    url    = finalM3u8Url,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = mapOf("Cookie" to sessionCookie)
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // 4. AŞAMA: Normal embed
        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""")
            .find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }

        val finalM3u8Url = if (extractedUrl.contains(".html")) {
            val idMatch = Regex("""embed-([^.]+)\.html""")
                .find(extractedUrl)?.groupValues?.getOrNull(1)
            if (idMatch != null) {
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                Log.e("DZP", "HTML linkinden ID ayıklanamadı: $extractedUrl")
                null
            }
        } else extractedUrl

        if (finalM3u8Url == null) return false

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = "Dizipal (Ana Sunucu)",
                url    = finalM3u8Url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )

        // 5. AŞAMA: Altyazılar
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]
                val fileUrl = Regex("""file\s*:\s*["']([^"']+)["']""")
                    .find(itemStr)?.groupValues?.getOrNull(1)
                val label   = Regex("""label\s*:\s*["']([^"']+)["']""")
                    .find(itemStr)?.groupValues?.getOrNull(1) ?: "Unknown"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
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
}
