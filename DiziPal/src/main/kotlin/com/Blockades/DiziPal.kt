// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal2134.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare Bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"               to "Son Bölümler",
        "${mainUrl}/diziler"                to "Yeni Diziler",
        "${mainUrl}/filmler"                to "Yeni Filmler",
        "${mainUrl}/platform/netflix"       to "Netflix",
        "${mainUrl}/platform/exxen"         to "Exxen",
        "${mainUrl}/platform/blutv"         to "BluTV",
        "${mainUrl}/platform/disney-plus"   to "Disney+",
        "${mainUrl}/platform/prime-video"   to "Amazon Prime",
        "${mainUrl}/platform/tabii"         to "Tabii",
        "${mainUrl}/platform/gain"          to "Gain",
        "${mainUrl}/platform/max"           to "Max",
        "${mainUrl}/kategori/bilim-kurgu"   to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi"        to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel"      to "Belgesel Filmleri"
    )

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
        val name = this.selectFirst(".ep-title")?.text() ?: return null
        val episode = this.selectFirst(".ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title = "$name $episode"

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
        val title = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)
        val searchResponses = mutableListOf<SearchResponse>()

        jsonResponse.results?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.url ?: return@forEach
            val poster = item.poster

            if (item.type == "Dizi") {
                searchResponses.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            } else {
                searchResponses.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            }
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null
                
                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)
                
                val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epEpisode
                    this.season = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. AŞAMA: Sayfaya GET isteği atıp Token ve Çerezleri alıyoruz
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        // 2. AŞAMA: Token'ı Base64 Decode Et
        val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
        val decodedToken = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        Log.d("DZP", "Decoded Token » $decodedToken")

        var embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(decodedToken)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            // Eğer Base64 içinden doğrudan URL çıkmazsa ajax-player-config & ajax-view isteği atıyoruz
            val configRes = app.post(
                url = "$mainUrl/ajax-player-config",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                ),
                data = mapOf("token" to configToken)
            ).text

            embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(configRes)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
        }

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL token içinden çıkarılamadı!")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // 3. AŞAMA: Player/Embed Kaynağını Çek (M3U8 ve Altyazıları Ayıkla)
        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

        // Master veya Index M3U8 Tespiti (Verdiğin CDN URL yapısına uygun olarak)
        val masterM3u8 = Regex("""(https?://[^\s"'<]+?/hls2/[^\s"'<]+?/master\.m3u8[^\s"'<]*)""").find(embedSource)?.groupValues?.getOrNull(1)
            ?: Regex("""(https?://[^\s"'<]+?/hls2/[^\s"'<]+?/index-[^\s"'<]+\.m3u8)""").find(embedSource)?.groupValues?.getOrNull(1)
            ?: Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)?.groupValues?.getOrNull(1)

        val finalM3u8Url = if (masterM3u8 != null) {
            fixUrl(masterM3u8)
        } else {
            // HTML yapısındaki embed-ID formatına düşerse fallback:
            val idMatch = Regex("""embed-([^.]+)\.html""").find(embedSource)?.groupValues?.getOrNull(1)
            if (idMatch != null) {
                "https://s8.superadjacentsoddenly.xyz/hls2/01/00009/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                null
            }
        }

        if (finalM3u8Url == null) {
            Log.e("DZP", "M3U8 bağlantısı çıkarılamadı.")
            return false
        }

        Log.d("DZP", "Bulunan M3U8 Adresi » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (CDN Master)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            }
        )

        // 4. AŞAMA: Altyazıları (VTT) Yakalama
        val vttRegex = Regex("""(https?://[^\s"'<]+?/vtt/[^\s"'<]+?\.vtt)""")
        val vttMatches = vttRegex.findAll(embedSource).map { it.groupValues[1] }.distinct()

        for (vttUrl in vttMatches) {
            val langLabel = when {
                vttUrl.contains("_tur.vtt") -> "Türkçe"
                vttUrl.contains("_eng.vtt") -> "English"
                else -> "Altyazı"
            }

            subtitleCallback.invoke(
                SubtitleFile(
                    lang = langLabel,
                    url = fixUrl(vttUrl)
                )
            )
            Log.d("DZP", "Yakalanan Altyazı [$langLabel] » $vttUrl")
        }

        return true
    }
}
