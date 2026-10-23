package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOrijinal : MainAPI() {
    override var mainUrl = "https://dizipal1591.com"
    override var name = "DiziPalOrg"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                Log.d("kraptor_Dizipal", "!!cloudflare geldi!!")
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-eklenen-dizi-bolumler" to "Yeni Eklenen Bölümler",
        "${mainUrl}/yabanci-dizi-izle" to "Yeni Eklenenler",
        "${mainUrl}/platform-bolumler/netflix" to "Netflix Dizileri",
        "${mainUrl}/platform-bolumler/prime" to "Amazon Dizileri",
        "${mainUrl}/platform-bolumler/max" to "Max Dizileri",
        "${mainUrl}/platform-bolumler/disneyplus" to "Disney+ Dizileri",
        "${mainUrl}/platform-bolumler/exxen" to "Exxen Dizileri",
        "${mainUrl}/platform-bolumler/apple-tv" to "Apple TV+ Dizileri",
        "${mainUrl}/platform-bolumler/tabii" to "Tabii Dizileri",
        "${mainUrl}/platform-bolumler/tod" to "TOD Dizileri",
        "${mainUrl}/film-izle" to "Yeni Filmler",
    )

    private var sessionCookies: Map<String, String>? = null
    private var cKey: String? = null
    private var cValue: String? = null
    private val initMutex = Mutex()

    private suspend fun initSession() {
        if (sessionCookies != null && cKey != null && cValue != null) return
        initMutex.withLock {
            if (sessionCookies != null && cKey != null && cValue != null) return@withLock
            Log.d("kraptor_Dizipal", "🔄 Oturum başlatılıyor")
            val resp = app.get(mainUrl, interceptor = interceptor, timeout = 120, headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                "Referer" to "${mainUrl}/",
            ))
            sessionCookies = resp.cookies.mapValues { (_, v) -> URLDecoder.decode(v, "UTF-8") }
            val document = resp.document
            cKey = document.selectFirst("input[name=cKey]")?.`val`()
            cValue = document.selectFirst("input[name=cValue]")?.`val`()
            Log.d("kraptor_Dizipal", "cKey: $cKey, cValue: $cValue")
        }
    }

    // ---------------------------------------------------------------------
    // ANA SAYFA
    // ---------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?sayfa=$page"
        val document = app.get(url, interceptor = interceptor).document

        val home = document.select("a.tc")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val rawHref = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(rawHref) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("span.fr img")?.attr("src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val titleEl = this.selectFirst("span.tt")
        val title = titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val aria = this.attr("aria-label")
        val imdbScore = Regex("""IMDb\s+([0-9.]+)""").find(aria)?.groupValues?.get(1)
            ?: this.selectFirst("span.fr > span:not(.tt)")?.text()
                ?.takeIf { it.matches(Regex("""[0-9]+(\.[0-9]+)?""")) }

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(imdbScore)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(imdbScore)
            }
        }
    }

    // ---------------------------------------------------------------------
    // ARAMA
    // ---------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val resp = app.get(
                "$mainUrl/arama-yap?q=${URLEncoder.encode(query, "UTF-8")}",
                interceptor = interceptor
            )
            val fromHtml = resp.document.select("a.tc").mapNotNull { it.toMainPageResult() }
            if (fromHtml.isNotEmpty()) return fromHtml
        } catch (e: Exception) {
            Log.e("kraptor_Dizipal", "HTML search failed: ${e.message}")
        }

        initSession()
        if (cKey == null || cValue == null) return emptyList()
        return try {
            val body = app.post(
                "$mainUrl/bg/searchcontent", interceptor = interceptor, data = mapOf(
                    "cKey" to cKey!!, "cValue" to cValue!!, "searchterm" to query
                ), headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                )
            ).text
            val json = JSONObject(body)
            val resultList = json.getJSONObject("data").optJSONArray("result") ?: return emptyList()
            (0 until resultList.length()).mapNotNull { i ->
                val item = resultList.getJSONObject(i)
                val title = item.optString("object_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val slug = item.optString("used_slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = fixUrlNull("$mainUrl/$slug") ?: return@mapNotNull null
                val posterUrl = item.optString("object_poster_url").takeIf { it.isNotBlank() }
                if (href.contains("/movies/")) {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
                }
            }
        } catch (e: Exception) {
            Log.e("kraptor_Dizipal", "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------------------------------------------------------------------
    // DETAY SAYFASI
    // ---------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val getUrl = app.get(url, interceptor = interceptor)
        val document = getUrl.document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img.w-full.h-full.object-cover")?.attr("src")
                ?: document.selectFirst("img[alt][src*='poster']")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst("p.text-white.text-base")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("ul.rigth-content > li:nth-child(5) a").map { it.text() }
            .ifEmpty {
                document.select("a[href^='/tur/'], a[href^='/kanal/']").map { it.text() }
            }

        val ratingText = document.selectFirst("ul.rigth-content > li:nth-child(3) div.value")?.text()?.trim()
            ?: Regex("""IMDb\s*([0-9.]+)""").find(document.text())?.groupValues?.get(1)

        val duration = document.selectFirst("ul.rigth-content > li:nth-child(8) > div.value")
            ?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()

        val episodeLinks = document.select("a[href*='/bolum/'], a[href*='-bolum']")
            .filter {
                val h = it.attr("href")
                h.contains("/bolum/") || h.matches(Regex(""".*\d+x\d+.*"""))
            }

        val bolumler = episodeLinks.mapNotNull { el ->
            val bolumHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            if (bolumHref.isBlank()) return@mapNotNull null

            val text = el.text()
            val se = Regex("""(\d+)\s*[xX.]\s*(\d+)""").find(text)
            val season = se?.groupValues?.get(1)?.toIntOrNull()
                ?: el.selectFirst("[class*=opacity]")?.text()?.substringBefore(".")?.toIntOrNull()
            val episode = se?.groupValues?.get(2)?.toIntOrNull()
                ?: el.selectFirst("[class*=opacity]")?.text()?.substringAfterLast(" ")?.toIntOrNull()

            newEpisode(bolumHref) {
                this.name = "Bölüm"
                this.season = season
                this.episode = episode
            }
        }

        return if (url.contains("/movies/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingText)
                this.duration = duration
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, bolumler) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(ratingText)
                this.duration = duration
            }
        }
    }

    // ---------------------------------------------------------------------
    // LİNKLER
    // ---------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "data = $data")
        val response = app.get(data, interceptor = interceptor)
        val html = response.text
        val document = response.document

        Log.d("kraptor_$name", "HTML uzunluk: ${html.length}")
        Log.d("kraptor_$name", "iframe toplam: ${document.select("iframe").size}, src'li iframe: ${document.select("iframe[src]").size}, data-rm-k: ${document.select("[data-rm-k]").size}")
        Log.d("kraptor_$name", "dplayer82 geçiyor mu: ${html.contains("dplayer82")}, iframe.php geçiyor mu: ${html.contains("iframe.php")}, contentx geçiyor mu: ${html.contains("contentx")}")

        var anyFound = false

        // 1) HTML içindeki player/iframe URL'lerini regex ile çıkar
        val playerRegex = Regex(
            """(https?://[^\s"'\\]+(?:dplayer82|player|iframe|embed|contentx|playru|pichive|trstx|hotlinger|rapidvid|vidmoxy|sobreatsesuyp|turbo\.imgz|turkeyplayer)[^\s"'\\]*)""",
            RegexOption.IGNORE_CASE
        )
        val foundUrls = playerRegex.findAll(html)
            .map { it.value }
            .filter { it.contains("://") }
            .toSet()

        Log.d("kraptor_$name", "html'de bulunan player URL'leri: $foundUrls")

        for (u in foundUrls) {
            val fixed = fixUrlNull(u) ?: continue
            Log.d("kraptor_$name", "denenen URL: $fixed")
            try {
                if (fixed.contains("dplayer82.site")) {
                    DPlayer82().getUrl(fixed, "$mainUrl/", subtitleCallback, callback)
                } else {
                    loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                }
                anyFound = true
            } catch (e: Exception) {
                Log.e("kraptor_$name", "extractor hatası: ${e.message}")
            }
        }
        if (anyFound) return true

        // 2) Doğrudan iframe[src]
        val directIframe = document.selectFirst("iframe[src]")?.attr("src")
        if (!directIframe.isNullOrBlank()) {
            val fixed = fixUrlNull(directIframe)
            if (fixed != null) {
                Log.d("kraptor_$name", "directIframe = $fixed")
                if (fixed.contains("dplayer82.site")) {
                    DPlayer82().getUrl(fixed, "$mainUrl/", subtitleCallback, callback)
                } else {
                    loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                }
                return true
            }
        }

        // 3) data-rm-k şifreli yapı
        val hiddenEl = document.selectFirst("div[data-rm-k]")
            ?: document.selectFirst("[data-rm-k]")
        if (hiddenEl != null) {
            val hiddenJson = hiddenEl.text()
            val key =
                "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
            try {
                val obj = JSONObject(hiddenJson)
                val decryptedContent = decrypt(
                    key,
                    obj.getString("salt"),
                    obj.getString("iv"),
                    obj.getString("ciphertext")
                )
                Log.d("kraptor_$name", "decryptedContent = $decryptedContent")
                val iframe = fixUrlNull(decryptedContent)
                if (iframe != null) {
                    if (iframe.contains("dplayer82.site")) {
                        DPlayer82().getUrl(iframe, "$mainUrl/", subtitleCallback, callback)
                    } else {
                        loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                    }
                    return true
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "Decryption failed: ${e.message}")
            }
        }

        // 4) __next_f.push script'lerindeki JSON içinden URL ara
        val nextDataRegex = Regex("""self\.__next_f\.push\(\[1,\s*"(.+?)"\]\)""")
        val nextMatches = nextDataRegex.findAll(html).map { it.groupValues[1] }.toList()
        Log.d("kraptor_$name", "__next_f.push script sayısı: ${nextMatches.size}")

        for (block in nextMatches) {
            val unescaped = block
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\\\", "\\")

            val innerRegex = Regex(
                """(https?://[^\s"']+(?:iframe|player|embed|dplayer|contentx|trstx)[^\s"']*)""",
                RegexOption.IGNORE_CASE
            )
            val innerUrls = innerRegex.findAll(unescaped).map { it.value }.toSet()
            for (u in innerUrls) {
                val fixed = fixUrlNull(u) ?: continue
                Log.d("kraptor_$name", "next_f içinden URL: $fixed")
                try {
                    if (fixed.contains("dplayer82.site")) {
                        DPlayer82().getUrl(fixed, "$mainUrl/", subtitleCallback, callback)
                    } else {
                        loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                    }
                    anyFound = true
                } catch (e: Exception) {
                    Log.e("kraptor_$name", "next_f extractor hatası: ${e.message}")
                }
            }
        }
        if (anyFound) return true

        // 5) Fallback: tüm iframe'leri dene
        document.select("iframe[src]").forEach { f ->
            val src = f.attr("src")
            if (src.isNotBlank()) {
                val fixed = fixUrlNull(src)
                if (fixed != null) {
                    if (fixed.contains("dplayer82.site")) {
                        DPlayer82().getUrl(fixed, "$mainUrl/", subtitleCallback, callback)
                    } else {
                        loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                    }
                    anyFound = true
                }
            }
        }

        // 6) Debug: elementlerdeki data-* attribute'larını logla (sorun tespiti için)
        document.select("[data-player-frame], [data-preroll-slot], [data-before-image], [data-rm-k]").forEach { el ->
            Log.d("kraptor_$name", "DEBUG element <${el.tagName()}> attr'lar: ${el.attributes().joinToString { "${it.key}=${it.value.take(120)}" }}")
        }

        Log.d("kraptor_$name", "loadLinks sonucu: $anyFound")
        return anyFound
    }
}

fun decrypt(
    passphrase: String,
    saltHex: String,
    ivHex: String,
    ciphertextBase64: String
): String {
    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val iv = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
    val tmp = factory.generateSecret(spec)
    val secret = SecretKeySpec(tmp.encoded, "AES")
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
    val decoded = base64DecodeArray(ciphertextBase64)
    val plaintextBytes = cipher.doFinal(decoded)
    return String(plaintextBytes, Charsets.UTF_8)
}
