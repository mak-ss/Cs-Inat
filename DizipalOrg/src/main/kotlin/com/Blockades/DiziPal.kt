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
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipalorijinal22.vip"
    override var name                 = "DiziPalOrg"
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
        "$mainUrl/diziler"  to "Diziler",
        "$mainUrl/filmler"  to "Filmler",
        "$mainUrl/trend"    to "Trend",
        "$mainUrl/platform/netflix"        to "Netflix",
        "$mainUrl/platform/disney-plus"    to "Disney+",
        "$mainUrl/platform/prime-video"    to "Prime Video",
        "$mainUrl/platform/max"            to "Max",
        "$mainUrl/platform/blutv"          to "BluTV",
        "$mainUrl/platform/exxen"          to "Exxen",
        "$mainUrl/platform/gain"           to "Gain",
        "$mainUrl/platform/tabii"          to "tabii",
        "$mainUrl/platform/paramount-plus" to "Paramount+",
        "$mainUrl/"          to "Son Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Platform sayfası mı?
        val isPlatform = request.data.contains("/platform/")
        val url = if (isPlatform) {
            request.data.platformPagePath(page)
        } else {
            request.data.addPagePath(page)
        }
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        val items = doc.select(".ip-card a[href], .ip-trend-card a[href]")
            .filter { a ->
                val h = a.attr("href")
                h.contains("/dizi/") || h.contains("/film/")
            }
            .distinctBy { it.attr("href") }

        items.forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            val container = a.closest(".ip-card") ?: a.closest(".ip-trend-card")
            val poster = container?.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)

            val title = container?.selectFirst(".ip-card-title, .ip-trend-title")?.text()?.trim()
                ?: container?.selectFirst("img")?.attr("alt")?.trim()
                ?: a.attr("title").ifBlank { a.text() }.ifBlank {
                    href.substringAfterLast("/").replace("-", " ").trim()
                }

            if (href.contains("/dizi/")) {
                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                cards += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.select(".pagination a, a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, cards, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query.encodeURL()}"
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        val out = mutableListOf<SearchResponse>()

        doc.select(".ip-card a[href], .ip-trend-card a[href]").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach

            val container = a.closest(".ip-card") ?: a.closest(".ip-trend-card")
            val poster = container?.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)
            val title = container?.selectFirst(".ip-card-title, .ip-trend-title")?.text()?.trim()
                ?: container?.selectFirst("img")?.attr("alt")?.trim()
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            when {
                href.contains("/dizi/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                href.contains("/film/") -> out += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) — DEĞİŞMEDİ -------------------- */

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val poster = fixUrlNull(
            doc.selectFirst("[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst(".dp-sidebar-poster img, .dp-poster-small img")?.attr("src")
        )

        val title = doc.selectFirst(".dp-series-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: doc.title().substringBefore("|").trim()

        return when {
            url.contains("/dizi/") -> {
                val slug = url.substringAfterLast("/").substringBefore("?")
                val seasons = extractSeasons(doc)

                val eps = mutableListOf<Episode>()
                for (s in seasons) {
                    eps += fetchEpisodes(slug, s)
                }

                if (eps.isEmpty()) {
                    doc.select("""a[href*="/bolum/"]""").forEach { a ->
                        val href = normalizeHref(a.attr("href")) ?: return@forEach
                        val (s, e) = parseSeasonEpisode(href, a.text())
                        eps += newEpisode(href) {
                            name = a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $e" }
                            season = s
                            episode = e
                        }
                    }
                }

                Log.e("DZP", "load(): $title -> ${eps.size} bölüm")

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps.distinctBy { it.data }) {
                    this.posterUrl = poster
                }
            }

            url.contains("/film/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }

            url.contains("/bolum/") -> {
                val (s, e) = parseSeasonEpisode(url, doc.selectFirst("h1, .bp-ep-title")?.text().orEmpty())
                val ep = newEpisode(url) {
                    name = doc.selectFirst("h1, .bp-ep-title")?.text()?.trim()
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

    private fun extractSeasons(doc: Document): List<Int> {
        val fromBtns = doc.select(".dp-season-btn, .bp-stab")
            .mapNotNull { it.attr("data-season").toIntOrNull() }
            .distinct()
        if (fromBtns.isNotEmpty()) return fromBtns.sorted()

        val badge = doc.selectFirst(".dp-badge-season")?.text() ?: ""
        val n = Regex("""(\d+)""").find(badge)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (n != null) (1..n).toList() else listOf(1)
    }

    private suspend fun fetchEpisodes(slug: String, season: Int): List<Episode> {
        return try {
            val apiUrl = "$mainUrl/bolum_yukle.php?slug=${slug.encodeURL()}&sezon=$season"
            val json = app.get(
                apiUrl,
                interceptor = interceptor,
                referer = "$mainUrl/dizi/$slug",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            @Suppress("UNCHECKED_CAST")
            val arr = jacksonObjectMapper().readValue<List<Map<String, Any?>>>(json)

            arr.mapNotNull { item ->
                val epSlug = item["slug"]?.toString() ?: return@mapNotNull null
                val baslik = item["baslik"]?.toString() ?: ""
                val alt    = item["alt"]?.toString() ?: ""

                val href = "$mainUrl/bolum/$epSlug"
                val (s, e) = parseSeasonEpisode(epSlug, "$baslik $alt")

                newEpisode(href) {
                    name = baslik.ifBlank { alt }.ifBlank { "Bölüm ${e ?: ""}" }
                    this.season = s ?: season
                    this.episode = e
                }
            }
        } catch (e: Exception) {
            Log.e("DZP", "fetchEpisodes($slug, $season) hata: ${e.message}")
            emptyList()
        }
    }

    private fun parseSeasonEpisode(href: String, textRaw: String): Pair<Int?, Int?> {
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

    /* -------------------- Links (player) — DEĞİŞMEDİ -------------------- */

    data class EncPayload(val ciphertext: String, val iv: String, val salt: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e("DZP", "=== loadLinks ÇAĞRILDI ===")
        Log.e("DZP", "data=$data")

        if (extractFromPageUrl(data, referer = "$mainUrl/", subtitleCallback, callback)) {
            Log.e("DZP", "✓ Sayfadan link bulundu")
            return true
        }

        val page = runCatching {
            app.get(data, interceptor = interceptor, referer = "$mainUrl/").document
        }.getOrNull()

        if (page != null) {
            val pageHtml = page.html()
            val slugFromJs = Regex("""(?:var|let|const)\s+EP_SLUG\s*=\s*["']([^"']+)["']""")
                .find(pageHtml)?.groupValues?.getOrNull(1)
                ?: data.substringAfterLast("/").substringBefore("?")

            Log.e("DZP", "EP_SLUG=$slugFromJs")

            val isFilm = data.contains("/film/")
            val typeParam = if (isFilm) "&type=film" else ""
            val embedUrls = listOf(
                "$mainUrl/api/embed.php?slug=$slugFromJs&domain=https://dizipal2148.com$typeParam&cb=3",
                "$mainUrl/api/embed.php?slug=$slugFromJs&domain=$mainUrl$typeParam&cb=3",
                "$mainUrl/api/embed.php?slug=$slugFromJs&domain=https://dizipal2148.com&cb=3",
                "$mainUrl/api/embed.php?slug=$slugFromJs&domain=$mainUrl&cb=3"
            ).distinct()

            for (embedUrl in embedUrls) {
                Log.e("DZP", "embed.php deneniyor: $embedUrl")
                if (extractFromPageUrl(embedUrl, referer = data, subtitleCallback, callback)) {
                    Log.e("DZP", "✓ embed.php'den link bulundu")
                    return true
                }
            }

            page.select("iframe[src], amp-iframe[src]").forEach { ifr ->
                val src = normalizeHref(ifr.attr("src")) ?: return@forEach
                Log.e("DZP", "iframe: $src")
                if (loadExtractor(src, data, subtitleCallback, callback)) return true
                if (extractFromPageUrl(src, referer = data, subtitleCallback, callback)) return true
            }

            if (pushHlsProxy(pageHtml, referer = "$mainUrl/", callback)) return true
            if (pushM3u8s(pageHtml, referer = "$mainUrl/", callback)) return true
            if (pushMp4s(pageHtml, referer = "$mainUrl/", callback)) return true
        }

        Log.e("DZP", "✗ loadLinks başarısız")
        return false
    }

    private suspend fun extractFromPageUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e("DZP", "extractFromPageUrl: $url")
        val res = try {
            app.get(url, referer = referer, interceptor = interceptor)
        } catch (e: Exception) {
            Log.e("DZP", "fetch hatası: ${e.message}")
            return false
        }
        val body = res.text
        Log.e("DZP", "Yanıt: ${body.length} byte, content-type=${res.headers["content-type"]}")

        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Log.e("DZP", "JSON yanıt: ${body.take(300)}")
            if (pushHlsProxy(body, referer, callback)) return true
            if (pushM3u8s(body, referer, callback)) return true
            if (pushMp4s(body, referer, callback)) return true
            Regex("""https?://[^\s"'<>]+""").findAll(body).map { it.value }.distinct().forEach { u ->
                if (loadExtractor(u, referer, subtitleCallback, callback)) return true
            }
        }

        val doc = res.document

        decryptFromPage(doc)?.let { dec ->
            Log.e("DZP", "Şifre çözüldü: ${dec.length} byte")
            if (pushHlsProxy(dec, referer, callback)) return true
            if (pushM3u8s(dec, referer, callback)) return true
            if (pushMp4s(dec, referer, callback)) return true
        }

        if (pushHlsProxy(body, referer, callback)) return true
        if (pushM3u8s(body, referer, callback)) return true
        if (pushMp4s(body, referer, callback)) return true
        if (huntInScripts(doc, url, callback)) return true

        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            Log.e("DZP", "alt iframe: $src")
            if (loadExtractor(src, referer, subtitleCallback, callback)) return@forEach
            val subRes = runCatching {
                app.get(src, referer = url, interceptor = interceptor)
            }.getOrNull() ?: return@forEach
            if (pushHlsProxy(subRes.text, url, callback)) return@forEach
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
        val scripts = doc.select("script")

        for (s in scripts) {
            val code = if (s.hasAttr("src")) {
                val src = fixUrl(s.attr("src"))
                runCatching { app.get(src, referer = referer, interceptor = interceptor).text }.getOrNull()
            } else s.data()

            if (code.isNullOrBlank()) continue

            if (pushHlsProxy(code, referer, callback)) found = true
            if (pushM3u8s(code, referer, callback)) found = true

            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
                .findAll(code).map { it.groupValues[1] }.distinct().forEach { m3u ->
                    runCatching {
                        M3u8Helper.generateM3u8(
                            source    = name,
                            streamUrl = fixUrl(m3u),
                            referer   = referer,
                            name      = name
                        ).forEach(callback)
                        found = true
                    }
                }

            Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)""")
                .findAll(code).mapNotNull {
                    runCatching { String(Base64.getDecoder().decode(it.groupValues[1])) }.getOrNull()
                }.forEach { decoded ->
                    if (pushHlsProxy(decoded, referer, callback)) found = true
                    if (pushM3u8s(decoded, referer, callback)) found = true
                    if (pushMp4s(decoded, referer, callback)) found = true
                }

            if (pushMp4s(code, referer, callback)) found = true
        }

        return found
    }

    private suspend fun pushHlsProxy(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false

        val proxies = Regex("""https?://[^\s"'<>]*hls\.php\?url=[^\s"'<>]+""")
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()

        if (proxies.isEmpty()) return false

        Log.e("DZP", "pushHlsProxy: ${proxies.size} proxy bulundu")

        for (proxy in proxies) {
            val encoded = Regex("""[?&]url=([^&]+)""")
                .find(proxy)?.groupValues?.getOrNull(1) ?: continue

            val realM3u8 = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: continue
            if (!realM3u8.startsWith("http")) continue

            Log.e("DZP", "Proxy çözüldü -> $realM3u8")

            val m3u8Result = runCatching {
                M3u8Helper.generateM3u8(
                    source    = name,
                    streamUrl = proxy,
                    referer   = "$mainUrl/",
                    name      = name
                )
            }.getOrElse {
                Log.e("DZP", "M3u8Helper exception: ${it.message}")
                emptyList()
            }

            Log.e("DZP", "M3u8Helper sonucu: ${m3u8Result.size} link")

            if (m3u8Result.isNotEmpty()) {
                m3u8Result.forEach(callback)
                any = true
                continue
            }

            Log.e("DZP", "Fallback: proxy linki doğrudan veriliyor (psess korunur)")
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin"  to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            )

            val link = runCatching {
                ExtractorLink(
                    source  = name,
                    name    = name,
                    url     = proxy,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value,
                    headers = headers,
                    type    = ExtractorLinkType.M3U8
                )
            }.getOrElse {
                Log.e("DZP", "ExtractorLink constructor hatası: ${it.message}")
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = proxy
                )
            }

            callback(link)
            any = true
        }

        return any
    }

    private suspend fun pushM3u8s(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text).map { it.value }.distinct().forEach { m3u ->
                if (m3u.contains("hls.php")) return@forEach
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
                    ) {
                        this.referer = referer
                    }
                )
                any = true
            }
        return any
    }

    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-drm-k]""").firstOrNull()
            ?: doc.select("""div[data-rm-k]""").firstOrNull()
            ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = runCatching {
            jacksonObjectMapper().readValue<EncPayload>(json)
        }.getOrNull() ?: return null

        val appKeyB64 = doc.select("script").asSequence()
            .mapNotNull {
                Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1)
            }
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

    /** Platform sayfaları için sayfalama: ?page=2 veya /page/2/ formatını dener. */
    private fun String.platformPagePath(page: Int): String {
        if (page <= 1) return this
        return if (this.contains("?")) {
            if (this.contains("page=")) this.replace(Regex("page=\\d+"), "page=$page")
            else "$this&page=$page"
        } else {
            val base = this.trimEnd('/')
            "$base?page=$page"
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
