// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal2221.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler"                                      to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/platform/netflix"                              to "Netflix",
        "${mainUrl}/platform/exxen"                                to "Exxen",
        "${mainUrl}/platform/blutv"                                to "BluTV",
        "${mainUrl}/platform/disney-plus"                          to "Disney+",
        "${mainUrl}/platform/prime-video"                          to "Amazon Prime",
        "${mainUrl}/platform/tabii"                                to "Tabii",
        "${mainUrl}/platform/gain"                                 to "Gain",
        "${mainUrl}/platform/max"                                  to "Max",
        "${mainUrl}/kategori/bilim-kurgu"                          to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi"                               to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel"                             to "Belgesel Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
        ).document
        val home     = if (request.data.contains("/bolumler")) {
            document.select("div.episodes-list-grid > a.episode-list-item").mapNotNull { it.sonBolumler() }
        } else if (request.data.contains("/filmler") || request.data.contains("/kategori/")) {
            document.select("article.dp-card").mapNotNull { it.filmler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst(".ep-title")?.text() ?: return null
        val episode   = this.selectFirst(".ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "") // Sonundaki sezon-bölüm tagini at
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

    private fun Element.filmler(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("h3 a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val year = this.selectFirst("div.dp-card-meta span:first-child")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    private fun DizipalSearchResult.toPostSearchResult(): SearchResponse? {
        // Zorunlu alanların kontrolü (Early return)
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

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama URL'sini doğrudan parametre ile oluşturuyoruz
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        // JSON'ı yeni data class yapımızla parse ediyoruz
        val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        // Eğer results null dönerse veya boşsa güvenli şekilde geçiyoruz
        jsonResponse.results?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.url ?: return@forEach
            val poster = item.poster

            // Dizi mi Film mi olduğunu API'den gelen "type" alanına göre belirliyoruz
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
        // 1. BÖLÜM LİNKİ YÖNLENDİRMESİ
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        // Genel Meta Bilgileri
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        // .info-row içindeki span yapısından veriyi çekiyoruz
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()

        // "Kategoriler" altındaki tüm <a> tag'lerini çekip listeye çeviriyoruz
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }

        // HTML'de süre bilgisi mevcut değil, gelirse diye hazırlıklı bırakıyorum:
        // val durationText = document.selectFirst("div.info-row:contains(Süre) span.info-value")?.text()
        // val duration = Regex("(\\d+)").find(durationText ?: "")?.value?.toIntOrNull()
        val duration: Int? = null

        if (url.contains("/dizi/")) {
            // Yeni DOM yapısında başlık h1 tag'inde class ile tutuluyor
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null

                // Format: "1. Sezon 1. Bölüm" -> Regex ile güvenli parse işlemi
                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        } else {
            // Film detay sayfası HTML'i (221v.d.txt) selector'larına göre güncellendi.
            val title = document.selectFirst("h1")?.text()?.substringBefore(" (")?.trim() ?: return null

            val movieYear = document.selectFirst("div.dp-detail-meta span:first-child")?.text()?.toIntOrNull()
            val moviePlot = document.selectFirst("div.dp-description")?.text()?.trim()
            val movieTags = document.select("div.dp-detail-meta a[rel=tag]").map { it.text().trim() }
            val movieDuration = document.selectFirst("div.dp-detail-meta span:nth-child(2)")?.text()?.replace(" dk", "")?.toIntOrNull()


            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = movieYear ?: year
                this.plot      = moviePlot ?: description
                this.tags      = movieTags.ifEmpty { tags }
                this.duration  = movieDuration ?: duration
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

        // 1. AŞAMA: GET isteği atıp hem Token'ı hem de ÇEREZLERİ alıyoruz
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val document = getResponse.document

        // Sayfa hem film hem dizi bölümü olabilir. Token'ı ikisinden de almaya çalış.
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
            ?: document.selectFirst("div[data-rm-k]")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        Log.d("DZP", "Bulunan Token » $configToken")
        Log.d("DZP", "Yakalanan Çerezler » $cookies")

        // 2. AŞAMA: Token'ı API'ye Çerezlerle (Cookies) birlikte POST et
        val configResponseRaw = app.post(
            url = "$mainUrl/ajax-player-config",
            headers = mapOf(
                "User-Agent"       to userAgent,
                "Accept"           to "*/*",
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin"           to mainUrl,
                "Cookie"           to cookies
            ),
            referer = data,
            data = mapOf("cfg" to configToken)
        ).text

        Log.d("DZP", "API Yanıtı » $configResponseRaw")

        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(configResponseRaw)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL config'den alınamadı! Dönen yanıt: $configResponseRaw")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // ---------------------------------------------------------
        // YENİ EKLENEN AŞAMA: İMAGESTOO SUNUCUSU KONTROLÜ
        // ---------------------------------------------------------
        if (embedUrl.contains("imagestoo")) {
            // 1. URL'nin sonundan video ID'sini çek (Örn: decff3a1f694fccd108d4ce07b2587b5)
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")

            // 2. İlgili API endpoint'ini oluştur
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

            // 3. API'ye istek at (X-Requested-With header'ı bu tür AJAX isteklerinde önemlidir)
            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                )
            )

            var sessionCookie = ""

// 1. Önce CloudStream'in kendi parse ettiği "cookies" map'ine bakalım (En kolayı)
            val playerToken = apiResponse.cookies["fireplayer_player"]

            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                // 2. Eğer orada yoksa, Headers içinden manuel okuyalım.
                // Büyük/küçük harf duyarlılığından kaçınmak için ikisini de kontrol ediyoruz.
                val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]

                // rawSetCookie bir String olarak döndü, artık String metodlarını güvenle kullanabiliriz
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    // substringBefore yerine split kullanmak tip çıkarımı açısından her zaman daha garantilidir
                    val cleanCookie = rawSetCookie.split(";").firstOrNull()
                    if (cleanCookie != null) {
                        sessionCookie = "$cleanCookie;"
                    }
                }
            }

            Log.d("DZP", "Yakalanan Cookie » $sessionCookie")

            val responseText = apiResponse.text

            // 4. JSON benzeri veriden securedLink değerini yakala
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                // Kaçış karakterlerini (\/) temizle ve fixUrl ile son halini ver
                val cleanUrl = videoSourceRaw.replace("\\/", "/")
                val finalM3u8Url = fixUrl(cleanUrl)

                Log.d("DZP", "Imagestoo Çözülen Video Kaynağı » $finalM3u8Url")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mapOf("Referer" to embedUrl).toString()
                        headers= mapOf("Cookie" to sessionCookie)
                        quality = Qualities.Unknown.value
                    }
                )

                // Imagestoo için altyazı çekme işlemi gerekiyorsa API yanıtından aynı Regex mantığıyla çekilebilir.
                // Şimdilik işlemi burada sonlandırıyoruz.
                return true

            } else {
                Log.e("DZP", "Imagestoo API yanıtından videoSource çıkarılamadı! Yanıt: $apiResponse")
                return false
            }
        }
        // ---------------------------------------------------------
        // STANDART AŞAMA: ANA SUNUCU VEYA FARKLI KAYNAK
        // ---------------------------------------------------------

        // 3. AŞAMA: Embed Sayfasına Git ve JWPlayer Verilerini Ayıkla
        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

// 1. Regex'leri ve ilk eşleşmeyi koruyoruz
        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }

// 2. Dönüştürülmüş nihai URL'yi tutacak değişken
        val finalM3u8Url = if (extractedUrl.contains(".html")) {
            // URL'den sadece ID'yi (x6sctfgmyfws) güvenli bir şekilde ayıklıyoruz
            // Örn: .../embed-x6sctfgmyfws.html -> x6sctfgmyfws
            val idRegex = Regex("""embed-([^.]+)\.html""")
            val idMatch = idRegex.find(extractedUrl)?.groupValues?.getOrNull(1)

            if (idMatch != null) {
                // İstenen formata göre string interpolation ile yeni URL'yi inşa ediyoruz
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                Log.e("DZP", "HTML linkinden ID ayıklanamadı: $extractedUrl")
                null
            }
        } else {
            // Eğer ilk regex'ten doğrudan m3u8 geldiyse olduğu gibi kullanıyoruz
            extractedUrl
        }

// 3. Son kontrol ve validation
        if (finalM3u8Url == null) {
            return false
        }

// Artık elimizde işlenmiş nihai m3u8 URL'si var
        Log.d("DZP", "Başarıyla üretilen M3U8 URL: $finalM3u8Url")

// Bundan sonraki stream ekleme veya return işlemlerini finalM3u8Url ile yapabilirsin.

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            }
        )

        // 4. AŞAMA: Altyazıları (Tracks) Yakala
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

data class DizipalSearchData(
    val results: List<DizipalSearchResult>?
)

data class DizipalSearchResult(
    val title: String?,
    val url: String?,
    val poster: String?,
    val year: Int?,
    val type: String?
)
