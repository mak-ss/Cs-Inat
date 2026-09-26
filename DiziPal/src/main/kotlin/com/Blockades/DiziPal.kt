// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
// ! TX (dizipal3081.live) yapısına göre güncellenmiştir.

package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal3081.live"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true

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

    // -------------------------------------------------------------------------
    // NEXT.JS DATA PARSER
    // -------------------------------------------------------------------------

    /**
     * Next.js sayfalarındaki `self.__next_f.push([1,"..."])` script'lerini birleştirip
     * içindeki JSON verisini döndürür.
     */
    private fun parseNextData(html: String): JSONObject? {
        val regex = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        regex.findAll(html).forEach { match ->
            var chunk = match.groupValues[1]
            // JSON string escape'lerini geri çöz
            chunk = chunk
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\/", "/")
            sb.append(chunk)
        }
        val full = sb.toString()
        if (full.isBlank()) return null

        // İlk `{` ile son `}` arasını al
        val start = full.indexOf('{')
        val end = full.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null

        return try {
            JSONObject(full.substring(start, end + 1))
        } catch (e: Exception) {
            Log.e("DZP", "Next data parse hatası: ${e.message}")
            null
        }
    }

    /**
     * JSON içindeki initialHeroItems / initialPopularItems / initialTrending /
     * initialNewReleases / initialLastEpisodes / initialComingSoon alanlarından
     * içerik çıkarır.
     */
    private fun extractItemsFromNext(json: JSONObject?): List<JSONObject> {
        if (json == null) return emptyList()
        val items = mutableListOf<JSONObject>()

        // "initialLayout" veya doğrudan component props'ları içinde arayalım
        fun deepFind(obj: JSONObject) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                when {
                    key.startsWith("initial") && value is JSONArray -> {
                        for (i in 0 until value.length()) {
                            value.optJSONObject(i)?.let { items.add(it) }
                        }
                    }
                    value is JSONObject -> deepFind(value)
                    value is JSONArray -> {
                        for (i in 0 until value.length()) {
                            value.optJSONObject(i)?.let { deepFind(it) }
                        }
                    }
                }
            }
        }
        deepFind(json)
        return items
    }

    // -------------------------------------------------------------------------
    // MAIN PAGE
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get(request.data)
        val html = response.text
        val json = parseNextData(html)

        // 1) Next.js JSON'dan çekmeyi dene
        val jsonItems = extractItemsFromNext(json)
        val home = mutableListOf<SearchResponse>()

        jsonItems.forEach { obj ->
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@forEach
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
            val poster = obj.optString("poster_url").takeIf { it.isNotBlank() }
            val type = obj.optString("_contentType").ifBlank { obj.optString("type") }
            val year = obj.optInt("release_year").takeIf { it > 0 }

            val href = fixUrl(url)
            if (type == "movie") {
                home.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(poster)
                    this.year = year
                })
            } else {
                home.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(poster)
                    this.year = year
                })
            }
        }

        // 2) JSON'dan gelmediyse DOM'a fallback yap
        if (home.isEmpty()) {
            val document = response.document
            document.select("a[href*='/filmler/'], a[href*='/diziler/'], a[href*='/movies/'], a[href*='/series/']")
                .forEach { el ->
                    val href = el.attr("href")
                    val title = el.selectFirst("h3, .card-title, [class*=title]")?.text()?.trim()
                        ?: el.attr("title").takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val poster = el.selectFirst("img")?.let {
                        it.attr("data-src").ifEmpty { it.attr("src") }
                    }
                    val isMovie = href.contains("/filmler/") || href.contains("/movies/")
                    val resp = if (isMovie) {
                        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                            this.posterUrl = fixUrlNull(poster)
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = fixUrlNull(poster)
                        }
                    }
                    home.add(resp)
                }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = false)
    }

    // -------------------------------------------------------------------------
    // SEARCH
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // Önce Ajax endpoint'i dene
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

            if (responseRaw.text.trim().startsWith("{")) {
                val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)
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
            Log.w("DZP", "Ajax arama başarısız, HTML fallback: ${e.message}")
        }

        // HTML fallback: /arama?q= veya /search?q=
        val searchUrls = listOf(
            "$mainUrl/arama?q=$query",
            "$mainUrl/search?q=$query",
            "$mainUrl/discover?q=$query"
        )
        for (su in searchUrls) {
            try {
                val doc = app.get(su).document
                val list = doc.select("a[href*='/filmler/'], a[href*='/diziler/'], a[href*='/movies/'], a[href*='/series/']")
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
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // -------------------------------------------------------------------------
    // LOAD
    // -------------------------------------------------------------------------

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

        // Next.js JSON'dan içerik bilgisi
        val json = parseNextData(html)

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val isSeries = url.contains("/diziler/") || url.contains("/series/")

        // JSON'dan detay çekmeye çalış
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
            ?: matched?.optInt("release_year")?.takeIf { it > 0 }

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

            // 1) JSON'dan bölümleri çek
            json?.let { j ->
                val episodeItems = mutableListOf<JSONObject>()
                fun deepEp(obj: JSONObject) {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.opt(k)
                        when {
                            v is JSONObject -> {
                                if (v.has("episode_number") && v.has("season_number")) {
                                    episodeItems.add(v)
                                }
                                deepEp(v)
                            }
                            v is JSONArray -> {
                                for (i in 0 until v.length()) {
                                    v.optJSONObject(i)?.let { child ->
                                        if (child.has("episode_number") && child.has("season_number")) {
                                            episodeItems.add(child)
                                        }
                                        deepEp(child)
                                    }
                                }
                            }
                        }
                    }
                }
                deepEp(j)

                episodeItems.forEach { ep ->
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
                document.select("a[href*='/bolum/'], a[href*='/episode/'], .episode-item, [class*=episode]").forEach { el ->
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

    // -------------------------------------------------------------------------
    // LOAD LINKS
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d("DZP", "Bulunan Token » $configToken")
        Log.d("DZP", "Yakalanan Çerezler » $cookies")

        val paddedToken = configToken + "=".repeat((4 - configToken.length % 4) % 4)
        val decodedToken = String(android.util.Base64.decode(paddedToken, android.util.Base64.DEFAULT))
        Log.d("DZP", "Decoded Token » $decodedToken")

        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(decodedToken)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL token içinden alınamadı! Dönen yanıt: $decodedToken")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        if (embedUrl.contains("imagestoo")) {
            val videoId = embedUrl.trimEnd('/').substringAfterLast("/")
            val imagestooApiUrl = "https://imagestoo.com/player/index.php?data=$videoId&do=getVideo"
            Log.d("DZP", "Imagestoo API URL » $imagestooApiUrl")

            val apiResponse = app.post(
                url = imagestooApiUrl,
                referer = embedUrl,
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
                    val cleanCookie = rawSetCookie.split(";").firstOrNull()
                    if (cleanCookie != null) sessionCookie = "$cleanCookie;"
                }
            }

            Log.d("DZP", "Yakalanan Cookie » $sessionCookie")
            val responseText = apiResponse.text
            val videoSourceRaw = Regex(""""securedLink"\s*:\s*"([^"]+)"""").find(responseText)?.groupValues?.getOrNull(1)

            if (videoSourceRaw != null) {
                val cleanUrl = videoSourceRaw.replace("\\/", "/")
                val finalM3u8Url = fixUrl(cleanUrl)
                Log.d("DZP", "Imagestoo Çözülen Video Kaynağı » $finalM3u8Url")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Dizipal (Imagestoo)",
                        url = finalM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = mapOf("Referer" to embedUrl).toString()
                        headers = mapOf("Cookie" to sessionCookie)
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            } else {
                Log.e("DZP", "Imagestoo API yanıtından videoSource çıkarılamadı! Yanıt: $apiResponse")
                return false
            }
        }

        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""v\s*:\s*["']([^"']+\.html.*?)["']""").find(embedSource)

        val extractedUrl = m3u8Match?.groupValues?.getOrNull(1)

        if (extractedUrl == null) {
            Log.e("DZP", "Embed kaynağında geçerli bir link bulunamadı!")
            return false
        }

        val finalM3u8Url = if (extractedUrl.contains(".html")) {
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

        if (finalM3u8Url == null) return false

        Log.d("DZP", "Bulunan M3U8 » $finalM3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = finalM3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            }
        )

        // Altyazılar
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)
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
}
