// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal2134.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/bolumler" to "Son Bölümler",
        "${mainUrl}/diziler" to "Yeni Diziler",
        "${mainUrl}/filmler" to "Yeni Filmler",
        "${mainUrl}/platform/netflix" to "Netflix",
        "${mainUrl}/platform/exxen" to "Exxen",
        "${mainUrl}/platform/blutv" to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii" to "Tabii",
        "${mainUrl}/platform/gain" to "Gain",
        "${mainUrl}/platform/max" to "Max",
        //"${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        //"${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/kategori/komedi" to "Komedi Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/kategori/belgesel" to "Belgesel Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        //"${mainUrl}/kategori/erotik"                                    to "Erotik Filmler",
        // "${mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="  to "Aile",            // ! Fazla kategori olduğu için geç yükleniyor..
        // "${mainUrl}/diziler?kelime=&durum=&tur=2&type=&siralama="  to "Aksiyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=3&type=&siralama="  to "Animasyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel",
        // "${mainUrl}/diziler?kelime=&durum=&tur=6&type=&siralama="  to "Biyografi",
        // "${mainUrl}/diziler?kelime=&durum=&tur=7&type=&siralama="  to "Dram",
        // "${mainUrl}/diziler?kelime=&durum=&tur=8&type=&siralama="  to "Fantastik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=9&type=&siralama="  to "Gerilim",
        // "${mainUrl}/diziler?kelime=&durum=&tur=10&type=&siralama=" to "Gizem",
        // "${mainUrl}/diziler?kelime=&durum=&tur=12&type=&siralama=" to "Korku",
        // "${mainUrl}/diziler?kelime=&durum=&tur=13&type=&siralama=" to "Macera",
        // "${mainUrl}/diziler?kelime=&durum=&tur=14&type=&siralama=" to "Müzik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=16&type=&siralama=" to "Romantik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=17&type=&siralama=" to "Savaş",
        // "${mainUrl}/diziler?kelime=&durum=&tur=24&type=&siralama=" to "Yerli",
        // "${mainUrl}/diziler?kelime=&durum=&tur=18&type=&siralama=" to "Spor",
        // "${mainUrl}/diziler?kelime=&durum=&tur=19&type=&siralama=" to "Suç",
        // "${mainUrl}/diziler?kelime=&durum=&tur=20&type=&siralama=" to "Tarih",
        // "${mainUrl}/diziler?kelime=&durum=&tur=21&type=&siralama=" to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data,
        ).document
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
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "") // Sonundaki sezon-bölüm tagini at
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

    private fun DizipalSearchResult.toPostSearchResult(): SearchResponse? {
        // Zorunlu alanların kontrolü (Early return)
        val title = this.title ?: return null
        val href = this.url ?: return null

        return if (this.type.equals("Dizi", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year = this@toPostSearchResult.year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year = this@toPostSearchResult.year
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
                this.duration = duration
            }
        } else {
            // Film detay sayfası HTML'i elimizde olmadığı için en olası selector'ları fallback ile yazdım.
            // Gerekirse og:title meta tag'inden de çekebilirsin.
            val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
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

        // 1. SAYFA GEÇİCİ OLARAK BURADAN ÇEKİLİYOR
        val pageContent = app.get(
            data,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept-Language" to "en-US,en;q=0.9,tr-TR;q=0.8,tr;q=0.7",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            )
        ).text

        // Orijinal sitedeki script dosyasından video config token'ı alınıyor
        // Bu genellikle JS dosyasında gizlidir ve dinamik olarak oluşturulur
        // Bu kısım hassas ve site güncellemesiyle değişebilir
        val configToken = Regex("""data-cfg="([^"]+)"""").find(pageContent)?.groupValues?.get(1)

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        val decodedToken = try {
            // Token'ı Base64 decode et
            val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
            String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e("DZP", "Token decode edilemedi: ${e.message}")
            return false
        }

        // JSON parse et
        val jsonData = AppUtils.parseJson<VideoConfigData>(decodedToken)

        val embedUrlRaw = jsonData.v?.replace("\\/", "/")
        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL token içinden alınamadı! Dönen yanıt: $decodedToken")
            return false
        }
        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // 2. Embed URL'den video kaynağını çekme
        val embedPageContent = app.get(
            embedUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to data // Önemli referer bilgisi
            )
        ).text

        // Embed sayfasından m3u8 linkini veya alt kaynağı bulma
        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedPageContent)
        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }

        val finalM3u8Url = fixUrl(extractedUrl)
        Log.d("DZP", "Başarıyla üretilen M3U8 URL: $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl // Embed URL'yi referer olarak ayarlayalım
                quality = Qualities.Unknown.value
            }
        )

        // 3. Altyazıları (Tracks) Yakala
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedPageContent)

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

    // JSON Veri Yapıları
    data class DizipalSearchData(
        val results: List<DizipalSearchResult>? = null
    )

    data class DizipalSearchResult(
        val title: String? = null,
        val url: String? = null,
        val poster: String? = null,
        val year: Int? = null,
        val type: String? = null // "Dizi" veya "Film" olabilir
    )

    // Video Config için JSON Veri Yapısı
    data class VideoConfigData(
        val v: String? = null, // Embed URL'yi içerir
        val k: String? = null  // Encryption key, şimdilik kullanılmıyor
    )
}
