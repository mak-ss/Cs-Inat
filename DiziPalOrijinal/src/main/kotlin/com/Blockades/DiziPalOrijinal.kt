// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class DiziPalOrijinal : MainAPI() {
    override var mainUrl              = "https://dizipal1599.com"
    override var name                 = "DiziPal Orijinal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val interceptor: okhttp3.Interceptor by lazy { TarayiciGetir.Yakalayici() }

    override val mainPage = mainPageOf(
        "${mainUrl}/yabanci-dizi-izle"           to "Yeni Diziler",
        "${mainUrl}/film-izle"                    to "Yeni Filmler",
        "${mainUrl}/trendler"                     to "Trendler",
        "${mainUrl}/kesfet"                       to "Keşfet",
        "${mainUrl}/platform-bolumler/netflix"    to "Netflix",
        "${mainUrl}/platform-bolumler/prime"      to "Amazon Prime",
        "${mainUrl}/platform-bolumler/max"        to "Max",
        "${mainUrl}/platform-bolumler/disneyplus" to "Disney+",
        "${mainUrl}/platform-bolumler/exxen"      to "Exxen",
        "${mainUrl}/platform-bolumler/apple-tv"   to "Apple TV+",
        "${mainUrl}/platform-bolumler/tabii"      to "Tabii",
        "${mainUrl}/platform-bolumler/gain"       to "GAİN",
        "${mainUrl}/platform-bolumler/tod"        to "TOD (beIN)"
    )

    // ============================================================
    // ANA SAYFA
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page"
            else "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = EngelAsici.http.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val home = mutableListOf<SearchResponse>()

        document.select("a.tc").forEach { element ->
            element.toCardSearchResult()?.let { res ->
                if (home.none { it.url == res.url }) home.add(res)
            }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/series/'], a[href*='/movies/']").forEach { element ->
                element.toCardSearchResult()?.let { res ->
                    if (home.none { it.url == res.url }) home.add(res)
                }
            }
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toCardSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/series/") && !href.contains("/movies/") && !href.contains("/bolum/")) return null

        val title = this.selectFirst("span.tt")?.text()?.trim()
            ?: this.selectFirst("span.ft1")?.text()?.trim()
            ?: this.selectFirst("span.tn")?.text()?.trim()
            ?: this.attr("aria-label").substringBefore(",").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val imgEl = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            imgEl?.attr("src")?.ifEmpty { null } ?: imgEl?.attr("data-src")
        )

        return when {
            href.contains("/series/") -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
            href.contains("/movies/") -> {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            }
            href.contains("/bolum/") -> {
                val seriesUrl = href.substringBeforeLast("-").replace("/bolum/", "/series/")
                newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
            else -> null
        }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = if (this.slug.startsWith("http")) this.slug else "${mainUrl}/${this.slug}"
        val posterUrl = this.poster

        return if (this.type.equals("series", ignoreCase = true) || href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    // ============================================================
    // ARAMA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val jsonPayload = jacksonObjectMapper().writeValueAsString(
                mapOf("action" to "searchContent", "params" to listOf(query))
            )
            val responseRaw = EngelAsici.http.post(
                "${mainUrl}/api/mj",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "${mainUrl}/",
                    "Origin" to mainUrl
                ),
                requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            )

            val mapper = jacksonObjectMapper()
            val rootNode = mapper.readTree(responseRaw.text)
            val resultArrayNode = rootNode.get("result")

            if (resultArrayNode == null || !resultArrayNode.isArray) return emptyList()

            val searchItems: List<SearchItem> = mapper.readValue(resultArrayNode.traverse())
            searchItems.map { it.toPostSearchResult() }
        } catch (e: Exception) {
            Log.e("DiziPal", "Arama Hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================
    // DETAY SAYFASI (load) — title fallback genişletildi + /bolum/ özel işlem
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d("DiziPal", "========== load() BAŞLADI ==========")
        Log.d("DiziPal", "--> url: $url")

        val response = EngelAsici.http.get(url, interceptor = interceptor, headers = getHeaders(mainUrl))
        val document = response.document
        val html = document.html()

        Log.d("DiziPal", "--> HTML uzunluğu: ${html.length}")

        val isSeries   = url.contains("/series/") || url.contains("/bolum/")
        val schemaType = if (isSeries) "TVSeries" else "Movie"
        val jsonLd     = extractJsonLd(html, schemaType)

        Log.d("DiziPal", "--> isSeries: $isSeries, jsonLd var mı: ${jsonLd != null}")

        // Title fallback — çok daha kapsamlı
        val title = jsonLd?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.flex h2")?.text()?.trim()
            ?: document.selectFirst("span.tt")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=title]")?.attr("content")?.trim()
            ?: document.title()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                Log.e("DiziPal", "--> title BULUNAMADI, HTML ilk 1000 karakter: ${html.take(1000)}")
                return null
            }

        Log.d("DiziPal", "--> title: '$title'")

        val poster = jsonLd?.optString("image")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.hero img")?.attr("src")
            ?: document.selectFirst("div.fr img")?.attr("src")
            ?: document.selectFirst("div.page-top img[alt]")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = jsonLd?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.summary p")?.text()?.trim()
            ?: document.selectFirst("p.plot")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val year = jsonLd?.optString("datePublished")?.take(4)?.toIntOrNull()
            ?: document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        // /bolum/ URL'si ise tek episode olarak döndür
        if (url.contains("/bolum/")) {
            Log.d("DiziPal", "--> /bolum/ URL'si, seri URL'sine dönüştürülüyor")
            val seriesUrl = url.substringBeforeLast("-").replace("/bolum/", "/series/")
            Log.d("DiziPal", "--> seriesUrl: $seriesUrl")

            val singleEpisode = listOf(
                newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                }
            )

            return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, singleEpisode) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
            }.also { Log.d("DiziPal", "--> load() BAŞARIYLA döndü (bolum, tek episode)") }
        }

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val containsSeason = jsonLd?.opt("containsSeason")
            if (containsSeason is JSONArray) {
                Log.d("DiziPal", "--> JSON-LD containsSeason: JSONArray (${containsSeason.length()} sezon)")
                for (sIdx in 0 until containsSeason.length()) {
                    val seasonObj = containsSeason.optJSONObject(sIdx) ?: continue
                    val sNum = seasonObj.optInt("seasonNumber", sIdx + 1)
                    val epArr = seasonObj.optJSONArray("episode") ?: continue
                    for (eIdx in 0 until epArr.length()) {
                        val epObj = epArr.optJSONObject(eIdx) ?: continue
                        val epUrl = epObj.optString("url")
                        val epName = epObj.optString("name", "${eIdx + 1}. Bölüm")
                        val epNum = epObj.optInt("episodeNumber", eIdx + 1)
                        if (epUrl.isNotBlank()) {
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = epNum
                                    this.description = epObj.optString("description").takeIf { it.isNotBlank() }
                                }
                            )
                        }
                    }
                }
            } else if (containsSeason is JSONObject) {
                Log.d("DiziPal", "--> JSON-LD containsSeason: JSONObject (tek sezon)")
                val sNum = containsSeason.optInt("seasonNumber", 1)
                val epArr = containsSeason.optJSONArray("episode")
                if (epArr != null) {
                    for (eIdx in 0 until epArr.length()) {
                        val epObj = epArr.optJSONObject(eIdx) ?: continue
                        val epUrl = epObj.optString("url")
                        val epName = epObj.optString("name", "${eIdx + 1}. Bölüm")
                        val epNum = epObj.optInt("episodeNumber", eIdx + 1)
                        if (epUrl.isNotBlank()) {
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = epNum
                                    this.description = epObj.optString("description").takeIf { it.isNotBlank() }
                                }
                            )
                        }
                    }
                }
            } else {
                Log.d("DiziPal", "--> JSON-LD containsSeason YOK, DOM fallback kullanılacak")
            }

            if (episodes.isEmpty()) {
                Log.d("DiziPal", "--> Episode listesi boş, DOM'dan toplanıyor...")
                document.select("a[href*='/bolum/']").forEach { link: Element ->
                    val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                    val text = link.text().trim()
                    val sNum = Regex("""(\d+)\.\s*Sezon""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)x\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    val eNum = Regex("""(\d+)\.\s*Bölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    val epName = link.selectFirst("h2, span.tn, span.tt")?.text()?.trim()
                        ?: text.ifEmpty { "Bölüm" }

                    if (episodes.none { it.data == epHref }) {
                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                    }
                }
            }

            Log.d("DiziPal", "--> Toplam episode sayısı: ${episodes.size}")

            if (episodes.isEmpty()) {
                Log.e("DiziPal", "--> EPISODE LİSTESİ BOŞ! load() null dönüyor (series)")
                return null
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
            }.also { Log.d("DiziPal", "--> load() BAŞARIYLA döndü (series, ${episodes.size} bölüm)") }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
            }.also { Log.d("DiziPal", "--> load() BAŞARIYLA döndü (movie)") }
        }
    }

    private fun extractJsonLd(html: String, targetType: String): JSONObject? {
        val typePattern = "\"@type\":\"$targetType\""
        val pos = html.indexOf(typePattern)
        if (pos == -1) return null

        var start = pos
        while (start > 0 && html[start] != '{') start--

        var braceCount = 0
        var end = start
        for (i in start until html.length) {
            if (html[i] == '{') braceCount++
            else if (html[i] == '}') {
                braceCount--
                if (braceCount == 0) { end = i + 1; break }
            }
        }

        return try { JSONObject(html.substring(start, end)) } catch (e: Exception) { null }
    }

    // ============================================================
    // LİNK ÇIKARMA (loadLinks)
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPal", "========== loadLinks BAŞLADI ==========")
        Log.d("DiziPal", "--> data URL: $data")

        val response = EngelAsici.http.get(data, interceptor = interceptor, headers = getHeaders(mainUrl))
        val html = response.text
        Log.d("DiziPal", "--> HTML uzunluğu: ${html.length}")

        // === 1. NP_CONFIG bu sayfada mı? ===
        if (html.contains("NP_CONFIG")) {
            Log.d("DiziPal", "--> NP_CONFIG bu sayfada bulundu, doğrudan extractor'a gönderiliyor")
            DiziPalOrijinalPlayer().getUrl(
                url = data,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return true
        }

        // === 2. ciphertext/iv/salt dene ===
        val ctMatch   = Regex("""\\*"ciphertext\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val ivMatch   = Regex("""\\*"iv\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val saltMatch = Regex("""\\*"salt\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val keyMatch  = Regex("""\\*"k\\*"\s*:\s*\\*"([A-Za-z0-9]{40,})\\*"""").find(html)?.groupValues?.get(1)

        val defaultPassphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
        val passphrase = keyMatch ?: defaultPassphrase

        var iframeUrl = ""

        if (ctMatch != null && ivMatch != null && saltMatch != null) {
            Log.d("DiziPal", "--> ciphertext/iv/salt BULUNDU, decrypt ediliyor...")
            iframeUrl = decryptDizipalParams(ctMatch, ivMatch, saltMatch, passphrase)
        } else {
            Log.d("DiziPal", "--> ciphertext/iv/salt BULUNAMADI, alternatif yollar deneniyor...")
            val encryptedText = response.document.selectFirst("div[data-rm-k=true]")?.text() ?: ""
            if (encryptedText.isNotEmpty()) {
                Log.d("DiziPal", "--> div[data-rm-k=true] BULUNDU")
                iframeUrl = decryptDizipalData(encryptedText)
            }
        }

        Log.d("DiziPal", "--> iframeUrl (decrypt sonrası): '${iframeUrl.take(120)}'")

        // === 3. iframe attribute taraması ===
        if (iframeUrl.isEmpty()) {
            val iframeEl = response.document.selectFirst("iframe[data-player-frame]")
                ?: response.document.selectFirst("iframe")
            if (iframeEl != null) {
                val attrs = iframeEl.attributes().associate { it.key to it.value }
                Log.d("DiziPal", "--> iframe attribute'ları: $attrs")

                val candidates = listOf(
                    iframeEl.attr("src"),
                    iframeEl.attr("data-src"),
                    iframeEl.attr("data-url"),
                    iframeEl.attr("data-player"),
                    iframeEl.attr("data-frame"),
                    iframeEl.attr("data-lazy-src"),
                    iframeEl.attr("data-original")
                )
                for (c in candidates) {
                    if (c.isNotEmpty() && (c.startsWith("http") || c.startsWith("//"))) {
                        iframeUrl = c
                        Log.d("DiziPal", "--> iframe attribute'undan URL bulundu: $c")
                        break
                    }
                }
            }
        }

        // === 4. Script içinde video URL'si ara ===
        if (iframeUrl.isEmpty()) {
            val scriptMatches = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .map { it.groupValues[1] }
                .toList()
            Log.d("DiziPal", "--> Toplam ${scriptMatches.size} script etiketi bulundu")

            val urlPatterns = listOf(
                Regex("""["'](https?://[^"']*(?:dplayer|play|embed|iframe|player)[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""src\s*[:=]\s*["'](https?://[^"']+)["']"""),
                Regex("""url\s*[:=]\s*["'](https?://[^"']+)["']"""),
                Regex("""iframe\s*[:=]\s*["'](https?://[^"']+)["']""")
            )

            for (script in scriptMatches) {
                for (pattern in urlPatterns) {
                    val m = pattern.find(script)
                    if (m != null) {
                        val u = m.groupValues[1]
                        if (u.contains("google") || u.contains("facebook") || u.contains("twitter") ||
                            u.contains("analytics") || u.contains("cdn.jsdelivr") || u.contains("schema.org")) continue
                        iframeUrl = u
                        Log.d("DiziPal", "--> Script içinde URL bulundu: ${u.take(150)}")
                        break
                    }
                }
                if (iframeUrl.isNotEmpty()) break
            }
        }

        // === 5. __NEXT_DATA__ JSON içinde ara ===
        if (iframeUrl.isEmpty()) {
            val nextDataMatch = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (nextDataMatch != null) {
                val nextJson = nextDataMatch.groupValues[1]
                Log.d("DiziPal", "--> __NEXT_DATA__ bulundu (uzunluk: ${nextJson.length})")

                val urlPatterns = listOf(
                    Regex(""""(https?://[^"]*(?:dplayer|play|embed|iframe|player)[^"]*)""""),
                    Regex(""""(https?://[^"]*\.m3u8[^"]*)""""),
                    Regex(""""(https?://[^"]*\.mp4[^"]*)""""),
                    Regex(""""(?:videoUrl|iframeUrl|playerUrl|sourceUrl|embedUrl)"\s*:\s*"(https?://[^"]+)"""")
                )
                for (pattern in urlPatterns) {
                    val m = pattern.find(nextJson)
                    if (m != null) {
                        val u = m.groupValues[1].replace("\\/", "/")
                        iframeUrl = u
                        Log.d("DiziPal", "--> __NEXT_DATA__ içinde URL bulundu: ${u.take(150)}")
                        break
                    }
                }
            }
        }

        // === 6. HTML içinde dplayer URL'si ara ===
        if (iframeUrl.isEmpty()) {
            val dplayerMatch = Regex("""https?://[^"'\s<>]*dplayer[^"'\s<>]*""").find(html)
            if (dplayerMatch != null) {
                iframeUrl = dplayerMatch.value
                Log.d("DiziPal", "--> HTML içinde dplayer URL bulundu: ${iframeUrl.take(120)}")
            }
        }

        // === 7. HTML içinde m3u8 URL'si ara ===
        if (iframeUrl.isEmpty()) {
            val m3u8Match = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(html)
            if (m3u8Match != null) {
                Log.d("DiziPal", "--> HTML içinde m3u8 URL bulundu: ${m3u8Match.value.take(120)}")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "DiziPal Direct M3U8",
                        url = m3u8Match.value,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        // === 8. Sonuç ===
        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
            else if (iframeUrl.startsWith("://")) iframeUrl = "https$iframeUrl"
            else if (!iframeUrl.startsWith("http")) iframeUrl = "https://$iframeUrl"

            Log.d("DiziPal", "--> Extractor'a gönderiliyor: ${iframeUrl.take(150)}")
            DiziPalOrijinalPlayer().getUrl(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } else {
            Log.e("DiziPal", "--> HATA: iframeUrl tamamen BOŞ.")
            Log.e("DiziPal", "--> HTML SON 1500 KARAKTER: ${html.takeLast(1500)}")
        }
        return true
    }

    // ============================================================
    // YARDIMCILAR
    // ============================================================
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalParams(ctHexOrB64: String, ivHex: String, saltHex: String, passphrase: String): String {
        return try {
            val salt = saltHex.decodeHex()
            val iv = ivHex.decodeHex()
            val ciphertext = android.util.Base64.decode(ctHexOrB64, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            var finalUrl = String(cipher.doFinal(ciphertext), Charsets.UTF_8).replace("\\/", "/")
            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (finalUrl.startsWith("://")) finalUrl = "https$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"
            Log.d("DiziPal", "--> decryptDizipalParams sonuç: ${finalUrl.take(100)}")
            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPal", "--> Decryption Hatası: ${e.message}")
            ""
        }
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""
            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""
            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) ?: return ""

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            var finalUrl = String(cipher.doFinal(ciphertext), Charsets.UTF_8).replace("\\/", "/")
            if (finalUrl.startsWith("://")) finalUrl = "https$finalUrl"
            else if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
            else if (!finalUrl.startsWith("http")) finalUrl = "https://$finalUrl"
            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPal", "--> HATA: Decryption sırasında Exception: ${e.message}")
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to baseUrl
    )
}
