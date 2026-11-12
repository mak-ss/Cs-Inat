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
    override var mainUrl              = "https://dizipalizm.com/"
    override var name                 = "DiziPalizm"
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
        "$mainUrl/tum-bolumler/" to "Son Eklenen Bölümler",
        "$mainUrl/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",
        "$mainUrl/asya-dizileri/" to "Asya Dizileri",
        "$mainUrl/diziler" to "Tüm Diziler",
        "$mainUrl/filmler" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.addPagePath(page)
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document

        val cards = mutableListOf<SearchResponse>()

        doc.select("a.r-card").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach

            val poster = a.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)

            val title = a.selectFirst(".r-cap-t")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.removeSuffix(" izle")
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            if (href.contains("/dizi/")) {
                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                cards += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        if (request.name == "Son Eklenen Bölümler") {
            doc.select("div.list-episodes").forEach { div ->
                val a = div.selectFirst("a.r-still") ?: return@forEach
                val href = normalizeHref(a.attr("href")) ?: return@forEach
                if (!href.contains("-sezon-") || !href.contains("-bolum")) return@forEach

                val poster = a.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let(::fixUrlNull)

                val title = div.selectFirst(".r-cap-t")?.text()?.trim()
                    ?: a.attr("title")?.trim()
                    ?: href.substringAfterLast("/").replace("-", " ").trim()

                cards += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }

        val hasNext = doc.selectFirst("link[rel=next]") != null
        return newHomePageResponse(request.name, cards.distinctBy { it.url }, hasNext = hasNext)
    }

    /* -------------------- Search -------------------- */
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.encodeURL()}"
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        val out = mutableListOf<SearchResponse>()

        doc.select("a.r-card").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach

            val poster = a.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)

            val title = a.selectFirst(".r-cap-t")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.removeSuffix(" izle")
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            when {
                href.contains("/dizi/") -> out += newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                href.contains("/film/") -> out += newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }

        return out.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String) = search(query)

    /* -------------------- Load (details) -------------------- */
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, interceptor = interceptor, referer = "$mainUrl/").document
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("meta[name='twitter:image']")?.attr("content")
                ?: doc.selectFirst(".r-dposter img")?.attr("src")
        )
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("|")?.trim()
            ?: doc.title().substringBefore("|").trim()
        val title = rawTitle.removeSuffix(" izle").removeSuffix(" Tüm Bölümler").trim()

        return when {
            url.contains("/dizi/") -> {
                val slug = url.substringAfterLast("/").substringBefore("?")
                val seasons = extractSeasons(doc)
                val eps = mutableListOf<Episode>()
                for (s in seasons) {
                    eps += fetchEpisodes(slug, s, doc)
                }
                if (eps.isEmpty()) {
                    doc.select("a[href*='-sezon-'][href*='-bolum']").forEach { a ->
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
                    this.plot = doc.selectFirst(".r-lead p")?.text()?.trim()
                }
            }
            url.contains("/film/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = doc.selectFirst(".r-lead p")?.text()?.trim()
                }
            }
            url.contains("-sezon-") && url.contains("-bolum") -> {
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

    private fun extractSeasons(doc: Document): List<Int> {
        val fromBtns = doc.select(".r-sezonlar button.r-sezon")
            .mapNotNull { btn ->
                Regex("""(\d+)""").find(btn.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .distinct()
        if (fromBtns.isNotEmpty()) return fromBtns.sorted()
        return listOf(1)
    }

    private suspend fun fetchEpisodes(slug: String, season: Int, doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select("a[href*='-sezon-'][href*='-bolum']").forEach { a ->
            val href = normalizeHref(a.attr("href")) ?: return@forEach
            val (s, e) = parseSeasonEpisode(href, a.text())
            if (s == season || s == null) {
                episodes += newEpisode(href) {
                    name = a.selectFirst(".r-bolum-t")?.text()?.trim()
                        ?: a.attr("title").ifBlank { a.text() }.ifBlank { "Bölüm $e" }
                    this.season = s ?: season
                    this.episode = e
                }
            }
        }
        if (episodes.isNotEmpty()) return episodes

        return try {
            val apiUrl = "$mainUrl/bolum_yukle.php?slug=${slug.encodeURL()}&sezon=$season"
            val json = app.get(apiUrl, interceptor = interceptor, referer = "$mainUrl/dizi/$slug", headers = mapOf("X-Requested-With" to "XMLHttpRequest")).text
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
            emptyList()
        }
    }

    private fun parseSeasonEpisode(href: String, textRaw: String): Pair<Int?, Int?> {
        Regex("""-(\d+)-sezon-(\d+)-bolum""", RegexOption.IGNORE_CASE).find(href)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val text = textRaw.lowercase().replace('ö','o').replace('ü','u').replace('ı','i').replace('ş','s').replace('ç','c').replace('ğ','g')
        val s = Regex("""(\d+)\s*\.?\s*sezon""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val e = Regex("""(\d+)\s*\.?\s*bolum""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return s to e
    }

    /* -------------------- Links (player) -------------------- */
    data class EncPayload(val ciphertext: String, val iv: String, val salt: String)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.e("DZP", "=== loadLinks ÇAĞRILDI ===")
        Log.e("DZP", "data=$data")

        val page = runCatching { app.get(data, interceptor = interceptor, referer = "$mainUrl/").document }.getOrNull()
            ?: return false

        page.selectFirst("div.video-container iframe")?.let { iframe ->
            val src = normalizeHref(iframe.attr("src")) ?: return@let
            Log.e("DZP", "video-container iframe bulundu: $src")
            if (extractVideoFromIframe(src, data, subtitleCallback, callback)) {
                Log.e("DZP", "✓ iframe'den video bulundu")
                return true
            }
        }

        page.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            Log.e("DZP", "iframe: $src")
            if (extractVideoFromIframe(src, data, subtitleCallback, callback)) return true
        }

        val pageHtml = page.html()
        if (pushM3u8s(pageHtml, referer = "$mainUrl/", callback)) return true
        if (pushMp4s(pageHtml, referer = "$mainUrl/", callback)) return true

        Log.e("DZP", "✗ loadLinks başarısız")
        return false
    }

    private suspend fun extractVideoFromIframe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.e("DZP", "extractVideoFromIframe: $url")

        if (loadExtractor(url, referer, subtitleCallback, callback)) {
            Log.e("DZP", "✓ loadExtractor başarılı: $url")
            return true
        }

        val res = runCatching { app.get(url, referer = referer, interceptor = interceptor) }.getOrNull() ?: run {
            Log.e("DZP", "Iframe sayfası çekilemedi: $url")
            return false
        }
        val body = res.text
        val doc = res.document

        // Twimg m3u8'leri - DOĞRU REFERER ile gönder
        // Twitter CDN, referer olarak genellikle twitter.com veya x.com bekler
        Regex("""https?://video\.twimg\.com/[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(body).map { it.value }.distinct().forEach { m3u8 ->
            Log.e("DZP", "Twimg m3u8 bulundu: $m3u8")
            callback(newExtractorLink(source = name, name = name, url = m3u8) {
                this.referer = "https://twitter.com/"
                this.quality = Qualities.Unknown.value
            })
            return true
        }

        // ksdpictures.site gibi sitelerin m3u8'leri
        if (pushM3u8s(body, referer = referer, callback)) return true
        if (pushMp4s(body, referer = referer, callback)) return true

        if (huntInScripts(doc, referer, callback)) return true

        doc.select("iframe[src], amp-iframe[src]").forEach { ifr ->
            val src = normalizeHref(ifr.attr("src")) ?: return@forEach
            Log.e("DZP", "Nested iframe: $src")
            if (loadExtractor(src, referer, subtitleCallback, callback)) return true
            val subRes = runCatching { app.get(src, referer = url, interceptor = interceptor) }.getOrNull() ?: return@forEach
            if (pushM3u8s(subRes.text, referer, callback)) return true
            if (pushMp4s(subRes.text, referer, callback)) return true
            if (huntInScripts(subRes.document, referer, callback)) return true
        }

        return false
    }

    private suspend fun huntInScripts(doc: Document, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        for (s in doc.select("script")) {
            val code = if (s.hasAttr("src")) runCatching { app.get(fixUrl(s.attr("src")), referer = referer, interceptor = interceptor).text }.getOrNull() else s.data()
            if (code.isNullOrBlank()) continue

            // Twimg m3u8'leri (script içinde)
            Regex("""https?://video\.twimg\.com/[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(code).map { it.value }.distinct().forEach { m3u8 ->
                Log.e("DZP", "Script içinde twimg m3u8: $m3u8")
                callback(newExtractorLink(source = name, name = name, url = m3u8) {
                    this.referer = "https://twitter.com/"
                    this.quality = Qualities.Unknown.value
                })
                found = true
            }

            if (pushM3u8s(code, referer, callback)) found = true
            if (pushMp4s(code, referer, callback)) found = true

            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").findAll(code).map { it.groupValues[1] }.distinct().forEach { m3u ->
                callback(newExtractorLink(source = name, name = name, url = fixUrl(m3u)) {
                    this.referer = referer
                })
                found = true
            }
        }
        return found
    }

    private suspend fun pushM3u8s(text: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(text).map { it.value }.distinct().forEach { m3u ->
            if (m3u.contains("hls.php")) return@forEach
            callback(newExtractorLink(source = name, name = name, url = fixUrl(m3u)) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            })
            any = true
        }
        return any
    }

    private suspend fun pushMp4s(text: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var any = false
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").findAll(text).map { it.value }.distinct().forEach { link ->
            callback(newExtractorLink(source = name, name = name, url = fixUrl(link)) {
                this.referer = referer
            })
            any = true
        }
        return any
    }

    private fun decryptFromPage(doc: Document): String? {
        val encDiv = doc.select("""div[data-drm-k]""").firstOrNull() ?: doc.select("""div[data-rm-k]""").firstOrNull() ?: return null
        val json = encDiv.text().trim().ifBlank { return null }
        val payload = runCatching { jacksonObjectMapper().readValue<EncPayload>(json) }.getOrNull() ?: return null
        val appKeyB64 = doc.select("script").asSequence().mapNotNull { Regex("""window\.appCKey\s*=\s*'([^']+)'""").find(it.data())?.groupValues?.getOrNull(1) }.firstOrNull() ?: return null
        return runCatching {
            val keyStr = String(Base64.getDecoder().decode(appKeyB64), Charset.forName("UTF-8")).trim()
            val keyBytes = if (keyStr.matches(Regex("^[0-9a-fA-F]{32,64}$"))) keyStr.hexToBytes() else keyStr.toByteArray(Charsets.UTF_8)
            val ivBytes = payload.iv.hexToBytes()
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
        } else { "$this".trimEnd('/') + "/page/$page/" }
    }
    private fun normalizeHref(href: String?): String? = if (href.isNullOrBlank()) null else if (href.startsWith("http")) href else fixUrl(href)
    private fun String.hexToBytes(): ByteArray {
        val clean = this.replace(Regex("[^0-9a-fA-F]"), "")
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length) { out[i / 2] = ((clean[i].digitToInt(16) shl 4) + clean[i + 1].digitToInt(16)).toByte(); i += 2 }
        return out
    }
}
