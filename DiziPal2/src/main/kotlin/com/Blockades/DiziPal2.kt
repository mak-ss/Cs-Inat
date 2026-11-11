// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal2 : MainAPI() {
    override var mainUrl              = "https://dizipal.rest"
    override var name                 = "DiziPal2"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

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
        "$mainUrl/diziler"               to "Diziler",
        "$mainUrl/filmler"               to "Filmler",
        "$mainUrl/bolumler"              to "Son Bölümler",
        "$mainUrl/platform/blutv"        to "BluTV",
        "$mainUrl/platform/crunchyroll"  to "Crunchyroll",
        "$mainUrl/platform/netflix"      to "Netflix",
        "$mainUrl/platform/exxen"        to "Exxen",
        "$mainUrl/platform/prime-video"  to "Prime Video",
        "$mainUrl/platform/tabii"        to "Tabii",
        "$mainUrl/platform/apple-tv"     to "Apple TV+",
        "$mainUrl/platform/disney"       to "Disney+",
        "$mainUrl/platform/hbomax"       to "HBO Max",
        "$mainUrl/platform/gain"         to "Gain",
        "$mainUrl/platform/mubi"         to "Mubi",
        "$mainUrl/platform/tod"          to "TOD",
        "$mainUrl/platform/tv"           to "TV+",
        "$mainUrl/platform/hulu"         to "Hulu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.addPagePath(page)

        val res = try {
            app.get(
                url,
                interceptor = interceptor,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            )
        } catch (e: Exception) {
            Log.e("DZP", "getMainPage istek hatası: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val doc = res.document
        val cards = mutableListOf<SearchResponse>()

        val items = doc.select(".homepage-card[href], .trend-card[href]")
            .filter { a ->
                val h = a.attr("href")
                h.contains("/dizi/") || h.contains("/film/")
            }
            .distinctBy { it.attr("href") }

        val finalItems = if (items.isEmpty()) {
            doc.select("a[href*=/dizi/], a[href*=/film/]")
                .filter { a ->
                    val h = a.attr("href")
                    (h.contains("/dizi/") && !h.contains("/sezon/") && !h.contains("-bolum")) ||
                    (h.contains("/film/") && !h.contains("-bolum"))
                }
                .distinctBy { it.attr("href") }
        } else items

        finalItems.forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            val container = a.closest(".homepage-card")
                ?: a.closest(".trend-card")
                ?: a.parent()
                ?: a

            val img = container.selectFirst("img")
            val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let(::fixUrlNull)

            val title = container.selectFirst(
                ".homepage-card-title, .trend-card-title"
            )?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            if (href.contains("/dizi/")) {
                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                cards += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.select(".pagination a, a[rel=next], .section-more-btn").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query.encodeURL()}"
        val doc = try {
            app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        } catch (e: Exception) {
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        val items = doc.select(".homepage-card[href], .trend-card[href]").let {
            if (it.isEmpty()) doc.select("a[href*=/dizi/], a[href*=/film/]") else it
        }

        items.forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach
            if (href.contains("/sezon/") || href.contains("-bolum")) return@forEach

            val container = a.closest(".homepage-card")
                ?: a.closest(".trend-card")
                ?: a.parent()
                ?: a

            val img = container.selectFirst("img")
            val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let(::fixUrlNull)

            val title = container.selectFirst(
                ".homepage-card-title, .trend-card-title"
            )?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            when {
                href.contains("/dizi/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                href.contains("/film/") -> out += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        } catch (e: Exception) {
            return null
        }

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst(".series-poster img, .dp-sidebar-poster img, .dp-poster-small img")?.attr("src")
        )

        val title = doc.selectFirst(".series-title, .dp-series-title, h1")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: doc.title().substringBefore("|").trim()

        return when {
            url.contains("/dizi/") || url.contains("-sezon/") -> {
                val slug = url.substringAfter("/dizi/").substringBefore("/").substringBefore("?")
                val eps = mutableListOf<Episode>()

                val dataJson = Regex(
                    """window\.episodesData\s*=\s*(\{.*?\});""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(doc.html())?.groupValues?.getOrNull(1)

                if (!dataJson.isNullOrBlank()) {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val seasons = jacksonObjectMapper()
                            .readValue<Map<String, Map<String, Map<String, Any?>>>>(dataJson)

                        for ((seasonStr, episodes) in seasons) {
                            val seasonNum = seasonStr.toIntOrNull() ?: 1
                            for ((epStr, info) in episodes) {
                                val epNum = epStr.toIntOrNull()
                                val epTitle = info["title"]?.toString()?.trim()
                                    ?: epNum?.let { "$it. Bölüm" } ?: "Bölüm"
                                val href = if (epNum != null)
                                    "$mainUrl/dizi/$slug/${seasonNum}-sezon/${epNum}-bolum"
                                else "$mainUrl/dizi/$slug"
                                eps += newEpisode(href) {
                                    name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                }
                            }
                        }
                    }.onFailure { Log.e("DZP", "episodesData parse: ${it.message}") }
                }

                if (eps.isEmpty()) {
                    doc.select("""a[href*="/sezon/"][href*="-bolum"]""").forEach { a ->
                        val href = normalizeHref(a.attr("href")) ?: return@forEach
                        val (s, e) = parseSeasonEpisode(href, a.text())
                        eps += newEpisode(href) {
                            name = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm ${e ?: ""}" }
                            season = s
                            episode = e
                        }
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.distinctBy { it.data }) {
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
        Regex("""(\d+)-sezon/(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("""-(\d+)-sezon-(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val text = textRaw.lowercase()
            .replace('ö','o').replace('ü','u').replace('ı','i')
            .replace('ş','s').replace('ç','c').replace('ğ','g')
        val s = Regex("""(\d+)\s*\.?\s*sezon""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = Regex("""(\d+)\s*\.?\s*bolum""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return s to e
    }

    /* -------------------- loadLinks -------------------- */

    data class EncPayload(val ciphertext: String, val iv: String, val salt: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "loadLinks: $data")

        val page = try {
            app.get(data, interceptor = interceptor, referer = "$mainUrl/").document
        } catch (e: Exception) {
            Log.e("DZP", "loadLinks sayfa hatası: ${e.message}")
            return false
        }

        val html = page.html()
        Log.d("DZP", "iframes: ${page.select("iframe").size}")

        // 0) Sayfada doğrudan m3u8 var mı?
        if (pushM3u8s(html, referer = "$mainUrl/", callback)) return true

        // 1) Player iframe'leri
        for (ifr in page.select("iframe[src], amp-iframe[src]")) {
            val src = normalizeHref(ifr.attr("src")) ?: continue
            Log.d("DZP", "iframe: $src")

            // mntplayer özel extractor
            if (src.contains("mntplayer.com") || src.contains("montana") || src.contains("vod.")) {
                if (resolveMntPlayer(src, data, subtitleCallback, callback)) {
                    Log.d("DZP", "resolveMntPlayer başarılı")
                    return true
                }
                if (resolveMontana(src, callback)) {
                    Log.d("DZP", "resolveMontana başarılı")
                    return true
                }
            }

            if (loadExtractor(src, data, subtitleCallback, callback)) return true
            if (extractFromPageUrl(src, referer = data, subtitleCallback, callback)) return true
        }

        if (extractFromPageUrl(data, referer = "$mainUrl/", subtitleCallback, callback)) return true
        if (pushMp4s(html, referer = "$mainUrl/", callback)) return true

        return false
    }

    /**
     * mntplayer/vod iframe'ini çöz:
     * - HTML içinde /p/<token>/master.m3u8 ara
     * - /p/<token> varsa master.m3u8 türet ve doğrula
     * - Harici script'leri de tara
     */
    private suspend fun resolveMntPlayer(
        iframeUrl: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainHtml = runCatching {
            app.get(
                iframeUrl,
                referer = pageReferer,
                interceptor = interceptor,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site"
                )
            ).text
        }.getOrNull() ?: return false

        Log.d("DZP", "mnt mainHtml len=${mainHtml.length}")

        // 1) Doğrudan m3u8
        if (pushM3u8s(mainHtml, referer = iframeUrl, callback)) return true

        val baseOrigin = "https://vod.mntplayer.com:8443"

        // 2) HTML içinde /p/<token>
        val tokens = mutableSetOf<String>()
        Regex("""/p/[A-Za-z0-9_\-=.]+""").findAll(mainHtml).forEach { m ->
            if (m.value.length > 20) tokens += m.value
        }

        for (tok in tokens) {
            val masterUrl = "$baseOrigin$tok/master.m3u8"
            val ok = runCatching {
                app.get(masterUrl, referer = iframeUrl, interceptor = interceptor).text.contains("#EXTM3U")
            }.getOrDefault(false)
            if (ok) {
                Log.d("DZP", "mnt master bulundu: $masterUrl")
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = masterUrl,
                    referer   = iframeUrl,
                    name      = name
                ).forEach(callback)
                return true
            }
        }

        // 3) Harici script'ler
        val scriptUrls = Regex("""<script[^>]+src=["']([^"']+)["']""")
            .findAll(mainHtml)
            .map { fixUrl(it.groupValues[1]) }
            .distinct()
            .toList()

        for (sUrl in scriptUrls) {
            val js = runCatching {
                app.get(sUrl, referer = iframeUrl, interceptor = interceptor).text
            }.getOrNull() ?: continue

            // Direkt /p/.../master.m3u8
            Regex("""/p/[A-Za-z0-9_\-=.]+/master\.m3u8""").findAll(js).forEach { m ->
                val url = "$baseOrigin${m.value}"
                Log.d("DZP", "mnt js master: $url")
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = url,
                    referer   = iframeUrl,
                    name      = name
                ).forEach(callback)
                return true
            }

            // Sadece /p/<token>
            Regex("""/p/[A-Za-z0-9_\-=.]+""").findAll(js).forEach { m ->
                if (m.value.length < 20) return@forEach
                val url = "$baseOrigin${m.value}/master.m3u8"
                val ok = runCatching {
                    app.get(url, referer = iframeUrl, interceptor = interceptor).text.contains("#EXTM3U")
                }.getOrDefault(false)
                if (ok) {
                    Log.d("DZP", "mnt js token master: $url")
                    M3u8Helper.generateM3u8(
                        source    = name,
                        streamUrl = url,
                        referer   = iframeUrl,
                        name      = name
                    ).forEach(callback)
                    return true
                }
            }
        }

        // 4) Nested iframe
        for (m in Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(mainHtml)) {
            val n = fixUrl(m.groupValues[1])
            if (n != iframeUrl && (n.contains("mntplayer") || n.contains("vod."))) {
                if (resolveMntPlayer(n, iframeUrl, subtitleCallback, callback)) return true
            }
        }

        return false
    }

    /** Eski montana çözücü (fallback). */
    private suspend fun resolveMontana(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(
                iframeUrl,
                referer = "$mainUrl/",
                interceptor = interceptor,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate"
                )
            ).text
        }.getOrNull() ?: return false

        if (pushM3u8s(html, referer = iframeUrl, callback)) return true

        Regex("""["'](?:file|src|url|hls)["']\s*:\s*["']([^"']*\.m3u8[^"']*)["']""")
            .findAll(html).forEach { m ->
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = fixUrl(m.groupValues[1]),
                    referer   = iframeUrl,
                    name      = name
                ).forEach(callback)
            }

        return false
    }

    /* -------------------- Yardımcı çözücüler -------------------- */

    private suspend fun extractFromPageUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            app.get(url, referer = referer, interceptor = interceptor)
        } catch (e: Exception) {
            return false
        }
        val body = res.text
        val doc  = res.document

        decryptFromPage(doc)?.let { dec ->
            if (pushM3u8s(dec, referer, callback)) return true
            if (pushMp4s(dec, referer, callback)) return true
            Regex("""https?://[^\s"'<>]+""").findAll(dec).forEach { m ->
                if (loadExtractor(m.value, referer, subtitleCallback, callback)) return true
            }
        }

        if (pushM3u8s(body, referer, callback)) return true
        if (pushMp4s(body, referer, callback)) return true
        if (huntInScripts(doc, url, callback)) return true

        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            if (loadExtractor(src, referer, subtitleCallback, callback)) return@forEach
            val subRes = runCatching { app.get(src, referer = url, interceptor = interceptor) }.getOrNull() ?: return@forEach
            if (pushM3u8s(subRes.text, url, callback)) return@forEach
            if (pushMp4s(subRes.text, url, callback)) return@forEach
            if (huntInScripts(subRes.document, src, callback)) return@forEach
        }

        return false
    }

    private suspend fun huntInScripts(
        doc: Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (s in doc.select("script")) {
            val code = if (s.hasAttr("src")) {
                val src = fixUrl(s.attr("src"))
                runCatching { app.get(src, referer = referer, interceptor = interceptor).text }.getOrNull()
            } else s.data()

            if (code.isNullOrBlank()) continue

            if (pushM3u8s(code, referer, callback)) found = true

            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
                .findAll(code).forEach { m ->
                    runCatching {
                        M3u8Helper.generateM3u8(
                            source    = name,
                            streamUrl = fixUrl(m.groupValues[1]),
                            referer   = referer,
                            name      = name
                        ).forEach(callback)
                        found = true
                    }
                }

            Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)""")
                .findAll(code).mapNotNull {
                    runCatching { String(Base64.getDecoder().decode(it.groupValues[1])) }.getOrNull()
                }.forEach { dec ->
                    if (pushM3u8s(dec, referer, callback)) found = true
                    if (pushMp4s(dec, referer, callback)) found = true
                }

            if (pushMp4s(code, referer, callback)) found = true
        }
        return found
    }

    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { m3u ->
                runCatching {
                    M3u8Helper.generateM3u8(
                        source    = name,
                        streamUrl = fixUrl(m3u),
                        referer   = referer,
                        name      = name
                    ).forEach(callback)
                    any = true
                }
            }
        return any
    }

    private suspend fun pushMp4s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = fixUrl(link)
                    )
                )
                any = true
            }
        return any
    }

    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = runCatching { jacksonObjectMapper().readValue<EncPayload>(json) }.getOrNull() ?: return null

        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull() ?: return null

        return runCatching {
            val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
            val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}$")))
                keyStr.hexToBytes() else keyStr.toByteArray(Charsets.UTF_8)
            val ivBytes   = payload.iv.hexToBytes()
            val cipherBin = Base64.getDecoder().decode(payload.ciphertext)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBin), Charsets.UTF_8)
        }.getOrNull()
    }

    /* -------------------- Utils -------------------- */

    private fun String.encodeURL() = java.net.URLEncoder.encode(this, "utf-8")

    private fun String.addPagePath(page: Int): String {
        if (page <= 1) return this
        return if (this.contains("?")) {
            if (this.contains("paged=")) this.replace(Regex("paged=\\d+"), "paged=$page")
            else if (this.endsWith("?") || this.endsWith("&")) this + "paged=$page"
            else "$this&paged=$page"
        } else {
            val base = this.trimEnd('/')
            "$base/page/$page/"
        }
    }

    private fun normalizeHref(href: String?): String? =
        if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)

    private fun String.hexToBytes(): ByteArray {
        val clean = this.replace(Regex("[^0-9a-fA-F]"), "")
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length) {
            out[i / 2] = ((clean[i].digitToInt(16) shl 4) + clean[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }
}
