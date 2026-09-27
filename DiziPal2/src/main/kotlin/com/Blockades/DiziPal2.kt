// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal2 : MainAPI() {
    override var mainUrl              = "https://dizipal2134.com"
    override var name                 = "DiziPal2"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"             to "Son Bölümler",
        "${mainUrl}/diziler"              to "Yeni Diziler",
        "${mainUrl}/filmler"              to "Yeni Filmler",
        "${mainUrl}/platform/netflix"     to "Netflix",
        "${mainUrl}/platform/exxen"       to "Exxen",
        "${mainUrl}/platform/blutv"       to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii"       to "Tabii",
        "${mainUrl}/platform/gain"        to "Gain",
        "${mainUrl}/platform/max"         to "Max",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi"      to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel"    to "Belgesel Filmleri",
    )

    // ==================== ANA SAYFA ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name    = this.selectFirst(".ep-title")?.text() ?: return null
        val episode = this.selectFirst(".ep-info")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title   = "$name $episode"

        val href = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    // ==================== ARAMA ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        // JSONObject ile parse et — Jackson'a gerek yok
        val results = mutableListOf<SearchResponse>()
        try {
            val json = org.json.JSONObject(responseRaw.text)
            val arr = json.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val title  = item.optString("title", "").trim()
                    val url    = fixUrl(item.optString("url", ""))
                    val poster = item.optString("poster", "").takeIf { it.isNotBlank() }
                    val type   = item.optString("type", "")
                    val year   = item.optInt("year", 0).takeIf { it > 0 }

                    if (title.isBlank() || url.isBlank()) continue

                    val resp = if (type.equals("Dizi", true)) {
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year      = year
                        }
                    } else {
                        newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year      = year
                        }
                    }
                    results.add(resp)
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPal2", "search JSON hatası » ${e.message}")
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ==================== DETAY SAYFASI ====================

    override suspend fun load(url: String): LoadResponse? {
        Log.e("DiziPal2", "===== load ÇAĞRILDI =====")
        Log.e("DiziPal2", "url » $url")

        // Bölüm linki yönlendirmesi
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")
            ?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }
        val duration: Int? = null

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim()
                    ?: return@mapNotNull null

                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason   = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode  = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            Log.e("DiziPal2", "TOPLAM BÖLÜM » ${episodes.size}")

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        } else {
            val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        }
    }

    // ==================== LİNK YÜKLEME ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e("DiziPal2", "===== loadLinks ÇAĞRILDI =====")
        Log.e("DiziPal2", "data » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. AŞAMA: GET isteği atıp hem Token'ı hem de ÇEREZLERİ alıyoruz
        val getResponse = try {
            app.get(
                url = data,
                headers = mapOf(
                    "User-Agent"    to userAgent,
                    "Cache-Control" to "no-cache",
                    "Pragma"        to "no-cache"
                )
            )
        } catch (e: Exception) {
            Log.e("DiziPal2", "GET hatası » ${e.message}")
            return false
        }

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DiziPal2", "data-cfg alınamadı!")
            return false
        }

        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("DiziPal2", "Bulunan Token » $configToken")
        Log.d("DiziPal2", "Yakalanan Çerezler » $cookies")

        // 2. AŞAMA: Token'ı Base64 Decode Et
        val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
        val decodedToken = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        Log.d("DiziPal2", "Decoded Token » $decodedToken")

        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(decodedToken)
            ?.groupValues?.getOrNull(1)?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DiziPal2", "Embed URL token içinden alınamadı: $decodedToken")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DiziPal2", "Çözülen Embed URL » $embedUrl")

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DiziPal2", "Imagestoo API URL » $imagestooApiUrl")

            val apiResponse = try {
                app.post(
                    url = imagestooApiUrl,
                    referer = embedUrl,
                    headers = mapOf(
                        "User-Agent"       to userAgent,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept"           to "*/*"
                    )
                )
            } catch (e: Exception) {
                Log.e("DiziPal2", "Imagestoo POST hatası » ${e.message}")
                return false
            }

            var sessionCookie = ""

            val playerToken = apiResponse.cookies["fireplayer_player"]
            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    val cleanCookie = rawSetCookie.split(";").firstOrNull()
                    if (cleanCookie != null) sessionCookie = "$cleanCookie;"
                }
            }

            Log.d("DiziPal2", "Yakalanan Cookie » $sessionCookie")

            val responseText = apiResponse.text
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)
                ?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                val cleanUrl = videoSourceRaw.replace("\\/", "/")
                val finalM3u8Url = fixUrl(cleanUrl)

                Log.d("DiziPal2", "Imagestoo Çözülen Video » $finalM3u8Url")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.headers = mapOf(
                            "Referer" to embedUrl,
                            "Cookie"  to sessionCookie
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            } else {
                Log.e("DiziPal2", "Imagestoo videoSource yok. Yanıt: ${responseText.take(300)}")
                return false
            }
        }

        // 3. AŞAMA: Normal embed — sources veya v değişkeni ara
        val embedSource = try {
            app.get(
                url = embedUrl,
                referer = data,
                headers = mapOf("User-Agent" to userAgent)
            ).text
        } catch (e: Exception) {
            Log.e("DiziPal2", "embed GET hatası » ${e.message}")
            return false
        }

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DiziPal2", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }

        val finalM3u8Url: String? = if (extractedUrl.contains(".html")) {
            val idRegex = Regex("""embed-([^.]+)\.html""")
            val idMatch = idRegex.find(extractedUrl)?.groupValues?.getOrNull(1)

            if (idMatch != null) {
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                Log.e("DiziPal2", "HTML linkinden ID ayıklanamadı: $extractedUrl")
                null
            }
        } else {
            extractedUrl
        }

        if (finalM3u8Url == null) return false

        Log.d("DiziPal2", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )

        // 4. AŞAMA: Altyazıları yakala
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]

                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = fixUrl(fileUrl)
                        )
                    )
                }
            }
        }

        return true
    }
}
