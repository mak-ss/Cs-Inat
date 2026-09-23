// ! Bu araç CloudStream eklentisi için güncellenmiştir.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal10.com.tr"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // Cloudflare Bypass
    override var sequentialMainPage = true
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    // Standart Headers
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val res = chain.proceed(req)
            val body = res.peekBody(1024 * 1024).string()
            val doc  = runCatching { Jsoup.parse(body) }.getOrNull()
            val title = doc?.selectFirst("title")?.text()?.trim().orEmpty()
            return if (title.equals("Just a moment...", true) || title.contains("Bir dakika", true)) {
                cloudflareKiller.intercept(chain)
            } else res
        }
    }

    /* -------------------- MainPage -------------------- */

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Son Eklenen Diziler",
        "$mainUrl/filmler" to "Son Eklenen Filmler",
        "$mainUrl/animeler" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url, headers = defaultHeaders, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        val items = doc.select("a.homepage-card, a.trend-card, a.grid-card, article a[href]")
            .distinctBy { it.attr("href") }

        items.forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/anime/")) return@forEach

            val imgEl = a.selectFirst("img")
            val poster = fixUrlNull(
                imgEl?.attr("src")?.takeIf { !it.startsWith("data:") }
                    ?: imgEl?.attr("data-src")
                    ?: imgEl?.attr("srcset")?.substringBefore(" ")
            )

            val title = a.selectFirst(".homepage-card-title, .trend-card-title, h3, h2")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }.ifBlank { href.substringAfterLast("/").replace("-", " ") }

            if (href.contains("/film/")) {
                cards += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }

        return newHomePageResponse(request.name, cards, hasNext = cards.isNotEmpty())
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.encodeURL()}"
        val doc = app.get(searchUrl, headers = defaultHeaders, interceptor = interceptor, referer = "$mainUrl/").document
        val out = mutableListOf<SearchResponse>()

        doc.select("a.homepage-card, a.grid-card, a[href*='/dizi/'], a[href*='/film/']").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            val imgEl = a.selectFirst("img")
            val poster = fixUrlNull(
                imgEl?.attr("data-src") ?: imgEl?.attr("src")
            )
            val title = a.selectFirst(".homepage-card-title, h3, h2")?.text()?.trim()
                ?: a.text().ifBlank { href.substringAfterLast("/") }

            when {
                href.contains("/film/") -> out += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                href.contains("/dizi/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders, interceptor = interceptor, referer = "$mainUrl/").document

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst("img[src*='image.tmdb.org']")?.attr("src")
        )

        val title = doc.selectFirst("h1, .site-intro-title")?.text()?.trim()
            ?: doc.title().substringBefore("—").substringBefore("|").trim()

        return when {
            url.contains("/dizi/") || url.contains("/anime/") -> {
                val eps = doc.select("a[href*='-sezon/'], a[href*='/bolum']")
                    .mapNotNull { a ->
                        val href = normalizeHref(a.attr("href")) ?: return@mapNotNull null
                        val (s, e) = parseSeasonEpisode(href, a.text())
                        newEpisode(href) {
                            name = a.text().ifBlank { "Bölüm $e" }
                            season = s
                            episode = e
                        }
                    }
                    .distinctBy { it.data }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                    this.posterUrl = poster
                }
            }

            url.contains("/film/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String, textRaw: String): Pair<Int?, Int?> {
        Regex("""/(\d+)-sezon/(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val text = textRaw.lowercase()
        val s = Regex("""(\d+)\s*\.?\s*sezon""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val e = Regex("""(\d+)\s*\.?\s*bolum""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return s to e
    }

    /* -------------------- Links (player) -------------------- */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "loadLinks baslatildi: $data")

        var found = false
        val reqHeaders = defaultHeaders + mapOf("Referer" to "$mainUrl/")

        // 1) Ana bölüm/film sayfasını çek
        val res = app.get(data, headers = reqHeaders, interceptor = interceptor)
        val body = res.text
        val doc = res.document

        // Sayfa metni içinden (.m3u8, /stream/ vb.) direkt linkleri ara
        if (pushM3u8s(body, referer = data, callback)) {
            found = true
        }

        // 2) HTML içerisindeki iframe, video kaynakları ve data-src niteliklerini tara
        val elements = doc.select("iframe[src], iframe[data-src], iframe[data-player], video source[src]")
        
        elements.forEach { element ->
            val rawSrc = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-player") }

            val src = normalizeHref(rawSrc) ?: return@forEach

            if (pushM3u8s(src, referer = data, callback)) {
                found = true
            } else {
                // CloudStream yerleşik Extractor'larını çalıştır
                if (loadExtractor(src, referer = data, subtitleCallback, callback)) {
                    found = true
                } else {
                    // Iframe kaynağına girip metin içerisinden m3u8 ayıkla
                    runCatching {
                        val iframeRes = app.get(src, headers = reqHeaders, referer = data, interceptor = interceptor).text
                        if (pushM3u8s(iframeRes, referer = src, callback)) {
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }

    /** Metin içerisinden .m3u8 ve video akış adreslerini ayıklar ve oynatıcıya iletir */
    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        // JSON formatındaki kaçış karakterlerini (\/) düzgün URL biçimine çevir
        val unescapedText = text.replace("\\/", "/")

        // .m3u8 veya /stream/ uzantılı tüm HTTP/HTTPS URL'lerini yakalayan regex
        val regex = Regex("""https?://[^\s"'<>\\]+(?:\.m3u8|/stream/[^\s"'<>\\]+)""")

        regex.findAll(unescapedText).map { it.value }.distinct().forEach { cleanUrl ->
            runCatching {
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = cleanUrl,
                    referer   = referer,
                    name      = name
                ).forEach(callback)
                any = true
            }
        }
        return any
    }

    /* -------------------- Utils -------------------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)
}
