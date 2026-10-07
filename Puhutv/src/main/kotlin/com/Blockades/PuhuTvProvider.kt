package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale

class PuhuTVProvider : MainAPI() {
    override var mainUrl = "https://puhutv.com"
    override var name = "PuhuTV"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf(
        "/" to "Ana Sayfa",
        "/dizi" to "Diziler",
        "/yerli-diziler" to "Yerli Diziler",
        "/puhutv-orijinal" to "PuhuTV Orijinal"
    )

    private fun fixUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$mainUrl$raw"
            else -> "$mainUrl/$raw"
        }
    }

    // ============ ARAMA ============
    override suspend fun search(query: String): List<SearchResponse> {
        val slug: String = query.slug()
        if (slug.isBlank()) return emptyList()
        val data: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$slug-detay").text).getJSONObject("data")
        } catch (_: Exception) {
            return emptyList()
        }
        val title: String = data.optString("name").ifBlank { slug.titleTr() }
        val seasons = data.optJSONArray("seasons")
        val content: JSONObject? = data.optJSONObject("content")
        val poster: String? = content?.image() ?: data.image()
        return if (seasons != null && seasons.length() > 0) {
            listOf(newTvSeriesSearchResponse(title, "$mainUrl/$slug-detay") { posterUrl = poster })
        } else {
            val assets = data.optJSONArray("assets")
            val firstAsset: JSONObject? = if (assets == null || assets.length() == 0) null else assets.optJSONObject(0)
            val videoSlug: String = if (firstAsset == null) slug else firstAsset.optString("slug").removeSuffix("-izle")
            listOf(newMovieSearchResponse(title, "$mainUrl/$videoSlug-izle", TvType.Movie) { posterUrl = poster })
        }
    }

    // ============ ANA SAYFA ============
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path: String = request.data.ifBlank { "/" }
        val url = if (page > 1) "$mainUrl$path?sayfa=$page" else "$mainUrl$path"

        println("PuhuTV getMainPage URL: $url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            println("PuhuTV getMainPage HATA: ${e.message}")
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val nextDataPosters: Map<String, String> = extractNextDataPosters(document)
        println("PuhuTV __NEXT_DATA__ poster haritası boyutu: ${nextDataPosters.size}")

        val elements = document.select(
            "a[href*=-detay], a[href*=-izle], " +
            "a[href^=list/], a[href*=/list/]"
        )

        println("PuhuTV getMainPage bulunan element sayısı: ${elements.size}")

        val results: List<SearchResponse> = elements
            .mapNotNull { element -> element.toResponse(nextDataPosters) }
            .distinctBy { it.url }
            .take(40)

        println("PuhuTV getMainPage sonuç sayısı: ${results.size}")

        val hasNext = results.isNotEmpty() && document.selectFirst("a[href*='sayfa=${page + 1}']") != null
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    private fun extractNextDataPosters(document: org.jsoup.nodes.Document): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val script = document.selectFirst("script#__NEXT_DATA__") ?: return map
            val jsonText = script.data().ifBlank { script.html() }
            if (jsonText.isBlank()) return map

            val root = JSONObject(jsonText)
            val containerItems = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("data")
                ?.optJSONObject("data")
                ?.optJSONArray("container_items")
                ?: return map

            for (i in 0 until containerItems.length()) {
                val container = containerItems.optJSONObject(i) ?: continue
                val items = container.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val meta = item.optJSONObject("meta")
                    val slug = meta?.optString("slug").orEmpty()
                    if (slug.isBlank()) continue

                    val image = item.optString("image")
                        .ifBlank { item.optString("image_vertical_mobile") }
                    if (image.isNotBlank() && (image.startsWith("http") || image.startsWith("//"))) {
                        map[slug] = if (image.startsWith("//")) "https:$image" else image
                    }
                }
            }
        } catch (e: Exception) {
            println("PuhuTV __NEXT_DATA__ parse hatası: ${e.message}")
        }
        return map
    }

    // ============ İÇERİK YÜKLE ============
    override suspend fun load(url: String): LoadResponse? {
        val requestedPath: String = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (requestedPath.isBlank()) return null

        // ⬇️ Liste sayfalarını atla (film/dizi değil)
        if (requestedPath.startsWith("list/")) {
            println("PuhuTV load: Liste sayfası atlanıyor → $requestedPath")
            return null
        }

        val isWatch: Boolean = requestedPath.endsWith("-izle") || requestedPath.contains("-bolum-izle")

        val cleanSlug = requestedPath
            .removeSuffix("-detay")
            .removeSuffix("-izle")

        if (cleanSlug.isBlank()) return null

        // ⬇️ Bölüm linki mi? (tek bölüm → Movie olarak dön)
        val isEpisodeLink = isWatch && (
            cleanSlug.contains(Regex("""-\d+-bolum$""")) ||
            cleanSlug.endsWith("-pilot-bolum") ||
            cleanSlug.contains(Regex("""-\d+-sezon-\d+-bolum$""")) ||
            cleanSlug.contains("-bolum")
        )

        if (isEpisodeLink && isWatch) {
            println("PuhuTV load: Bölüm linki algılandı → $requestedPath")
            val data: JSONObject = try {
                JSONObject(app.get("$mainUrl/api/slug/$cleanSlug-izle").text).getJSONObject("data")
            } catch (e: Exception) {
                println("PuhuTV load: Bölüm API hatası = ${e.message}")
                return null
            }
            val title: String = data.optString("name").ifBlank { cleanSlug.titleTr() }
            val content: JSONObject? = data.optJSONObject("content")
            val poster: String? = content?.image() ?: data.image()

            val parentPoster: String? = data.optJSONObject("title")
                ?.optString("image")
                ?.takeIf { it.isNotBlank() }
                ?: data.optJSONObject("title")
                    ?.optString("image_vertical_mobile")
                    ?.takeIf { it.isNotBlank() }

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = parentPoster ?: poster
                plot = data.optString("description").takeIf { it.isNotBlank() }
            }
        }

        // ============================================================
        // DETAY SAYFASI
        // 1) Önce API'den dene (bölüm listesi burada gelir)
        // 2) Başarısız olursa HTML + __NEXT_DATA__ fallback
        // ============================================================
        println("PuhuTV load: Detay yükleniyor → $requestedPath")

        // ---- 1) API YOLU ----
        val apiData: JSONObject? = try {
            JSONObject(app.get("$mainUrl/api/slug/$cleanSlug-detay").text).optJSONObject("data")
        } catch (e: Exception) {
            println("PuhuTV load: Detay API hatası = ${e.message}")
            null
        }

        if (apiData != null) {
            val title: String = apiData.optString("name")
                .ifBlank { cleanSlug.titleTr() }
            val content: JSONObject? = apiData.optJSONObject("content")
            val poster: String? = content?.image()
                ?: apiData.optString("image").takeIf { it.isNotBlank() }
                ?: apiData.optString("image_vertical_mobile").takeIf { it.isNotBlank() }

            val seasons = apiData.optJSONArray("seasons")
            val episodes = mutableListOf<Episode>()

            if (seasons != null && seasons.length() > 0) {
                println("PuhuTV load: $title → ${seasons.length()} sezon (API), bölümler çekiliyor")

                for (s in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(s) ?: continue
                    val seasonId = season.optString("id")
                        .ifBlank { season.optString("_id") }
                        .ifBlank { season.optString("season_id") }
                    val seasonNumber = season.optInt("number", s + 1).let { if (it <= 0) s + 1 else it }

                    if (seasonId.isBlank()) {
                        println("PuhuTV load: Sezon $seasonNumber id BOŞ, atlanıyor")
                        continue
                    }

                    // ✅ Doğru endpoint: /seasons/{id}/episodes
                    val epsArr = fetchSeasonEpisodes(seasonId)
                    if (epsArr == null || epsArr.length() == 0) {
                        println("PuhuTV load: Sezon $seasonNumber için bölüm bulunamadı")
                        continue
                    }

                    println("PuhuTV load: Sezon $seasonNumber → ${epsArr.length()} bölüm")

                    for (i in 0 until epsArr.length()) {
                        val ep = epsArr.optJSONObject(i) ?: continue
                        // PuhuTV'de bölüm yolu genelde "url" veya "slug" alanında
                        val slugPath = ep.optString("url")
                            .ifBlank { ep.optString("slug") }
                            .ifBlank { ep.optString("slugPath") }
                            .ifBlank { ep.optString("slug_path") }
                            .ifBlank { ep.optString("path") }
                        if (slugPath.isBlank()) continue

                        val fullUrl = when {
                            slugPath.startsWith("http") -> slugPath
                            slugPath.startsWith("/") -> "$mainUrl$slugPath"
                            else -> "$mainUrl/$slugPath"
                        }

                        val epPoster: String? = ep.optJSONObject("content")?.image()
                            ?: ep.optString("image").takeIf { it.isNotBlank() }
                            ?: poster

                        episodes.add(newEpisode(fullUrl) {
                            name = ep.optString("name")
                                .ifBlank { ep.optString("eventLabel") }
                                .ifBlank { ep.optString("event_label") }
                                .ifBlank { "Bölüm ${i + 1}" }
                            this.season = ep.optInt("season", seasonNumber).let { if (it <= 0) seasonNumber else it }
                            this.episode = ep.optInt("number", i + 1).let { if (it <= 0) i + 1 else it }
                            posterUrl = epPoster
                            description = ep.optString("description").takeIf { it.isNotBlank() }
                        })
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                val sorted = episodes
                    .distinctBy { it.data }
                    .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

                println("PuhuTV load: $title → API'den ${sorted.size} bölüm dönülüyor")
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                    posterUrl = poster
                    plot = apiData.optString("description").takeIf { it.isNotBlank() }
                }
            }

            // Bölüm yok → film olabilir
            val assets = apiData.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                val firstAsset = assets.optJSONObject(0)
                val videoSlug = firstAsset?.optString("slug")?.removeSuffix("-izle").orEmpty()
                if (videoSlug.isNotBlank()) {
                    val watchUrl = "$mainUrl/$videoSlug-izle"
                    return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                        posterUrl = poster
                        plot = apiData.optString("description").takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        // ---- 2) HTML + __NEXT_DATA__ FALLBACK ----
        println("PuhuTV load: API başarısız, HTML fallback → $requestedPath")

        val document = try {
            app.get("$mainUrl/$requestedPath").document
        } catch (e: Exception) {
            println("PuhuTV load: Detay HTML hatası = ${e.message}")
            return null
        }

        val nextData = try {
            document.selectFirst("script#__NEXT_DATA__")?.let { script ->
                val jsonText = script.data().ifBlank { script.html() }
                if (jsonText.isNotBlank()) JSONObject(jsonText) else null
            }
        } catch (e: Exception) {
            println("PuhuTV load: __NEXT_DATA__ parse hatası = ${e.message}")
            null
        }

        val titleData = findTitleData(nextData)
        if (titleData == null) {
            println("PuhuTV load: __NEXT_DATA__ içinde title verisi bulunamadı")
            return null
        }

        val title: String = titleData.optString("name")
            .ifBlank { titleData.optString("title") }
            .ifBlank { cleanSlug.titleTr() }
        val content: JSONObject? = titleData.optJSONObject("content")
        val poster: String? = content?.image()
            ?: titleData.optString("image").takeIf { it.isNotBlank() }
            ?: titleData.optString("image_vertical_mobile").takeIf { it.isNotBlank() }

        println("PuhuTV load: title=$title, poster=$poster")

        val nextDataEpisodes = extractEpisodesFromNextData(titleData)
        println("PuhuTV load: $title → __NEXT_DATA__'dan ${nextDataEpisodes.size} bölüm")

        if (nextDataEpisodes.isNotEmpty()) {
            val sorted = nextDataEpisodes
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                posterUrl = poster
                plot = titleData.optString("description").takeIf { it.isNotBlank() }
            }
        }

        // Bölüm yok — film olabilir
        val assets = titleData.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            val firstAsset: JSONObject = assets.optJSONObject(0) ?: return null
            val videoSlug: String = firstAsset.optString("slug").removeSuffix("-izle")
            if (videoSlug.isNotBlank()) {
                val watchUrl = "$mainUrl/$videoSlug-izle"
                return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                    posterUrl = poster
                    plot = titleData.optString("description").takeIf { it.isNotBlank() }
                }
            }
        }

        println("PuhuTV load: $title için ne bölüm ne asset bulundu")
        return null
    }

    // ============ SEZON BÖLÜMLERİNİ API'DEN ÇEK ============
    private suspend fun fetchSeasonEpisodes(seasonId: String): org.json.JSONArray? {
        // PuhuTV'nin gerçek endpoint'i: /seasons/{id}/episodes
        val endpoints = listOf(
            "https://galadriel.puhutv.com/seasons/$seasonId/episodes?page=1&per=100",
            "https://galadriel.puhutv.com/api/seasons/$seasonId/episodes?page=1&per=100",
            "https://galadriel.puhutv.com/seasons/$seasonId?page=1&per=100",
            "https://api.puhutv.com/seasons/$seasonId/episodes?page=1&per=100",
            "$mainUrl/api/seasons/$seasonId/episodes?page=1&per=100",
            "$mainUrl/api/seasons/$seasonId?page=1&per=100"
        )

        for (endpoint in endpoints) {
            try {
                val response = app.get(endpoint).text
                if (response.isBlank() || !response.trimStart().startsWith("{")) continue

                val json = JSONObject(response)

                // Olası tüm yapıları dene
                val arr = json.optJSONArray("episodes")
                    ?: json.optJSONObject("data")?.optJSONArray("episodes")
                    ?: json.optJSONArray("items")
                    ?: json.optJSONObject("data")?.optJSONArray("items")
                    ?: json.optJSONArray("data")

                if (arr != null && arr.length() > 0) {
                    println("PuhuTV fetchSeasonEpisodes: BAŞARILI → $endpoint (${arr.length()} bölüm)")
                    return arr
                }
            } catch (e: Exception) {
                println("PuhuTV fetchSeasonEpisodes hata ($endpoint) = ${e.message}")
            }
        }
        return null
    }

    // ⬇️ __NEXT_DATA__ içinden title objesini bul (recursive)
    // ✅ DÜZELTİLDİ: data.data.title yolu eklendi
    private fun findTitleData(root: JSONObject?): JSONObject? {
        if (root == null) return null

        val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")

        // pageProps.title
        pageProps?.optJSONObject("title")?.let { return it }

        val dataLevel1 = pageProps?.optJSONObject("data")
        if (dataLevel1 != null) {
            // data.title
            dataLevel1.optJSONObject("title")?.let { return it }

            // ✅ data.data.title  (EN KRİTİK EKSİK YOL)
            val dataLevel2 = dataLevel1.optJSONObject("data")
            if (dataLevel2 != null) {
                dataLevel2.optJSONObject("title")?.let { return it }
                dataLevel2.optJSONObject("series")?.let { return it }
                dataLevel2.optJSONObject("movie")?.let { return it }

                // data.data içinde assets/seasons/episodes varsa direkt onu dön
                if ((dataLevel2.has("assets") || dataLevel2.has("seasons") || dataLevel2.has("episodes")) &&
                    (dataLevel2.has("name") || dataLevel2.has("slug") || dataLevel2.has("title"))
                ) {
                    return dataLevel2
                }
            }

            // data.items[] içinde ara
            val items = dataLevel1.optJSONArray("items")
            if (items != null && items.length() > 0) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.has("assets") || item.has("seasons") || item.has("episodes")) {
                        return item
                    }
                }
            }
        }

        // Derin arama
        return deepFindTitle(root)
    }

    private fun deepFindTitle(obj: JSONObject): JSONObject? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            when (value) {
                is JSONObject -> {
                    if ((value.has("assets") || value.has("seasons") || value.has("episodes")) &&
                        (value.has("name") || value.has("slug") || value.has("title"))
                    ) {
                        return value
                    }
                    val found = deepFindTitle(value)
                    if (found != null) return found
                }
                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        if ((item.has("assets") || item.has("seasons") || item.has("episodes")) &&
                            (item.has("name") || item.has("slug") || item.has("title"))
                        ) {
                            return item
                        }
                        val found = deepFindTitle(item)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    // ⬇️ __NEXT_DATA__ içindeki bölümleri çıkar
    private fun extractEpisodesFromNextData(titleData: JSONObject): List<Episode> {
        val episodes = mutableListOf<Episode>()

        titleData.optJSONArray("episodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ep = arr.optJSONObject(i) ?: continue
                parseEpisode(ep, 1)?.let { episodes.add(it) }
            }
        }

        titleData.optJSONArray("seasons")?.let { seasons ->
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val seasonNumber = season.optInt("number", s + 1).let { if (it <= 0) s + 1 else it }

                for (field in listOf("episodes", "items", "assets")) {
                    season.optJSONArray(field)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val ep = arr.optJSONObject(i) ?: continue
                            parseEpisode(ep, seasonNumber)?.let { episodes.add(it) }
                        }
                    }
                }
            }
        }

        titleData.optJSONArray("container_items")?.let { containers ->
            for (c in 0 until containers.length()) {
                val container = containers.optJSONObject(c) ?: continue
                val items = container.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    parseEpisode(item, 1)?.let { episodes.add(it) }

                    item.optJSONArray("assets")?.let { assets ->
                        for (a in 0 until assets.length()) {
                            val asset = assets.optJSONObject(a) ?: continue
                            parseEpisode(asset, 1)?.let { episodes.add(it) }
                        }
                    }
                }
            }
        }

        return episodes.distinctBy { it.data }
    }

    // ✅ DÜZELTİLDİ: PuhuTV gerçek alanları (url/slug) önce deneniyor
    private fun parseEpisode(ep: JSONObject, defaultSeason: Int): Episode? {
        val slugPath = ep.optString("url")
            .ifBlank { ep.optString("slug") }
            .ifBlank { ep.optString("slugPath") }
            .ifBlank { ep.optString("slug_path") }
            .ifBlank { ep.optString("path") }

        if (slugPath.isBlank()) return null

        val fullUrl = when {
            slugPath.startsWith("http") -> slugPath
            slugPath.startsWith("/") -> "$mainUrl$slugPath"
            else -> "$mainUrl/$slugPath"
        }

        val name = ep.optString("name")
            .ifBlank { ep.optString("title") }
            .ifBlank { ep.optString("eventLabel") }
            .ifBlank { ep.optString("event_label") }
            .ifBlank { ep.optString("display_name") }

        val seasonNum = ep.optInt("season", defaultSeason).let {
            if (it <= 0) defaultSeason else it
        }
        val episodeNum = ep.optInt("number", 0).let {
            if (it <= 0) ep.optInt("episode", 0) else it
        }

        val episodePoster: String? = ep.optJSONObject("content")?.image()
            ?: ep.optString("image").takeIf { it.isNotBlank() }
            ?: ep.optString("image_vertical_mobile").takeIf { it.isNotBlank() }

        return newEpisode(fullUrl) {
            this.name = name.ifBlank { "Bölüm" }
            this.season = seasonNum
            this.episode = episodeNum
            this.posterUrl = episodePoster
            this.description = ep.optString("description").takeIf { it.isNotBlank() }
        }
    }

    // ============ LİNKLERİ YÜKLE ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug: String = data.substringAfter(mainUrl).substringBefore("?").trim('/').removeSuffix("-izle")
        val info: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$slug-izle").text).getJSONObject("data")
        } catch (_: Exception) {
            return false
        }
        val id: String = info.getString("id")
        if (id.isBlank()) return false
        val videos = try {
            JSONObject(app.get("$mainUrl/api/assets/$id/videos").text).getJSONObject("data").getJSONArray("videos")
        } catch (_: Exception) {
            return false
        }
        for (index in 0 until videos.length()) {
            val video: JSONObject = videos.getJSONObject(index)
            val streamUrl: String = video.optString("url")
            if (!streamUrl.startsWith("https://")) continue
            val quality: Int = video.optInt("quality", Qualities.Unknown.value)
            val format: String = video.optString("video_format")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (format == "hls" || streamUrl.contains(".m3u8", true))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                }
            )
        }
        return true
    }

    // ============ HTML ELEMENT → SearchResponse ============
    private fun Element.toResponse(nextDataPosters: Map<String, String>): SearchResponse? {
        val rawHref: String = attr("href")
        val href: String = fixUrl(rawHref) ?: return null

        if (!href.startsWith(mainUrl)) return null

        val slug: String = href.substringAfterLast("/").substringBefore("?").trim('/')
        if (slug.isBlank()) return null

        val isDetail: Boolean = slug.endsWith("-detay")
        val isIzle: Boolean = slug.endsWith("-izle")
        val isList: Boolean = rawHref.contains("list/") || href.contains("/list/")

        if (!isDetail && !isIzle && !isList) return null

        val isEpisode = isIzle && slug.contains("-bolum-izle")
        if (isEpisode) return null

        val image: Element? = selectFirst("img")
        val alt: String = image?.attr("alt") ?: ""

        val title: String = if (alt.isNotBlank()) {
            alt
        } else {
            slug.removeSuffix("-detay").removeSuffix("-izle").titleTr()
        }

        val slugKey = slug.removeSuffix("-detay").removeSuffix("-izle") + "-detay"
        val fromNextData = nextDataPosters[slugKey]
        val fromHtml = if (fromNextData == null) findPoster() else null
        val poster: String? = fromNextData ?: fromHtml

        return when {
            isDetail -> newTvSeriesSearchResponse(title, href) { posterUrl = poster }
            isIzle -> newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            isList -> newTvSeriesSearchResponse(title, href) { posterUrl = poster }
            else -> null
        }
    }

    private fun Element.findPoster(): String? {
        selectFirst("noscript")?.let { ns ->
            val nsHtml = ns.html()
            val m = Regex("""<img[^>]+src=["']([^"']+)["']""").find(nsHtml)
            if (m != null) {
                val url = m.groupValues[1]
                if (url.isNotBlank() && !url.startsWith("data:")) {
                    fixUrl(url)?.let { return it }
                }
            }
        }

        selectFirst("img")?.let { img ->
            extractImgSrc(img)?.let { return it }
        }

        parent()?.let { p ->
            p.selectFirst("noscript")?.let { ns ->
                val m = Regex("""<img[^>]+src=["']([^"']+)["']""").find(ns.html())
                if (m != null) {
                    val url = m.groupValues[1]
                    if (url.isNotBlank() && !url.startsWith("data:")) {
                        fixUrl(url)?.let { return it }
                    }
                }
            }
            p.selectFirst("img")?.let { img ->
                extractImgSrc(img)?.let { return it }
            }
        }

        var ancestor: Element? = parent()
        var depth = 0
        while (ancestor != null && depth < 5) {
            ancestor.selectFirst("noscript")?.let { ns ->
                val m = Regex("""<img[^>]+src=["']([^"']+)["']""").find(ns.html())
                if (m != null) {
                    val url = m.groupValues[1]
                    if (url.isNotBlank() && !url.startsWith("data:")) {
                        fixUrl(url)?.let { return it }
                    }
                }
            }
            ancestor.selectFirst("img")?.let { img ->
                extractImgSrc(img)?.let { return it }
            }
            ancestor.selectFirst("picture source")?.let { source ->
                extractSrcset(source)?.let { return it }
            }
            ancestor.selectFirst("[style*=background-image]")?.let { el ->
                extractBgImage(el.attr("style"))?.let { return it }
            }
            ancestor = ancestor.parent()
            depth++
        }

        return null
    }

    private fun extractImgSrc(image: Element): String? {
        val raw = image.attr("src")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("data-srcset") }
            .ifBlank { image.attr("srcset") }
        if (raw.isBlank()) return null
        if (raw.startsWith("data:")) return null
        return fixUrl(normalizeSrcset(raw))
    }

    private fun extractSrcset(source: Element): String? {
        val raw = source.attr("srcset")
            .ifBlank { source.attr("data-srcset") }
        if (raw.isBlank()) return null
        if (raw.startsWith("data:")) return null
        return fixUrl(normalizeSrcset(raw))
    }

    private fun normalizeSrcset(raw: String): String {
        val first = raw.split(",").firstOrNull()?.trim().orEmpty()
        return first.split(" ").firstOrNull()?.trim().orEmpty()
    }

    private fun extractBgImage(style: String): String? {
        if (style.isBlank()) return null
        val match = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style) ?: return null
        return fixUrl(match.groupValues[1])
    }
}

// ============ YARDIMCI FONKSİYONLAR ============

private fun JSONObject.image(): String? {
    for (key in listOf(
        "poster", "image", "cover", "thumbnail", "posterUrl", "imageUrl",
        "image_vertical_mobile", "wide", "large", "small", "medium", "backdrop"
    )) {
        val v = optString(key)
        if (v.isNotBlank() && (v.startsWith("http") || v.startsWith("//"))) {
            return if (v.startsWith("//")) "https:$v" else v
        }
    }

    val images = optJSONObject("images")
    if (images != null) {
        for (key in listOf("poster", "cover", "thumbnail", "wide", "large", "medium", "small")) {
            val v = images.optString(key)
            if (v.isNotBlank() && (v.startsWith("http") || v.startsWith("//"))) {
                return if (v.startsWith("//")) "https:$v" else v
            }
        }
        val keys = images.keys()
        while (keys.hasNext()) {
            val v = images.optString(keys.next())
            if (v.isNotBlank() && (v.startsWith("http") || v.startsWith("//"))) {
                return if (v.startsWith("//")) "https:$v" else v
            }
        }
    }

    val content = optJSONObject("content")
    if (content != null) {
        val nested = content.image()
        if (nested != null) return nested
    }

    return null
}

private fun String.slug(): String = lowercase(Locale.ROOT)
    .replace('ı', 'i')
    .replace('İ', 'i')
    .replace('ğ', 'g')
    .replace('Ğ', 'g')
    .replace('ü', 'u')
    .replace('Ü', 'u')
    .replace('ş', 's')
    .replace('Ş', 's')
    .replace('ö', 'o')
    .replace('Ö', 'o')
    .replace('ç', 'c')
    .replace('Ç', 'c')
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private fun String.titleTr(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { c ->
        if (c.isLowerCase()) c.titlecase(Locale("tr")) else c.toString()
    }
}
