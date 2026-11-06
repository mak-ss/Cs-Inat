package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class DiziPal12 : MainAPI() {
    override var mainUrl = "https://dizipalw2.com/"
    override var name = "DiziPalw2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/platform/netflix"      to "Netflix",
        "$mainUrl/platform/prime-video"  to "Prime Video",
        "$mainUrl/platform/disney"       to "Disney+",
        "$mainUrl/platform/hbomax"       to "HBOMax",
        "$mainUrl/platform/blutv"        to "BluTV",
        "$mainUrl/platform/exxen"        to "Exxen",
        "$mainUrl/platform/tabii"        to "Tabii",
        "$mainUrl/platform/apple-tv"     to "Apple TV+",
        "$mainUrl/platform/gain"         to "Gain",
        "$mainUrl/platform/mubi"         to "Mubi",
        "$mainUrl/platform/tod"          to "TOD",
        "$mainUrl/platform/tv"           to "TV+",
        "$mainUrl/platform/hulu"         to "Hulu",
        "$mainUrl/platform/crunchyroll"  to "Crunchyroll"
    )

    private fun cleanPoster(vararg candidates: String?): String? {
        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            if (c.startsWith("data:")) continue
            if (c.endsWith(".svg")) continue
            if (c.contains("placeholder")) continue
            return c
        }
        return null
    }

    // ---------- ANASAYFA ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach

            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim()
                ?: article.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach

            val img = article.selectFirst("img")
            val poster = cleanPoster(img?.attr("src"), img?.attr("data-src"))

            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") -> TvType.Anime
                badgeClass.contains("badge-film")  -> TvType.Movie
                badgeClass.contains("badge-dizi")  -> TvType.TvSeries
                href.contains("/anime/")           -> TvType.Anime
                href.contains("/film/")            -> TvType.Movie
                else                               -> TvType.TvSeries
            }

            val year = article.selectFirst("span.grid-card-year")?.text()?.trim()?.toIntOrNull()

            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, href, type) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
            items.add(resp)
        }

        val hasNext = document.selectFirst("nav.pagination button.pag-next:not(.disabled)") != null
            || document.selectFirst("nav.pagination a[rel=next]") != null
            || page < 20

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ---------- ARAMA ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select("article.grid-card").forEach { article ->
            val anchor = article.selectFirst("a.grid-card-link") ?: return@forEach
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            val title = article.selectFirst("h3.grid-card-title")?.text()?.trim() ?: return@forEach
            val img = article.selectFirst("img")
            val poster = cleanPoster(img?.attr("src"), img?.attr("data-src"))
            val badgeClass = article.selectFirst("span.grid-card-badge")?.className() ?: ""
            val type = when {
                badgeClass.contains("badge-anime") || href.contains("/anime/") -> TvType.Anime
                badgeClass.contains("badge-film")  || href.contains("/film/")  -> TvType.Movie
                else -> TvType.TvSeries
            }
            val resp = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster }
            }
            results.add(resp)
        }

        if (results.isEmpty()) {
            document.select("a.homepage-card").forEach { el ->
                val title = el.selectFirst("h3.homepage-card-title")?.text()?.trim() ?: return@forEach
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                val img = el.selectFirst("img")
                val poster = cleanPoster(img?.attr("data-src"), img?.attr("src"))
                val type = if (href.contains("/anime/")) TvType.Anime
                           else if (href.contains("/film/")) TvType.Movie
                           else TvType.TvSeries
                results.add(newTvSeriesSearchResponse(title, href, type) { this.posterUrl = poster })
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- DETAY ----------
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/sezon/") && url.contains("/bolum")) {
            val seriesUrl = url.replace(Regex("/\\d+-sezon/\\d+-bolum.*$"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.series-title")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?: return null

        val posterImg = document.selectFirst("div.series-poster img")
        val poster = cleanPoster(
            posterImg?.attr("src"),
            posterImg?.attr("data-src"),
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val plot = document.selectFirst("p.series-summary")?.text()?.trim()
        val year = document.selectFirst("div.series-data-item:contains(Yapım Yılı) time")?.text()?.toIntOrNull()
            ?: document.selectFirst("time[datetime]")?.text()?.toIntOrNull()

        val tags = document.select("div.series-badges a.series-badge").map { it.text().trim() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        // HTML'de bölüm kartları farklı yapıda: a.episode-card-link yerine li.ep-item içindeki a'lar
        document.select("li.ep-item a[href*='-sezon-']").forEach { epAnchor ->
            val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epTitle = epAnchor.selectFirst("span.title")?.text()?.trim() ?: "Bölüm"

            // Sezon ve bölüm numaralarını URL'den çıkar
            val seasonMatch = Regex("(\\d+)-sezon").find(epHref)
            val episodeMatch = Regex("(\\d+)-bolum").find(epHref)
            val epSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            val epEpisode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name = epTitle
                this.season = epSeason
                this.episode = epEpisode
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // ---------- İZLEME LİNKLERİ ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPal12", "loadLinks data=$data")
        return try {
            val document = app.get(data).document
            val html = document.html()

            // 1) Doğrudan iframe (bazı sayfalarda olabilir)
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='vod.']")?.attr("src")
                ?: document.selectFirst("iframe[src]")?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                Log.d("DiziPal12", "iframeSrc=$iframeSrc")
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // 2) HTML içinde doğrudan m3u8
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value
            if (directM3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "DiziPal12 HLS",
                        url = directM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // 3) YENİ: Şifreli iframe verisini çöz (data-rm-k)
            val encryptedDiv = document.selectFirst("div[data-rm-k]")
            if (encryptedDiv != null) {
                val ciphertextJson = encryptedDiv.text()
                Log.d("DiziPal12", "Encrypted data found: $ciphertextJson")
                val decryptedUrl = decryptIframeUrl(ciphertextJson)
                if (decryptedUrl != null) {
                    Log.d("DiziPal12", "Decrypted iframe URL: $decryptedUrl")
                    MntPlayer12().getUrl(fixUrl(decryptedUrl), mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            // 4) Eski yöntem: episodesData içindeki embed token (yedek)
            val episodesDataMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
            if (episodesDataMatch != null) {
                val tokenMatch = Regex("""\\/embed\\/\?token=([^"\\]+)""").find(episodesDataMatch)
                    ?: Regex("""/embed/\?token=([^"\\]+)""").find(episodesDataMatch)
                if (tokenMatch != null) {
                    val embedUrl = "$mainUrl/embed/?token=${tokenMatch.groupValues[1]}"
                    MntPlayer12().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            Log.e("DiziPal12", "Oynatıcı bulunamadı: $data")
            false
        } catch (e: Exception) {
            Log.e("DiziPal12", "loadLinks HATA » ${e.message}", e)
            false
        }
    }

    /**
     * data-rm-k div'indeki JSON'dan şifreli iframe URL'sini çözer.
     * JSON yapısı: {"ciphertext": "...", "iv": "...", "salt": "..."}
     */
    private fun decryptIframeUrl(jsonString: String): String? {
        return try {
            val json = JSONObject(jsonString)
            val ciphertextB64 = json.getString("ciphertext")
            val ivHex = json.getString("iv")
            val saltHex = json.getString("salt")

            // Base64 decode
            val ciphertext = Base64.decode(ciphertextB64, Base64.DEFAULT)
            val iv = hexStringToByteArray(ivHex)
            val salt = hexStringToByteArray(saltHex)

            // Not: Buradaki anahtar türetme yöntemi siteden siteye değişebilir.
            // Elimizdeki verilerle kesin bir anahtar yok. Çoğu dizipal türevi sitede
            // anahtar, salt'ın bir kısmı veya sabit bir string olabilir.
            // Şimdilik bilinen bir yöntem olmadığı için, salt'ın ilk 16 byte'ını anahtar olarak deniyoruz.
            // Eğer çalışmazsa, site JS'inden anahtar türetme mantığını bulmak gerekir.
            val keyBytes = salt.copyOf(16) // Varsayım: AES-128

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            val decryptedString = String(decryptedBytes, Charsets.UTF_8)

            // Çözülen string genellikle bir URL veya URL içeren bir JSON olur.
            // Basitçe URL regex'i ile çıkaralım.
            val urlRegex = Regex("""https?://[^\s"']+""")
            urlRegex.find(decryptedString)?.value
        } catch (e: Exception) {
            Log.e("DiziPal12", "decryptIframeUrl error: ${e.message}", e)
            null
        }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
