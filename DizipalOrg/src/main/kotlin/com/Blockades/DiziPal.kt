// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
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

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/bolumler" to "Son Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPageQuery(page)
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        val anchors = doc.select("a[href^=\"/filmler/\"], a[href^=\"/diziler/\"]")
            .distinctBy { it.attr("href") }

        for (a in anchors) {
            val href = normalizeHref(a.attr("href")) ?: continue
            if (href.endsWith("/filmler") || href.endsWith("/diziler")) continue

            val img = a.selectFirst("img")
            val poster = img?.attr("src")?.let { fixUrlNull(it) }
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

        val hasNext = doc.select("a[href*=\"?page=\"], a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, cards.distinctBy { it.url }, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
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

        val poster = fixUrlNull(
            doc.selectFirst("img[alt][src*=\"/storage/posters/\"]")?.attr("src")
                ?: doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
        )

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: doc.title().substringBefore("|").trim()
        val title = rawTitle.removeSuffix(" izle").trim()

        return when {
            url.contains("/diziler/") -> {
                val eps = mutableListOf<Episode>()
                doc.select("a[href^=\"/bolumler/\"]").distinctBy { it.attr("href") }.forEach { a ->
                    val href = normalizeHref(a.attr("href")) ?: return@forEach
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
        Regex("""-s(\d+)b(\d+)""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
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

        val doc = runCatching {
            app.get(data, interceptor = interceptor, referer = "$mainUrl/").document
        }.getOrNull() ?: return false

        // 1) Sayfadaki iframe'leri topla
        val iframeUrls = mutableListOf<String>()
        doc.select("iframe[src]").forEach { ifr ->
            normalizeHref(ifr.attr("src"))?.let { iframeUrls += it }
        }
        // 2) JSON-LD VideoObject embedUrl
        doc.select("script[type=application/ld+json]").forEach { script ->
            val json = script.data()
            if (json.contains("\"VideoObject\"") || json.contains("\"embedUrl\"")) {
                Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"").find(json)
                    ?.groupValues?.getOrNull(1)?.let { iframeUrls += it }
            }
        }

        if (iframeUrls.isEmpty()) {
            Log.e("DZP", "Hiç iframe bulunamadı")
            return false
        }

        // 3) Her iframe için player'ı çöz
        for (playerUrl in iframeUrls.distinct()) {
            Log.d("DZP", "Player: $playerUrl")
            if (resolvePlayer(playerUrl, data, subtitleCallback, callback)) return true
        }

        // 4) Son çare: sayfa HTML'inde storagedomain pattern ara
        if (huntStorageM3u8(doc.html(), referer = data, callback)) return true

        return false
    }

    /**
     * videoplays.cfd player sayfasını açar; içindeki API çağrısını veya
     * doğrudan m3u8 linkini bulur.
     */
    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 4.1) Bilinen extractor'lara şans ver
        if (loadExtractor(playerUrl, referer, subtitleCallback, callback)) return true

        // 4.2) Player HTML'ini çek
        val res = runCatching {
            app.get(playerUrl, referer = referer, interceptor = interceptor)
        }.getOrNull() ?: return false

        val body = res.text
        val pdoc = res.document

        // 4.3) HTML/JS içinde token'lı master.m3u8 var mı?
        if (huntStorageM3u8(body, referer = playerUrl, callback)) return true

        // 4.4) Sayfa içindeki script src'lerini de indirip tara
        pdoc.select("script[src]").forEach { s ->
            val src = normalizeHref(s.attr("src")) ?: return@forEach
            val js = runCatching { app.get(src, referer = playerUrl, interceptor = interceptor).text }.getOrNull()
                ?: return@forEach
            if (huntStorageM3u8(js, referer = playerUrl, callback)) return true
        }

        // 4.5) Player'ın kendi API'sini dene
        val videoId = Regex("""[?&]video=(\d+)""").find(playerUrl)?.groupValues?.getOrNull(1)
        if (videoId != null) {
            val apiCandidates = listOf(
                "https://videoplays.cfd/api/video/$videoId",
                "https://videoplays.cfd/api/videos/$videoId",
                "https://videoplays.cfd/api/player/$videoId",
                "https://videoplays.cfd/api/get/$videoId",
                "https://videoplays.cfd/get/$videoId",
                "https://videoplays.cfd/api/v1/video/$videoId",
            )
            for (api in apiCandidates) {
                val apiRes = runCatching {
                    app.get(api, referer = playerUrl, interceptor = interceptor)
                }.getOrNull() ?: continue
                val apiBody = apiRes.text
                Log.d("DZP", "API denemesi: $api → ${apiBody.take(120)}")
                if (huntStorageM3u8(apiBody, referer = playerUrl, callback)) return true
            }
        }

        // 4.6) m3u8 değil mp4 olabilir
        if (pushMp4s(body, referer = playerUrl, callback)) return true

        return false
    }

    /** HTML/JS içinde storagedomain.cfd/.../master.m3u8?token=... pattern'ini ara. */
    private suspend fun huntStorageM3u8(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Token'lı master.m3u8 (storagedomain.cfd veya *domain.cfd)
        val masterRegex = Regex(
            """https?://[a-zA-Z0-9.-]+\.cfd/\d+/master\.m3u8\?token=[A-Za-z0-9._\-]+"""
        )
        masterRegex.findAll(text).map { it.value }.distinct().forEach { url ->
            Log.d("DZP", "master.m3u8 bulundu: ${url.take(80)}...")
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = url,
                referer = referer,
                name = name
            ).forEach(callback)
            found = true
        }

        // Token'sız video_0/stream.m3u8 (master bozulursa fallback)
        if (!found) {
            val streamRegex = Regex(
                """https?://[a-zA-Z0-9.-]+\.cfd/\d+/video_\d+/stream\.m3u8[^\s"'<>\\]*"""
            )
            streamRegex.findAll(text).map { it.value }.distinct().forEach { url ->
                Log.d("DZP", "video stream.m3u8 bulundu: ${url.take(80)}...")
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = url,
                    referer = referer,
                    name = name
                ).forEach(callback)
                found = true
            }
        }

        // Genel m3u8 yakalayıcı (diğer domainler için fallback)
        if (!found) {
            if (pushM3u8s(text, referer, callback)) found = true
        }

        return found
    }

    /** Metin içindeki herhangi bir .m3u8 linkini ekle (genel fallback). */
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

    /** Metin içindeki .mp4 linklerini ekle. */
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
