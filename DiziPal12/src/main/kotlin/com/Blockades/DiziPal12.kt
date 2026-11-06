package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// ================================================================================================
// ANA API
// ================================================================================================
class DiziPal12 : MainAPI() {
    override var mainUrl              = "https://dizipal2147.com"
    override var name                 = "DiziPal12"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = false

    override val mainPage = mainPageOf(
        "$mainUrl/"                       to "Ana Sayfa",
        "${mainUrl}/bolumler"             to "Son Bölümler",
        "${mainUrl}/diziler"              to "Yeni Diziler",
        "${mainUrl}/filmler"              to "Yeni Filmler",
        "${mainUrl}/platform/netflix"     to "Netflix",
        "${mainUrl}/platform/exxen"       to "Exxen",
        "${mainUrl}/platform/blutv"       to "BluTV",
        "${mainUrl}/platform/disney-plus" to "Disney+",
        "${mainUrl}/platform/prime-video" to "Amazon Prime",
        "${mainUrl}/platform/tabii"       to "Tabii",
        "${mainUrl}/platform/gain"        to "Gain",
        "${mainUrl}/platform/max"         to "Max",
        "${mainUrl}/kategori/bilim-kurgu" to "Bilimkurgu Filmleri",
        "${mainUrl}/kategori/komedi"      to "Komedi Filmleri",
        "${mainUrl}/kategori/belgesel"    to "Belgesel Filmleri",
    )

    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // ============================================================================================
    // ANA SAYFA
    // ============================================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = when {
            request.data.contains("/bolumler") -> {
                document.select("div.episodes-list-grid > a.episode-list-item")
                    .mapNotNull { it.sonBolumler() }
            }
            request.data == mainUrl || request.data == "$mainUrl/" -> {
                val items = mutableListOf<SearchResponse>()

                document.select("a.trending-item").forEach { item ->
                    if (item.attr("href").contains("/dizi/")) {
                        dizilerFromTrend(item)?.let { items.add(it) }
                    }
                }
                document.select("ul.content-grid.large > li").forEach { li ->
                    li.selectFirst("a")?.let { a ->
                        if (a.attr("href").contains("/dizi/")) {
                            dizilerFromCard(li)?.let { items.add(it) }
                        }
                    }
                }
                document.select("ul.content-grid:not(.large) > li").forEach { li ->
                    li.selectFirst("a")?.let { a ->
                        if (a.attr("href").contains("/film/")) {
                            dizilerFromCard(li)?.let { items.add(it) }
                        }
                    }
                }
                items
            }
            else -> {
                document.select("ul.content-grid > li").mapNotNull { it.diziler() }
            }
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun dizilerFromTrend(element: Element): SearchResponse? {
        val href = fixUrlNull(element.attr("href")) ?: return null
        val title = element.selectFirst("h3.trending-title")?.text() ?: return null
        val poster = fixUrlNull(element.selectFirst("img")?.attr("data-src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun dizilerFromCard(element: Element): SearchResponse? = element.diziler()

    private fun Element.sonBolumler(): SearchResponse? {
        val name    = this.selectFirst(".ep-title")?.text() ?: return null
        val episode = this.selectFirst(".ep-info")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title   = "$name $episode"

        val href = fixUrlNull(this.attr("href")) ?: return null
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") })

        val seriesUrl = href
            .replace(Regex("-\\d+-sezon-\\d+-bolum.*$"), "")
            .replace("/bolum/", "/dizi/")

        return newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.card-info h3, h3.card-title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val imgEl     = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") })

        val type = if (href.contains("/film/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    // ============================================================================================
    // ARAMA
    // ============================================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val results = mutableListOf<SearchResponse>()
        try {
            val json = org.json.JSONObject(responseRaw.text)
            val arr = json.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val title  = item.optString("title", "").trim()
                    val url    = fixUrl(item.optString("url", ""))
                    val poster = item.optString("poster", "").takeIf { it.isNotBlank() }
                    val type   = item.optString("type", "")
                    val year   = item.optInt("year", 0).takeIf { it > 0 }

                    if (title.isBlank() || url.isBlank()) continue

                    val resp = if (type.equals("Dizi", true)) {
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year      = year
                        }
                    } else {
                        newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year      = year
                        }
                    }
                    results.add(resp)
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPal12", "search JSON hatası » ${e.message}")
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================================================
    // DETAY SAYFASI
    // ============================================================================================
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/bolum/")) {
            val seriesUrl = url.replace("/bolum/", "/dizi/")
                .replace(Regex("-\\d+-sezon.*"), "")
            return load(seriesUrl)
        }

        val document = app.get(url).document

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")
            ?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.series-description")?.text()?.trim()
            ?: document.selectFirst("div.film-description p")?.text()?.trim()
        val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a")
            .map { it.text().trim() }

        if (url.contains("/dizi/")) {
            val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

            val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
                val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
                val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim()
                    ?: return@mapNotNull null

                val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)

                val epSeason  = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
            }
        } else {
            val title = document.selectFirst("h1.series-title, h1.movie-title, h1.film-title")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" izle")?.trim()
                ?: ""

            if (title.isEmpty()) return null

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
            }
        }
    }

    // ============================================================================================
    // VİDEO LİNKLERİ
    // ============================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = mutableListOf<String>()

        if (data.contains(".m3u8", ignoreCase = true)) {
            found += data
        } else {
            val pageHtml = try {
                app.get(
                    data,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                ).text
            } catch (e: Exception) {
                Log.e("DiziPal12", "Sayfa çekilemedi » ${e.message}")
                ""
            }

            if (pageHtml.isNotBlank()) {
                Log.d("DiziPal12", "Sayfa HTML (ilk 500): ${pageHtml.take(500)}")

                val norm = pageHtml.replace("\\/", "/").replace("&amp;", "&")

                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(norm).forEach { found += it.groupValues[1] }

                Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                    .findAll(norm).forEach { found += it.value }

                Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                    .findAll(norm).forEach { found += it.value }

                val dataCfgMatch = Regex(
                    """<div[^>]*id=["']videoContainer["'][^>]*data-cfg=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(norm)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<div[^>]*data-cfg=["']([^"']+)["'][^>]*id=["']videoContainer["']""",
                        RegexOption.IGNORE_CASE
                    ).find(norm)?.groupValues?.getOrNull(1)

                val bcMatch = Regex(
                    """<meta\s+name=["']_bc["']\s+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(norm)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<meta\s+content=["']([^"']+)["']\s+name=["']_bc["']""",
                        RegexOption.IGNORE_CASE
                    ).find(norm)?.groupValues?.getOrNull(1)

                val anyCfgMatch = Regex("""data-cfg=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(norm)?.groupValues?.getOrNull(1)

                val cfgToSend = dataCfgMatch ?: anyCfgMatch ?: bcMatch

                // _ct ve cf_clearance değerlerini SADECE HTML'den çıkar (derleme hatası yok)
                val ctFromHtml = Regex("""_ct=([a-zA-Z0-9_\-]+)""", RegexOption.IGNORE_CASE)
                    .find(norm)?.groupValues?.getOrNull(1)

                val cfFromHtml = Regex("""cf_clearance=([a-zA-Z0-9_\-\.]+)""", RegexOption.IGNORE_CASE)
                    .find(norm)?.groupValues?.getOrNull(1)

                val csrfMatch = Regex(
                    """<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(norm)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<meta\s+content=["']([^"']+)["']\s+name=["']csrf-token["']""",
                        RegexOption.IGNORE_CASE
                    ).find(norm)?.groupValues?.getOrNull(1)

                Log.d("DiziPal12", "ct=${ctFromHtml?.take(20)}... csrf=${csrfMatch?.take(20)}... cf=${cfFromHtml?.take(20)}...")

                if (!cfgToSend.isNullOrBlank()) {
                    Log.d("DiziPal12", "cfg=${cfgToSend.take(40)}... POST → ajax-player-config")
                    try {
                        val baseUrlMeta = Regex(
                            """<meta\s+name=["']dp-base-url["']\s+content=["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        ).find(norm)?.groupValues?.getOrNull(1) ?: "/"

                        val apiUrl = if (baseUrlMeta.startsWith("http"))
                            "${baseUrlMeta.trimEnd('/')}/ajax-player-config"
                        else
                            "$mainUrl/ajax-player-config"

                        Log.d("DiziPal12", "POST → $apiUrl")

                        val cookieParts = mutableListOf<String>()
                        ctFromHtml?.let { cookieParts += "_ct=$it" }
                        cfFromHtml?.let { cookieParts += "cf_clearance=$it" }
                        val cookieHeader = cookieParts.joinToString("; ")

                        Log.d("DiziPal12", "Cookie başlığı: ${cookieHeader.take(200)}")

                        val apiResponse = app.post(
                            apiUrl,
                            data = mapOf("cfg" to cfgToSend),
                            referer = data,
                            headers = buildMap {
                                put("User-Agent", UA)
                                put("Accept", "application/json, text/plain, */*")
                                put("X-Requested-With", "XMLHttpRequest")
                                put("Origin", mainUrl)
                                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                                if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                                csrfMatch?.let { put("X-CSRF-Token", it) }
                            }
                        ).text

                        Log.d("DiziPal12", "ajax-player-config yanıtı = ${apiResponse.take(800)}")

                        val apiNorm = apiResponse.replace("\\/", "/").replace("&amp;", "&")

                        try {
                            val json = org.json.JSONObject(apiNorm)
                            val success = json.optBoolean("success", false)
                            Log.d("DiziPal12", "success=$success")

                            val config = json.optJSONObject("config")
                                ?: json.optJSONObject("data")?.optJSONObject("config")

                            if (config != null) {
                                val v = config.optString("v", "")
                                val t = config.optString("t", "").lowercase()
                                Log.d("DiziPal12", "config.v=${v.take(120)} | config.t=$t")

                                if (v.isNotBlank()) {
                                    when {
                                        v.contains(".m3u8", true) -> found += v
                                        t == "iframe" && v.contains("<iframe", true) -> {
                                            Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                                .find(v)?.groupValues?.getOrNull(1)?.let { found += it }
                                        }
                                        t == "iframe" -> found += v
                                        v.contains("<iframe", true) -> {
                                            Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                                .find(v)?.groupValues?.getOrNull(1)?.let { found += it }
                                        }
                                        else -> found += v
                                    }
                                }
                            } else {
                                Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                                    .findAll(apiNorm).forEach { found += it.value }
                                Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                                    .findAll(apiNorm).forEach { found += it.value }
                            }
                        } catch (e: Exception) {
                            Log.e("DiziPal12", "JSON parse hatası » ${e.message}")
                            Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                                .findAll(apiNorm).forEach { found += it.value }
                            Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                                .findAll(apiNorm).forEach { found += it.value }
                        }
                    } catch (e: Exception) {
                        Log.e("DiziPal12", "ajax-player-config POST HATA » ${e.message}")
                    }
                } else {
                    Log.d("DiziPal12", "data-cfg/_bc bulunamadı, sadece HTML taraması")
                }
            }
        }

        val unique = found
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { src ->
                when {
                    src.contains("superadjacentsoddenly", true) -> 5
                    src.contains("formationfeed", true)         -> 4
                    src.contains(".m3u8", true) && !src.contains("?") && src.contains("index") -> 3
                    src.contains(".m3u8", true) && !src.contains("?") -> 2
                    src.contains(".m3u8", true)                 -> 1
                    else                                        -> 0
                }
            }

        if (unique.isEmpty()) {
            Log.w("DiziPal12", "Hiçbir kaynak bulunamadı: $data")
            return false
        }

        Log.d("DiziPal12", "Bulunan kaynaklar: $unique")

        for (src in unique) {
            try {
                when {
                    src.contains("superadjacentsoddenly", true) ->
                        SuperAdjacentExtractor().getUrl(src, "$mainUrl/", subtitleCallback, callback)

                    src.contains("formationfeed", true) ->
                        FormationFeedExtractor().getUrl(src, "$mainUrl/", subtitleCallback, callback)

                    src.contains(".m3u8", true) -> {
                        val origin = try {
                            val u = java.net.URI(src)
                            val p = if (u.port != -1) ":${u.port}" else ""
                            "${u.scheme}://${u.host}$p"
                        } catch (_: Exception) { mainUrl }

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = src,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to UA,
                                    "Origin" to origin,
                                    "Referer" to "$mainUrl/"
                                )
                            }
                        )
                    }

                    else -> JwKaynak().getUrl(src, "$mainUrl/", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("DiziPal12", "loadLinks alt hata ($src) » ${e.message}")
            }
        }
        return true
    }
}

// ================================================================================================
// EXTRACTOR'LAR — DiziPal12 modülünde tanımlı olmalı
// ================================================================================================

class SuperAdjacentExtractor : ExtractorApi() {
    override val name = "SuperAdjacent"
    override val mainUrl = "https://superadjacentsoddenly.xyz"
    override val requiresReferer = true

    private val DIZIPAL_REFERER = "https://dizipal2147.com/"
    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = DIZIPAL_REFERER
        try {
            if (url.contains(".m3u8", ignoreCase = true)) {
                emitLink(callback, url, pageReferer)
                return
            }

            val pageText = app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to pageReferer,
                    "Referer" to pageReferer
                )
            ).text

            val normalized = pageText.replace("\\/", "/").replace("&amp;", "&")
            val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+?\.m3u8(?:\?[^\s"'\\<>]*)?)""")
            val matches = m3u8Regex.findAll(normalized).map { it.groupValues[1] }.toList()

            if (matches.isEmpty()) {
                Log.w("SuperAdjacent", "Embed sayfasında m3u8 bulunamadı: $url")
                return
            }

            val preferred = matches.sortedByDescending { link ->
                when {
                    !link.contains("?") && link.contains("index") -> 2
                    !link.contains("?") -> 1
                    else -> 0
                }
            }.first()

            emitLink(callback, preferred, pageReferer)
        } catch (e: Exception) {
            Log.e("SuperAdjacent", "Hata: ${e.message}", e)
        }
    }

    private suspend fun emitLink(callback: (ExtractorLink) -> Unit, m3u8Url: String, referer: String) {
        val link = newExtractorLink(
            source = this.name,
            name = this.name,
            url = m3u8Url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Origin" to referer,
                "Referer" to referer
            )
        }
        callback.invoke(link)
        Log.d("SuperAdjacent", "Link gönderildi: $m3u8Url | Referer: $referer")
    }
}

class FormationFeedExtractor : ExtractorApi() {
    override var name = "FormationFeed"
    override var mainUrl = "https://formationfeed.net"
    override val requiresReferer = true

    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = try {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*"
                )
            ).text
        } catch (e: Exception) {
            return
        }

        val normalized = response.replace("\\/", "/").replace("&amp;", "&")

        var m3u8Url = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
            .find(normalized)?.groupValues?.get(1)

        if (m3u8Url.isNullOrBlank()) {
            val scriptContents = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(normalized)
                .map { it.groupValues[1] }
                .joinToString("\n")

            m3u8Url = Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(scriptContents)?.groupValues?.get(1)
                ?.replace("\\/", "/")
        }

        if (!m3u8Url.isNullOrBlank()) {
            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to mainUrl,
                    "Referer" to url
                )
            }
            callback.invoke(link)
        }
    }
}

open class JwKaynak : ExtractorApi() {
    override val name            = "PalPlayer"
    override val mainUrl         = "https://formationfeed.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl

        val sayfa = app.get(
            url,
            referer = referer ?: "$base/",
            headers = mapOf("Sec-Fetch-Dest" to "iframe")
        ).text

        val metin = if (sayfa.contains("eval(function(p,a,c,k,e")) {
            runCatching { getAndUnpack(sayfa) }.getOrNull()?.let { sayfa + "\n" + it } ?: sayfa
        } else {
            sayfa
        }

        val kaynaklar = Regex("""sources\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1)
        if (kaynaklar == null) {
            Log.d("NeO_JwKaynak", "kaynak bulunamadı » $url")
            return
        }

        Regex("""file\s*:\s*["']([^"']+)["']""").findAll(kaynaklar).forEach { eslesme ->
            val dosya = eslesme.groupValues[1].replace("\\/", "/")
            Log.d("NeO_JwKaynak", "$base » $dosya")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = dosya,
                    type   = if (dosya.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                ) {
                    this.referer = "$base/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to base)
                }
            )
        }

        Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(metin)?.groupValues?.get(1)?.let { izler ->
            Regex("""\{[^}]*?file\s*:\s*["']([^"']+)["'][^}]*?}""").findAll(izler).forEach { iz ->
                val blok  = iz.value
                if (blok.contains("thumbnails")) return@forEach
                val dosya = iz.groupValues[1].replace("\\/", "/")
                val etiket = Regex("""label\s*:\s*["']([^"']+)["']""").find(blok)?.groupValues?.get(1) ?: "Türkçe"
                subtitleCallback.invoke(newSubtitleFile(if (etiket.equals("Turkish", true)) "Türkçe" else etiket, dosya))
            }
        }
    }
}
