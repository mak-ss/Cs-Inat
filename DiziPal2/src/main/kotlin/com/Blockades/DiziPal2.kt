package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPal2 : MainAPI() {
    override var mainUrl              = "https://dizipal2143.com"
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

    // ============================================================================================
    // ANA SAYFA
    // ============================================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = when {
            // Son Bölümler sayfası
            request.data.contains("/bolumler") -> {
                document.select("div.episodes-list-grid > a.episode-list-item")
                    .mapNotNull { it.sonBolumler() }
            }
            // Ana sayfa (trend + son eklenenler)
            request.data == mainUrl || request.data == "$mainUrl/" -> {
                val items = mutableListOf<SearchResponse>()
                // Trend diziler
                document.select("a.trending-item").forEach { item ->
                    val href = item.attr("href")
                    if (href.contains("/dizi/")) {
                        items.add(dizilerFromTrend(item))
                    }
                }
                // Son eklenen diziler
                document.select("ul.content-grid.large > li").forEach { li ->
                    li.selectFirst("a")?.let { a ->
                        if (a.attr("href").contains("/dizi/")) {
                            items.add(dizilerFromCard(li))
                        }
                    }
                }
                // Son eklenen filmler
                document.select("ul.content-grid:not(.large) > li").forEach { li ->
                    li.selectFirst("a")?.let { a ->
                        if (a.attr("href").contains("/film/")) {
                            items.add(dizilerFromCard(li))
                        }
                    }
                }
                items
            }
            // Diğer sayfalar (diziler, filmler, platform, kategori)
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

    private fun dizilerFromCard(element: Element): SearchResponse? {
        return element.diziler()
    }

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

        // URL'ye göre tür belirleme
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
    // VİDEO LİNKLERİ — data-cfg çözümü + tüm extractor'lara yönlendirme
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
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                ).text
            } catch (e: Exception) {
                Log.e("DiziPal2", "Sayfa çekilemedi » ${e.message}")
                ""
            }

            if (pageHtml.isNotBlank()) {
                val norm = pageHtml.replace("\\/", "/").replace("&amp;", "&")

                // 2a) data-cfg değerini bul
                val cfgRegex = Regex("""data-cfg=["']([^"']+)["']""")
                val cfgMatch = cfgRegex.find(norm)?.groupValues?.getOrNull(1)

                // 2b) content-id
                val contentIdRegex = Regex("""data-content-id=["']([^"']+)["']""")
                val contentIdMatch = contentIdRegex.find(norm)?.groupValues?.getOrNull(1)

                Log.d("DiziPal2", "cfg=$cfgMatch, contentId=$contentIdMatch, url=$data")

                // 2c) Dizipal'in player API'sine POST at
                if (!cfgMatch.isNullOrBlank()) {
                    try {
                        val apiResponse = app.post(
                            "$mainUrl/player/api",
                            data = mapOf(
                                "cfg"        to cfgMatch,
                                "content_id" to (contentIdMatch ?: ""),
                                "type"       to if (data.contains("/bolum/")) "episode" else "movie"
                            ),
                            referer = data,
                            headers = mapOf(
                                "User-Agent"       to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                                "Accept"           to "application/json, text/plain, */*",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin"           to mainUrl
                            )
                        ).text

                        Log.d("DiziPal2", "player/api yanıtı = $apiResponse")
                        val apiNorm = apiResponse.replace("\\/", "/").replace("&amp;", "&")

                        // Dönen yanıtta m3u8 var mı?
                        Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*""")
                            .findAll(apiNorm).forEach { found += it.value }

                        // superadjacentsoddenly / formationfeed URL'leri
                        Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                            .findAll(apiNorm).forEach { found += it.value }

                        // JSON içinde "url", "embed", "source", "sources" alanları
                        try {
                            val json = org.json.JSONObject(apiNorm)
                            json.optString("url").takeIf { it.isNotBlank() }?.let { found += it }
                            json.optString("embed").takeIf { it.isNotBlank() }?.let { found += it }
                            json.optString("source").takeIf { it.isNotBlank() }?.let { found += it }
                            val sources = json.optJSONArray("sources")
                            if (sources != null) {
                                for (i in 0 until sources.length()) {
                                    val s = sources.getJSONObject(i)
                                    s.optString("file").takeIf { it.isNotBlank() }?.let { found += it }
                                    s.optString("url").takeIf { it.isNotBlank() }?.let { found += it }
                                }
                            }
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e("DiziPal2", "player/api POST HATA » ${e.message}")
                    }
                }

                // 2d) Sayfadaki doğrudan m3u8 (yedek)
                Regex("""https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*""")
                    .findAll(norm).forEach { found += it.value }

                // 2e) Sayfadaki superadjacentsoddenly / formationfeed URL'leri (yedek)
                Regex("""https?://[^\s"'\\<>]+(?:superadjacentsoddenly|formationfeed)[^\s"'\\<>]*""")
                    .findAll(norm).forEach { found += it.value }

                // 2f) iframe src
                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(norm).forEach { found += it.groupValues[1] }
            }
        }

        val unique = found.distinct().filter { it.isNotBlank() }
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
                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
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
