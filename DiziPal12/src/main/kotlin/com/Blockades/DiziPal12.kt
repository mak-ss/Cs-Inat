package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        "$mainUrl/trendler" to "Trendler",
        "$mainUrl/diziler"  to "Diziler",
        "$mainUrl/filmler"  to "Filmler",
        "$mainUrl/anime"    to "Anime"
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
        document.select("li.ep-item a[href*='-sezon-']").forEach { epAnchor ->
            val epHref = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epTitle = epAnchor.selectFirst("span.title")?.text()?.trim() ?: "Bölüm"
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

            // 1) Doğrudan m3u8
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.value
            if (directM3u8 != null) {
                Log.d("DiziPal12", "Found direct m3u8: $directM3u8")
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

            // 2) Doğrudan iframe src
            val iframeSrc = document.selectFirst("iframe#montana-frame")?.attr("src")
                ?: document.selectFirst("iframe[src*='mntplayer']")?.attr("src")
                ?: document.selectFirst("iframe[src*='vod.']")?.attr("src")
                ?: document.selectFirst("iframe[src]")?.attr("src")

            if (!iframeSrc.isNullOrBlank() && !iframeSrc.startsWith("about:")) {
                Log.d("DiziPal12", "Found iframeSrc: $iframeSrc")
                MntPlayer12().getUrl(fixUrl(iframeSrc), mainUrl, subtitleCallback, callback)
                return true
            }

            // 3) data-rm-k → Kotlin ile AES çözümü (WebView YOK)
            val rmK = document.selectFirst("div[data-rm-k]")?.attr("data-rm-k")
                ?: document.selectFirst("[data-rm-k]")?.attr("data-rm-k")
            if (!rmK.isNullOrBlank()) {
                Log.d("DiziPal12", "data-rm-k found, decrypting (len=${rmK.length})")
                val iframeUrl = decryptRmK(rmK)
                if (iframeUrl != null) {
                    Log.d("DiziPal12", "Decrypted iframe URL: $iframeUrl")
                    MntPlayer12().getUrl(fixUrl(iframeUrl), mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            // 4) Yedek: episodesData içindeki embed token
            val episodesDataMatch = Regex("""window\.episodesData\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
            if (episodesDataMatch != null) {
                val tokenMatch = Regex("""\\/embed\\/\?token=([^"\\]+)""").find(episodesDataMatch)
                    ?: Regex("""/embed/\?token=([^"\\]+)""").find(episodesDataMatch)
                if (tokenMatch != null) {
                    val embedUrl = "$mainUrl/embed/?token=${tokenMatch.groupValues[1]}"
                    Log.d("DiziPal12", "Found embed token: $embedUrl")
                    MntPlayer12().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            // 5) Sayfa içinde mntplayer / vod url geçiyorsa regex ile yakala
            val playerUrl = Regex("""(https?://[^\s"']*mntplayer[^\s"']*)""").find(html)?.value
                ?: Regex("""(https?://vod\.[^\s"']+)""").find(html)?.value
            if (playerUrl != null) {
                Log.d("DiziPal12", "Regex player URL: $playerUrl")
                MntPlayer12().getUrl(fixUrl(playerUrl), mainUrl, subtitleCallback, callback)
                return true
            }

            Log.e("DiziPal12", "Oynatıcı bulunamadı: $data")
            false
        } catch (e: Exception) {
            Log.e("DiziPal12", "loadLinks HATA » ${e.message}", e)
            false
        }
    }

    // ---------- data-rm-k ÇÖZÜCÜ ----------
    /**
     * Dizipal data-rm-k içeriğini Kotlin içinde çözer.
     * Not: Sitenin JS bundle'ı güncellenirse anahtar/IV değişebilir.
     * Bu yüzden birkaç yaygın kombinasyon denenir.
     */
    private fun decryptRmK(encoded: String): String? {
        // Olası anahtar/IV adayları (sitenin JS'inden teyit edilebilir).
        // İhtiyaç halinde buraya doğru anahtarı ekleyin; ilk tutan kullanılır.
        data class KeyIv(val key: ByteArray, val iv: ByteArray)

        val candidates = listOf(
            // Yaygın kullanılan sabit anahtar örnekleri
            KeyIv("0123456789abcdef".toByteArray(Charsets.UTF_8), "abcdef9876543210".toByteArray(Charsets.UTF_8)),
            KeyIv("dizipal12key2024".toByteArray(Charsets.UTF_8), "dizipal12iv2024!".toByteArray(Charsets.UTF_8)),
            KeyIv("DiziPalSecretKey".toByteArray(Charsets.UTF_8), "DiziPalSecretIv!".toByteArray(Charsets.UTF_8)),
            KeyIv("1234567890abcdef1234567890abcdef".toByteArray(Charsets.UTF_8).copyOf(32), "abcdef1234567890".toByteArray(Charsets.UTF_8))
        )

        // Farklı Base64 biçimleri dene
        val rawVariants = listOfNotNull(
            runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull(),
            runCatching { Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull(),
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull(),
            runCatching { encoded.toByteArray(Charsets.ISO_8859_1) }.getOrNull() // zaten raw byte ise
        ).distinctBy { it.size }

        val modes = listOf(
            "AES/CBC/PKCS5Padding",
            "AES/CBC/NoPadding",
            "AES/ECB/PKCS5Padding",
            "AES/CFB/PKCS5Padding",
            "AES/CTR/NoPadding"
        )

        for (variant in rawVariants) {
            for (cand in candidates) {
                val key = when (cand.key.size) {
                    16, 24, 32 -> cand.key
                    else -> null
                } ?: continue
                for (mode in modes) {
                    try {
                        val cipher = Cipher.getInstance(mode)
                        if (mode.contains("/ECB/")) {
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                        } else {
                            val iv = if (mode.contains("/CTR/") || mode.contains("/CFB/")) {
                                cand.iv.copyOf(16)
                            } else cand.iv
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        }
                        val out = cipher.doFinal(variant)
                        val s = String(out, Charsets.UTF_8).trim()
                        if (looksLikeUrl(s)) {
                            // Bazen JSON içine gömülü olabilir
                            val m = Regex("""(https?://[^\s"']+)""").find(s)?.value
                            return m ?: s
                        }
                    } catch (_: Exception) {
                        // sıradaki kombinasyonu dene
                    }
                }
            }
        }

        // Son çare: encoded içinde doğrudan url varsa
        Regex("""(https?://[^\s"'<>]+)""").find(encoded)?.let { return it.value }
        Log.e("DiziPal12", "decryptRmK: hiçbir kombinasyon tutmadı")
        return null
    }

    private fun looksLikeUrl(s: String): Boolean {
        if (s.length < 8) return false
        val t = s.trim()
        return t.startsWith("http://") || t.startsWith("https://") ||
               (t.startsWith("/") && !t.contains(" ")) ||
               t.contains("mntplayer") || t.contains("vod.")
    }
}
