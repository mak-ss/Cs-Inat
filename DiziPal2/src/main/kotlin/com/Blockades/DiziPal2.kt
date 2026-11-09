package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal2 : MainAPI() {
    override var mainUrl              = "https://dizipal2148.com"
    override var name                 = "DiziPal2"
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
            Log.e("DiziPal2", "search JSON hatası » ${e.message}")
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
    // VİDEO LİNKLERİ — ajax-player-config endpoint'i
    // ============================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = mutableListOf<String>()

        // 1) data zaten m3u8 mi?
        if (data.contains(".m3u8", ignoreCase = true)) {
            found += data
        } else {
            // 2) Sayfayı çek
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
                Log.e("DiziPal2", "Sayfa çekilemedi » ${e.message}")
                ""
            }

            if (pageHtml.isNotBlank()) {
                Log.d("DiziPal2", "Sayfa HTML (ilk 500): ${pageHtml.take(500)}")

                val norm = pageHtml.replace("\\/", "/").replace("&amp;", "&")

                // 2a) Tüm iframe src'leri (bazı bölümlerde hâlâ olabilir)
                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(norm)
                    .forEach { found += it.groupValues[1] }

                // 2b) Doğrudan m3u8 linkleri (nadiren)
                Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                    .findAll(norm).forEach { found += it.value }

                // 2c) superadjacentsoddenly / formationfeed URL'leri
                Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                    .findAll(norm).forEach { found += it.value }

                // 2d) videoContainer data-cfg VEYA meta[name=_bc]
                //     Dizipal'in JS'i: document.getElementById("videoContainer").dataset.cfg
                //     <div id="videoContainer" data-cfg="...">
                val dataCfgMatch = Regex(
                    """<div[^>]*id=["']videoContainer["'][^>]*data-cfg=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(norm)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<div[^>]*data-cfg=["']([^"']+)["'][^>]*id=["']videoContainer["']""",
                        RegexOption.IGNORE_CASE
                    ).find(norm)?.groupValues?.getOrNull(1)

                // <meta name="_bc" content="..."> — iki attribute sırası
                val bcMatch = Regex(
                    """<meta\s+name=["']_bc["']\s+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(norm)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<meta\s+content=["']([^"']+)["']\s+name=["']_bc["']""",
                        RegexOption.IGNORE_CASE
                    ).find(norm)?.groupValues?.getOrNull(1)

                // Eski data-cfg (div üzerinde değil, farklı elementlerde olabilir)
                val anyCfgMatch = Regex("""data-cfg=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(norm)?.groupValues?.getOrNull(1)

                val cfgToSend = dataCfgMatch ?: anyCfgMatch ?: bcMatch

                if (!cfgToSend.isNullOrBlank()) {
                    Log.d("DiziPal2", "cfg=${cfgToSend.take(40)}... POST → ajax-player-config")
                    try {
                        // Dizipal JS: BASE_URL + "ajax-player-config"
                        // BASE_URL = meta[name="dp-base-url"] content veya "/"
                        val baseUrlMeta = Regex(
                            """<meta\s+name=["']dp-base-url["']\s+content=["']([^"']+)["']""",
                            RegexOption.IGNORE_CASE
                        ).find(norm)?.groupValues?.getOrNull(1) ?: "/"

                        val apiUrl = if (baseUrlMeta.startsWith("http"))
                            "${baseUrlMeta.trimEnd('/')}/ajax-player-config"
                        else
                            "$mainUrl/ajax-player-config"

                        Log.d("DiziPal2", "POST → $apiUrl")

                        val apiResponse = app.post(
                            apiUrl,
                            data = mapOf("cfg" to cfgToSend),
                            referer = data,
                            headers = mapOf(
                                "User-Agent"       to UA,
                                "Accept"           to "application/json, text/plain, */*",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin"           to mainUrl,
                                "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8"
                            )
                        ).text

                        Log.d("DiziPal2", "ajax-player-config yanıtı = ${apiResponse.take(800)}")

                        val apiNorm = apiResponse.replace("\\/", "/").replace("&amp;", "&")

                        // Dizipal JS: JSON.parse(responseText).config içinde {v, t, p}
                        try {
                            val json = org.json.JSONObject(apiNorm)

                            // Bazı sürümlerde {"success":true,"config":{...}}
                            val config = json.optJSONObject("config")
                                ?: json.optJSONObject("data")?.optJSONObject("config")

                            if (config != null) {
                                val v = config.optString("v", "")
                                val t = config.optString("t", "").lowercase()
                                Log.d("DiziPal2", "config.v=${v.take(120)} | config.t=$t")

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
                                // config yok, yanıt içinde m3u8/iframe arayalım
                                Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                                    .findAll(apiNorm).forEach { found += it.value }
                                Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                                    .findAll(apiNorm).forEach { found += it.value }
                            }
                        } catch (e: Exception) {
                            Log.e("DiziPal2", "JSON parse hatası » ${e.message}")
                            Regex("""https?://[^\s"'\\<>]+\.m3u8(?:\?[^\s"'\\<>]*)?[^\s"'\\<>]*""")
                                .findAll(apiNorm).forEach { found += it.value }
                            Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                                .findAll(apiNorm).forEach { found += it.value }
                        }
                    } catch (e: Exception) {
                        Log.e("DiziPal2", "ajax-player-config POST HATA » ${e.message}")
                    }
                } else {
                    Log.d("DiziPal2", "data-cfg/_bc bulunamadı, sadece HTML taraması")
                }
            }
        }

        // Tekilleştir + öncelik sırala
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
            Log.w("DiziPal2", "Hiçbir kaynak bulunamadı: $data")
            return false
        }

        Log.d("DiziPal2", "Bulunan kaynaklar: $unique")

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
                Log.e("DiziPal2", "loadLinks alt hata ($src) » ${e.message}")
            }
        }
        return true
    }
}
