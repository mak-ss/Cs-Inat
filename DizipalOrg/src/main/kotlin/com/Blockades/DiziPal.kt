// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipalorjinal21.com"
    override var name = "DiziPalOrg"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val res = chain.proceed(req)
            val body = res.peekBody(512 * 1024).string()
            val doc = runCatching { Jsoup.parse(body) }.getOrNull()
            val title = doc?.selectFirst("title")?.text()?.trim().orEmpty()
            return if (title.equals("Just a moment...", true) ||
                title.contains("Bir dakika", true) ||
                title.contains("Attention Required", true)
            ) {
                cloudflareKiller.intercept(chain)
            } else res
        }
    }

    /* -------------------- MainPage -------------------- */

    // DÜZELTME: Site Next.js SSR. Sayfalama "?page=N" ile çalışıyor.
    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/bolumler" to "Son Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPageQuery(page)
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        // DÜZELTME: Gerçek seçici — tüm /filmler/ ve /diziler/ linklerini al
        val anchors = doc.select("a[href^=\"/filmler/\"], a[href^=\"/diziler/\"]")
            .distinctBy { it.attr("href") }

        for (a in anchors) {
            val href = normalizeHref(a.attr("href")) ?: continue
            if (href.endsWith("/filmler") || href.endsWith("/diziler")) continue

            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.let { fixUrlNull(it) }
            // DÜZELTME: Başlık önce h3, sonra img alt, sonra a.title
            val title = a.selectFirst("h3")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: a.attr("title").ifBlank { a.text() }.ifBlank {
                    href.substringAfterLast("/").replace("-", " ").trim()
                }

            when {
                href.contains("/diziler/") -> cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
                href.contains("/filmler/") -> cards += newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        // DÜZELTME: Next.js'te "next" linki genelde /filmler?page=N şeklinde
        val hasNext = doc.select("a[href*=\"?page=\"], a[rel=next]").isNotEmpty()

        return newHomePageResponse(request.name, cards.distinctBy { it.url }, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        // DÜZELTME: Site aramasi hâlâ /arama?q= ile çalışıyor (JSON-LD SearchAction)
        val url = "$mainUrl/arama?q=${query.encodeURL()}"
        val doc = runCatching {
            app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<SearchResponse>()
        doc.select("a[href^=\"/filmler/\"], a[href^=\"/diziler/\"]")
            .distinctBy { it.attr("href") }
            .forEach { a ->
                val href = normalizeHref(a.attr("href")) ?: return@forEach
                if (href.endsWith("/filmler") || href.endsWith("/diziler")) return@forEach

                val img = a.selectFirst("img")
                val poster = img?.attr("src")?.let { fixUrlNull(it) }
                val title = a.selectFirst("h3")?.text()?.trim()
                    ?: img?.attr("alt")?.trim()
                    ?: href.substringAfterLast("/").replace("-", " ").trim()

                when {
                    href.contains("/diziler/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                    href.contains("/filmler/") -> out += newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }
        return out
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        // DÜZELTME: Poster = sayfa içindeki büyük img, fallback = og:image (o arkaplan ama iş görür)
        val poster = fixUrlNull(
            doc.selectFirst("img[alt][src*=\"/storage/posters/\"]")?.attr("src")
                ?: doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        )

        // DÜZELTME: h1 "Kavgacılar izle" şeklinde, " izle" sonekini temizle
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.title().substringBefore("|").trim()
        val title = rawTitle.removeSuffix(" izle").trim()

        return when {
            url.contains("/diziler/") -> {
                // Bölümler SSR ile statik HTML'de geliyor: /bolumler/ linkleri
                val eps = mutableListOf<Episode>()
                doc.select("a[href^=\"/bolumler/\"]").distinctBy { it.attr("href") }.forEach { a ->
                    val href = normalizeHref(a.attr("href")) ?: return@forEach
                    // Bölüm başlığı: kart içindeki p'lerden biri "X. Sezon Y. Bölüm"
                    val text = a.text()
                    val (s, e) = parseSeasonEpisode(href, text)
                    eps += newEpisode(href) {
                        name = text.ifBlank { "Bölüm ${e ?: ""}" }
                        season = s
                        episode = e
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.distinctBy { it.data }) {
                    this.posterUrl = poster
                }
            }

            url.contains("/filmler/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            url.contains("/bolumler/") -> {
                val (s, e) = parseSeasonEpisode(url, doc.selectFirst("h1")?.text().orEmpty())
                val ep = newEpisode(url) {
                    name = doc.selectFirst("h1")?.text()?.trim()
                    season = s
                    episode = e
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(ep)) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private fun parseSeasonEpisode(href: String, textRaw: String): Pair<Int?, Int?> {
        // DÜZELTME: Yeni URL formatı: /bolumler/alien-earth-s1b8
        Regex("""-s(\d+)b(\d+)""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        // Fallback: metinden çıkar
        val text = textRaw.lowercase(Locale.ROOT)
            .replace('ö', 'o').replace('ü', 'u').replace('ı', 'i')
            .replace('ş', 's').replace('ç', 'c').replace('ğ', 'g')
        val s = Regex("""(\d+)\s*\.?\s*sezon""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        Log.d("DZP", "loadLinks: $data")

        // DÜZELTME: Detay sayfasını çek, video kaynağı artık iframe içinde
        val doc = runCatching {
            app.get(data, interceptor = interceptor, referer = "$mainUrl/").document
        }.getOrNull() ?: return false

        // 1) JSON-LD VideoObject içindeki embedUrl (en güvenilir yol)
        val jsonLd = doc.select("script[type=application/ld+json]").firstOrNull { it.data().contains("\"VideoObject\"") }
        jsonLd?.let { script ->
            val json = script.data()
            Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)?.let { embed ->
                Log.d("DZP", "JSON-LD embedUrl: $embed")
                if (loadExtractor(embed, data, subtitleCallback, callback)) return true
                if (extractFromPlayer(embed, data, subtitleCallback, callback)) return true
            }
        }

        // 2) Sayfadaki iframe'ler (videoplays.cfd)
        doc.select("iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            Log.d("DZP", "iframe: $src")
            if (loadExtractor(src, data, subtitleCallback, callback)) return@forEach
            if (extractFromPlayer(src, data, subtitleCallback, callback)) return@forEach
        }

        // 3) HTML içinde m3u8/mp4 var mı?
        val html = doc.html()
        if (pushM3u8s(html, referer = data, callback)) return true
        if (pushMp4s(html, referer = data, callback)) return true

        return false
    }

    /** videoplays.cfd/player.html?video=... sayfasından m3u8/mp4 çıkar. */
    private suspend fun extractFromPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = runCatching { app.get(url, referer = referer, interceptor = interceptor) }.getOrNull() ?: return false
        val body = res.text

        if (pushM3u8s(body, referer, callback)) return true
        if (pushMp4s(body, referer, callback)) return true

        // Player içinde gizli iframe olabilir
        res.document.select("iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            if (loadExtractor(src, referer, subtitleCallback, callback)) return@forEach
            val subRes = runCatching { app.get(src, referer = url, interceptor = interceptor) }.getOrNull() ?: return@forEach
            if (pushM3u8s(subRes.text, url, callback)) return@forEach
            if (pushMp4s(subRes.text, url, callback)) return@forEach
        }
        return false
    }

    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
            .findAll(text).map { it.value }.distinct().forEach { m3u ->
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = fixUrl(m3u),
                    referer = referer,
                    name = name
                ).forEach(callback)
                any = true
            }
        return any
    }

    private suspend fun pushMp4s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
            .findAll(text).map { it.value }.distinct().forEach { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(link),
                        type = ExtractorLinkType.VIDEO
                    ) { this.referer = referer }
                )
                any = true
            }
        return any
    }

    /* -------------------- Utils -------------------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    // DÜZELTME: Next.js yeni format "?page=N" kullanıyor
    private fun String.addPageQuery(page: Int): String {
        if (page <= 1) return this
        return if (this.contains("?")) {
            if (this.contains("page=")) this.replace(Regex("page=\\d+"), "page=$page")
            else "$this&page=$page"
        } else "$this?page=$page"
    }

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)
}
