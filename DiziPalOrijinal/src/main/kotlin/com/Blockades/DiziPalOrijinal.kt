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

    // Yeni site yapısı: platform-bolumler/<slug>, kanal/<slug> ve statik listeleme sayfaları
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
        // Site artık statik HTML döndürüyor; POST endpoint'leri kaldırıldı.
        // Sayfalama için ?sayfa=N dene, yoksa tek sayfa dön.
        val url = if (page <= 1) request.data else "${request.data}?sayfa=$page"
        val document = app.get(url, interceptor = interceptor).document

        // Kart yapısı: <a class="tc" href="/series/..."> ... <span class="fr"> ... <img src="..."> ... <span class="tt">Başlık</span>
        val home = document.select("a.tc")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // href
        val rawHref = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(rawHref) ?: return null

        // poster: <span class="fr"> ... <img src="...">
        val posterUrl = fixUrlNull(
            this.selectFirst("span.fr img")?.attr("src")
                ?: this.selectFirst("img")?.attr("src")
        )

        // başlık: önce <span class="tt">, yoksa img alt
        val titleEl = this.selectFirst("span.tt")
        val title = titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        // puan: aria-label içinde "IMDb 8.6" geçiyor, ondan çekmek daha güvenli
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
        // Yeni site arama sayfası HTML döndürüyor olabilir
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

        // Fallback: eski JSON POST
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

        // Poster: detay sayfasında büyük afiş — birden çok olası seçici
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

        // Puan: sayfadaki IMDb yazısından
        val ratingText = document.selectFirst("ul.rigth-content > li:nth-child(3) div.value")?.text()?.trim()
            ?: Regex("""IMDb\s*([0-9.]+)""").find(document.text())?.groupValues?.get(1)

        val duration = document.selectFirst("ul.rigth-content > li:nth-child(8) > div.value")
            ?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()

        // Bölüm listesi: <a href="/bolum/..."> veya "1x01" içeren linkler
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
        val document = app.get(data, interceptor = interceptor).document

        // 1) Yeni yapı: doğrudan iframe var mı?
        val directIframe = document.selectFirst("iframe[src]")?.attr("src")
        if (!directIframe.isNullOrBlank()) {
            Log.d("kraptor_$name", "directIframe = $directIframe")
            val fixed = fixUrlNull(directIframe)
            if (fixed != null) {
                loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                return true
            }
        }

        // 2) Eski şifreli yapı: div[data-rm-k]
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
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                    return true
                }
            } catch (e: Exception) {
                Log.e("kraptor_$name", "Decryption failed: ${e.message}")
            }
        }

        // 3) Fallback: sayfadaki tüm iframe'leri dene
        var any = false
        document.select("iframe[src]").forEach { f ->
            val src = f.attr("src")
            if (src.isNotBlank()) {
                val fixed = fixUrlNull(src)
                if (fixed != null) {
                    loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                    any = true
                }
            }
        }
        return any
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
