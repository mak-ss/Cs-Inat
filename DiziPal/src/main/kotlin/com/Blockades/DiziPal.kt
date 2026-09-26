// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
// ! TX (dizipal3081.live) yapısına göre güncellenmiştir.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.json.JSONArray
import org.json.JSONObject

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal3081.live"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "${mainUrl}/"                       to "Ana Sayfa",
        "${mainUrl}/filmler"                to "Filmler",
        "${mainUrl}/diziler"                to "Diziler",
        "${mainUrl}/trending"               to "Trend",
        "${mainUrl}/popular/weekly"         to "Haftanın Popülerleri",
        "${mainUrl}/upcoming"               to "Vizyona Girecekler",
        "${mainUrl}/collections"            to "Koleksiyonlar",
        "${mainUrl}/genre/action"           to "Aksiyon",
        "${mainUrl}/genre/comedy"           to "Komedi",
        "${mainUrl}/genre/drama"            to "Drama",
        "${mainUrl}/genre/scifi"            to "Bilim Kurgu",
        "${mainUrl}/genre/thriller"         to "Gerilim",
        "${mainUrl}/genre/horror"           to "Korku",
        "${mainUrl}/genre/animation"        to "Animasyon",
        "${mainUrl}/genre/documentary"      to "Belgesel",
    )

    // =========================================================================
    // NEXT.JS / RSC DATA PARSER
    // =========================================================================

    /**
     * Next.js sayfalarındaki `self.__next_f.push([1,"..."])` script chunk'larını
     * birleştirip JSON döndürür. Ayrıca standalone RSC payload formatını
     * (`0:{...}`, `3:[...]` gibi satırlar) da parse eder.
     */
    private fun parseNextData(html: String): JSONObject? {
        // 1) Script chunk'larını birleştir
        val regex = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        regex.findAll(html).forEach { match ->
            var chunk = match.groupValues[1]
            chunk = chunk
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\/", "/")
            sb.append(chunk)
        }

        // 2) Script yoksa sayfa doğrudan RSC payload olabilir
        val raw = if (sb.isBlank()) html else sb.toString()

        // 3) RSC formatı: "0:{...}" veya "3:[...]" satırları
        val lineRegex = Regex("""(?m)^(\d+):(\{.*|\[.*)$""")
        val matches = lineRegex.findAll(raw).toList()

        if (matches.isNotEmpty()) {
            val joined = JSONObject()
            matches.forEach { m ->
                val id = m.groupValues[1]
                val jsonStr = m.groupValues[2].trimEnd(',')
                try {
                    if (jsonStr.startsWith("{")) {
                        joined.put(id, JSONObject(jsonStr))
                    } else {
                        joined.put(id, JSONArray(jsonStr))
                    }
                } catch (_: Exception) {}
            }
            if (joined.length() > 0) return joined
        }

        // 4) Fallback: ilk { ile son } arası
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            Log.e("DZP", "Next data parse hatası: ${e.message}")
            null
        }
    }

    /**
     * RSC JSON ağacındaki tüm initial* alanlarını (initialHeroItems, initialTrending vb.)
     * recursive olarak bulup içerik objelerini döndürür.
     */
    private fun extractItemsFromNext(json: JSONObject?): List<JSONObject> {
        if (json == null) return emptyList()
        val items = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()

        fun deepFind(obj: Any?) {
            when (obj) {
                is JSONObject -> {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.opt(key)
                        if (key.startsWith("initial") && value is JSONArray) {
                            for (i in 0 until value.length()) {
                                value.optJSONObject(i)?.let { items.add(it) }
                            }
                        } else {
                            deepFind(value)
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until obj.length()) {
                        deepFind(obj.opt(i))
                    }
                }
            }
        }
        deepFind(json)

        // Deduplicate by url
        return items.filter {
            val u = it.optString("url")
            if (u.isBlank()) return@filter false
            if (u in seen) return@filter false
            seen.add(u)
            true
        }
    }

    /**
     * Bir JSON objesini (RSC veya API'den) SearchResponse'a çevirir.
     */
    private fun nextItemToSearchResponse(obj: JSONObject): SearchResponse? {
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val poster = obj.optString("poster_url").takeIf { it.isNotBlank() }
            ?: obj.optString("poster").takeIf { it.isNotBlank() }
        val type = obj.optString("_contentType").ifBlank { obj.optString("type") }
        val year = obj.optInt("release_year").takeIf { it > 0 }
            ?: obj.optInt("year").takeIf { it > 0 }

        val href = fixUrl(url)
        return if (type == "movie" || url.contains("/filmler/") || url.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
            }
        }
    }

    // =========================================================================
    // MAIN PAGE
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data

        // 1) ANA SAYFA: Next.js RSC'den initial verileri çek
        if (url.trimEnd('/') == mainUrl.trimEnd('/')) {
            try {
                val response = app.get(url)
                val json = parseNextData(response.text)
                val items = extractItemsFromNext(json)
                val home = items.mapNotNull { obj -> nextItemToSearchResponse(obj) }
                if (home.isNotEmpty()) {
                    return newHomePageResponse(request.name, home, hasNext = false)
                }
            } catch (e: Exception) {
                Log.w("DZP", "Ana sayfa RSC parse başarısız: ${e.message}")
            }
        }

        // 2) LİSTELEME SAYFALARI: API endpoint'ini dene
        val apiResult = tryApiListing(url, page)
        if (apiResult.isNotEmpty()) {
            return newHomePageResponse(request.name, apiResult, hasNext = apiResult.size >= 20)
        }

        // 3) HTML/DOM fallback
        val response = app.get(url)
        val document = response.document
        val home = document.select("a[href*='/filmler/'], a[href*='/diziler/'], a[href*='/movies/'], a[href*='/series/']")
            .mapNotNull { el ->
                val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = el.selectFirst("h3, .card-title, [class*=title]")?.text()?.trim()
                    ?: el.attr("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val poster = el.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
                val isMovie = href.contains("/filmler/") || href.contains("/movies/")
                if (isMovie) newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                } else newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                }
            }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = false)
    }

    /**
     * /movies, /series gibi liste sayfaları için Next.js API endpoint'ini dener.
     */
    private suspend fun tryApiListing(url: String, page: Int): List<SearchResponse> {
        val isMovie = url.contains("/filmler") || url.contains("/movies")
        val type = if (isMovie) "movie" else "series"
        val slug = url.substringAfter(mainUrl).trim('/').substringAfterLast('/')

        val candidates = listOf(
            "$mainUrl/api/$type?page=$page",
            "$mainUrl/api/content?type=$type&page=$page",
            "$mainUrl/api/discover?type=$type&page=$page",
            "$mainUrl/api/load-more?type=$type&page=$page",
            "$mainUrl/api/$type?page=$page&slug=$slug",
            "$mainUrl/api/discover?type=$type&page=$page&genre=$slug"
        )

        for (endpoint in candidates) {
            try {
                val resp = app.get(
                    endpoint,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = url
                )

                val text = resp.text.trim()
                if (!text.startsWith("{") && !text.startsWith("[")) continue

                val root = JSONObject(
                    if (text.startsWith("[")) """{"data":$text}""" else text
                )

                val array = root.optJSONArray("data")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("movies")
                    ?: root.optJSONArray("series")
                    ?: continue

                val list = mutableListOf<SearchResponse>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val item = nextItemToSearchResponse(obj) ?: continue
                    list.add(item)
                }

                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                Log.d("DZP", "API denemesi başarısız ($endpoint): ${e.message}")
            }
        }
        return emptyList()
    }

    // =========================================================================
    // SEARCH
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        // 1) Ajax endpoint
        try {
            val searchUrl = "$mainUrl/ajax-search?q=$query"
            val responseRaw = app.get(
                searchUrl,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "$mainUrl/"
            )

            val body = responseRaw.text.trim()
            if (body.startsWith("{")) {
                val jsonResponse: DizipalSearchData =
                    mapper.readValue(body, DizipalSearchData::class.java)
                val list = mutableListOf<SearchResponse>()
                jsonResponse.results?.forEach { item ->
                    val title = item.title ?: return@forEach
                    val url = item.url ?: return@forEach
                    val href = fixUrl(url)
                    if (item.type.equals("Dizi", true) || item.type.equals("series", true)) {
                        list.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = fixUrlNull(item.poster)
                            this.year = item.year
                        })
                    } else {
                        list.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = fixUrlNull(item.poster)
                            this.year = item.year
                        })
                    }
                }
                if (list.isNotEmpty()) return list
            }
        } catch (e: Exception) {
            Log.w("DZP", "Ajax arama başarısız: ${e.message}")
        }

        // 2) Next.js search sayfası RSC
        val searchUrls = listOf(
            "$mainUrl/arama?q=$query",
            "$mainUrl/search?q=$query",
            "$mainUrl/discover?q=$query"
        )
        for (su in searchUrls) {
            try {
                val resp = app.get(su)
                val json = parseNextData(resp.text)
                val items = extractItemsFromNext(json)
                val list = items.mapNotNull { nextItemToSearchResponse(it) }
                if (list.isNotEmpty()) return list

                // DOM fallback
                val doc = resp.document
                val domList = doc.select("a[href*='/filmler/'], a[href*='/diziler/'], a[href*='/movies/'], a[href*='/series/']")
                    .mapNotNull { el ->
                        val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val title = el.selectFirst("h3, .card-title, [class*=title]")?.text()?.trim()
                            ?: return@mapNotNull null
                        val poster = el.selectFirst("img")?.let {
                            it.attr("data-src").ifEmpty { it.attr("src") }
                        }
                        val isMovie = href.contains("/filmler/") || href.contains("/movies/")
                        if (isMovie) newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                            this.posterUrl = fixUrlNull(poster)
                        } else newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = fixUrlNull(poster)
                        }
                    }
                if (domList.isNotEmpty()) return domList
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // =========================================================================
    // LOAD
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        // Bölüm linki yönlendirmesi
        if (url.contains("/bolum/") || url.contains("/episode/")) {
            val seriesUrl = url
                .replace("/bolum/", "/dizi/")
                .replace("/episode/", "/series/")
                .replace(Regex("-\\d+-sezon.*"), "")
                .replace(Regex("-season-\\d+.*"), "")
            return load(seriesUrl)
        }

        val response = app.get(url)
        val html = response.text
        val document = response.document
        val json = parseNextData(html)

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val isSeries = url.contains("/diziler/") || url.contains("/series/")

        // RSC içindeki eşleşen objeyi bul
        val items = extractItemsFromNext(json)
        val matched = items.firstOrNull { obj ->
            val u = obj.optString("url")
            u.isNotBlank() && (url.endsWith(u) || url.contains(u.removePrefix("/")))
        }

        val title = matched?.optString("title")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" izle")?.trim()
            ?: return null

        val year = matched?.optInt("release_year")?.takeIf { it > 0 }

        val description = matched?.optString("description")?.takeIf { it.isNotBlank() }
            ?: matched?.optString("short_description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("p.series-description, .description, [class*=description]")?.text()?.trim()

        val tags = mutableListOf<String>()
        matched?.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.opt(i)
                when (g) {
                    is String -> tags.add(g)
                    is JSONObject -> g.optString("name").takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }
            }
        }
        if (tags.isEmpty()) {
            document.select("a[href*='/genre/']").forEach { tags.add(it.text().trim()) }
        }

        val duration = matched?.optInt("runtime")?.takeIf { it > 0 }

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            // 1) RSC JSON'dan bölümleri çek
            json?.let { j ->
                val episodeItems = mutableListOf<JSONObject>()

                fun deepEp(obj: Any?) {
                    when (obj) {
                        is JSONObject -> {
                            if (obj.has("episode_number") && obj.has("season_number")) {
                                episodeItems.add(obj)
                            }
                            val keys = obj.keys()
                            while (keys.hasNext()) {
                                deepEp(obj.opt(keys.next()))
                            }
                        }
                        is JSONArray -> {
                            for (i in 0 until obj.length()) {
                                deepEp(obj.opt(i))
                            }
                        }
                    }
                }
                deepEp(j)

                episodeItems.distinctBy { it.optString("url") }.forEach { ep ->
                    val epTitle = ep.optString("episode_title").takeIf { it.isNotBlank() }
                        ?: ep.optString("title").takeIf { it.isNotBlank() }
                        ?: "Bölüm"
                    val epSeason = ep.optInt("season_number").takeIf { it > 0 }
                    val epNum = ep.optInt("episode_number").takeIf { it > 0 }
                    val epHref = ep.optString("url").takeIf { it.isNotBlank() }
                        ?: "$url/sezon-${epSeason ?: 1}/bolum-${epNum ?: 1}"
                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.season = epSeason
                        this.episode = epNum
                    })
                }
            }

            // 2) DOM fallback
            if (episodes.isEmpty()) {
                document.select("a[href*='/bolum/'], a[href*='/episode/'], .episode-item, [class*=episode]")
                    .forEach { el ->
                        val epHref = el.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                        val epName = el.selectFirst(".title, [class*=title], h3, h4")?.text()?.trim()
                            ?: el.text().trim().takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val subtitle = el.selectFirst(".subtitle, [class*=subtitle]")?.text()?.trim() ?: ""
                        val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)
                            ?: Regex("""S(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE).find(subtitle)
                        episodes.add(newEpisode(fixUrl(epHref)) {
                            this.name = epName
                            this.season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                            this.episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                        })
                    }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        }
    }

    // =========================================================================
    // LOAD LINKS
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val html = getResponse.text
        val document = getResponse.document

        // =====================================================================
        // 1) EMBED URL'İ BULMA — Çoklu strateji
        // =====================================================================

        var embedUrl: String? = null

        // 1a) Klasik data-cfg token'ı
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()
        if (!configToken.isNullOrEmpty()) {
            try {
                val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
                val decoded = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
                Log.d("DZP", "data-cfg decoded » $decoded")
                embedUrl = Regex(""""v"\s*:\s*"([^"]+)"""").find(decoded)
                    ?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            } catch (e: Exception) {
                Log.w("DZP", "data-cfg decode başarısız: ${e.message}")
            }
        }

        // 1b) Alternatif data attribute'ları
        if (embedUrl.isNullOrEmpty()) {
            val altAttrs = listOf("data-config", "data-player", "data-source", "data-video", "data-embed")
            for (attr in altAttrs) {
                val attrVal = document.selectFirst("[#videoContainer], [class*=player], [id*=player]")
                    ?.attr(attr)?.trim()
                if (!attrVal.isNullOrEmpty()) {
                    try {
                        val padded = attrVal + "=".repeat((4 - attrVal.length % 4) % 4)
                        val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
                        val found = Regex(""""v"\s*:\s*"([^"]+)"""").find(decoded)
                            ?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                        if (!found.isNullOrEmpty()) {
                            embedUrl = found
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 1c) iframe src
        if (embedUrl.isNullOrEmpty()) {
            val iframeSrc = document.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!iframeSrc.isNullOrEmpty() && !iframeSrc.contains("youtube") && !iframeSrc.contains("youtu.be")) {
                embedUrl = iframeSrc
            }
        }

        // 1d) Next.js RSC JSON içinden embed/player URL'i ara
        if (embedUrl.isNullOrEmpty()) {
            val json = parseNextData(html)
            if (json != null) {
                val keysToFind = listOf(
                    "video_url", "embed_url", "player_url", "iframe_url", "source", "url", "src", "file"
                )
                embedUrl = findStringInJson(json, keysToFind) { value ->
                    value.startsWith("http") &&
                        (value.contains("/embed") || value.contains("/player") ||
                         value.contains("/video") || value.contains(".m3u8"))
                }
            }
        }

        // 1e) Script tag'leri içinde regex ile ara
        if (embedUrl.isNullOrEmpty()) {
            val patterns = listOf(
                Regex("""["'](https?://[^"']+/embed[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""["']v["']\s*:\s*["']([^"']+)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""source\s*:\s*["']([^"']+)["']""")
            )
            for (p in patterns) {
                val m = p.find(html)
                val found = m?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                if (!found.isNullOrEmpty() && found.startsWith("http")) {
                    embedUrl = found
                    break
                }
            }
        }

        if (embedUrl.isNullOrEmpty()) {
            Log.e("DZP", "Hiçbir yöntemle embed URL bulunamadı!")
            return false
        }

        val finalEmbedUrl = fixUrl(embedUrl)
        Log.d("DZP", "Çözülen Embed URL » $finalEmbedUrl")

        // =====================================================================
        // 2) IMAGESTOO ÖZEL İŞLEME
        // =====================================================================
        if (finalEmbedUrl.contains("imagestoo")) {
            val videoId = finalEmbedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = finalEmbedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                )
            )

            var sessionCookie = ""
            val playerToken = apiResponse.cookies["fireplayer_player"]
            if (!playerToken.isNullOrEmpty()) {
                sessionCookie = "fireplayer_player=$playerToken"
            } else {
                val rawSetCookie = apiResponse.headers["Set-Cookie"] ?: apiResponse.headers["set-cookie"]
                if (rawSetCookie != null && rawSetCookie.contains("fireplayer_player")) {
                    sessionCookie = rawSetCookie.split(";").firstOrNull() ?: ""
                }
            }

            val responseText = apiResponse.text
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                val finalM3u8Url = fixUrl(videoSourceRaw.replace("\\/", "/"))
                Log.d("DZP", "Imagestoo M3U8 » $finalM3u8Url")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = finalEmbedUrl
                        headers = mapOf("Cookie" to sessionCookie)
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            Log.e("DZP", "Imagestoo videoSource alınamadı!")
            return false
        }

        // =====================================================================
        // 3) DOĞRUDAN M3U8 İSE HEMEN VER
        // =====================================================================
        if (finalEmbedUrl.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Dizipal (M3U8)",
                    url = finalEmbedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = data
                    quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // =====================================================================
        // 4) EMBED SAYFASINI ÇEK VE M3U8 ÇIKAR
        // =====================================================================
        val embedSource = app.get(
            url = finalEmbedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""")
            .find(embedSource)
            ?: Regex("""file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı! Embed: $finalEmbedUrl")
            return false
        }

        val finalM3u8Url = if (extractedUrl.contains(".html") && !extractedUrl.contains(".m3u8")) {
            val idRegex = Regex("""embed-([^.]+)\.html""")
            val idMatch = idRegex.find(extractedUrl)?.groupValues?.getOrNull(1)
            if (idMatch != null) {
                "https://s2.superadjacentsoddenly.xyz/hls2/01/00007/${idMatch}_,n,h,.urlset/master.m3u8"
            } else {
                Log.e("DZP", "HTML linkinden ID ayıklanamadı: $extractedUrl")
                null
            }
        } else {
            extractedUrl
        }

        if (finalM3u8Url.isNullOrEmpty()) return false

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = finalEmbedUrl
                quality = Qualities.Unknown.value
            }
        )

        // =====================================================================
        // 5) ALTYAZILAR
        // =====================================================================
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(embedSource)
        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]
                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)
                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"
                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(SubtitleFile(lang = label, url = fixUrl(fileUrl)))
                }
            }
        }

        return true
    }

    /**
     * JSON ağacında verilen key isimlerinden birini bulup predicate'e uyan ilk
     * String değeri döndürür.
     */
    private fun findStringInJson(
        obj: Any?,
        keys: List<String>,
        predicate: (String) -> Boolean
    ): String? {
        when (obj) {
            is JSONObject -> {
                val iter = obj.keys()
                while (iter.hasNext()) {
                    val k = iter.next()
                    val v = obj.opt(k)
                    if (k in keys && v is String && predicate(v)) return v
                    val nested = findStringInJson(v, keys, predicate)
                    if (nested != null) return nested
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val nested = findStringInJson(obj.opt(i), keys, predicate)
                    if (nested != null) return nested
                }
            }
        }
        return null
    }
}
