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

        val isDetail: Boolean = requestedPath.endsWith("-detay")
        val isWatch: Boolean = requestedPath.endsWith("-izle") || requestedPath.contains("-bolum-izle")

        val cleanSlug = requestedPath
            .removeSuffix("-detay")
            .removeSuffix("-izle")

        if (cleanSlug.isBlank()) return null

        // ⬇️ Bölüm linki mi? ("-1-bolum-izle", "-pilot-bolum-izle", "-1-sezon-1-bolum-izle")
        val isEpisodeLink = isWatch && (
            cleanSlug.contains(Regex("""-\d+-bolum$""")) ||
            cleanSlug.endsWith("-pilot-bolum") ||
            cleanSlug.contains(Regex("""-\d+-sezon-\d+-bolum$""")) ||
            cleanSlug.contains("-bolum")
        )

        // ⬇️ Bölüm linki ise → doğrudan Movie LoadResponse dön
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

        // ⬇️ Detay sayfası
        val suffix: String = if (isDetail) "-detay" else "-izle"
        val slug: String = cleanSlug

        val data: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$slug$suffix").text).getJSONObject("data")
        } catch (e: Exception) {
            println("PuhuTV load: Detay API hatası = ${e.message}")
            return null
        }

        val title: String = data.optString("name").ifBlank { slug.titleTr() }
        val content: JSONObject? = data.optJSONObject("content")
        val poster: String? = content?.image() ?: data.image()
        val seasons = data.optJSONArray("seasons")
        val assets = data.optJSONArray("assets")

        // ⬇️ DİZİ Mİ?
        if (isDetail && seasons != null && seasons.length() > 0) {
            println("PuhuTV load: $title → ${seasons.length()} sezon bulundu")
            val episodes = mutableListOf<Episode>()

            for (seasonIndex in 0 until seasons.length()) {
                val seasonJson: JSONObject = seasons.optJSONObject(seasonIndex) ?: continue

                // ⬇️ Sezon ID'sini birden fazla alan adından dene
                val seasonId: String = seasonJson.optString("id")
                    .ifBlank { seasonJson.optString("season_id") }
                    .ifBlank { seasonJson.optString("_id") }
                    .ifBlank { seasonJson.optString("uuid") }

                println("PuhuTV DEBUG: Sezon[$seasonIndex] objesi = $seasonJson")
                println("PuhuTV DEBUG: Sezon[$seasonIndex] id = '$seasonId'")

                if (seasonId.isBlank()) {
                    println("PuhuTV DEBUG: Sezon[$seasonIndex] id BOŞ, atlanıyor")
                    continue
                }

                val seasonNumber: Int = seasonJson.optInt("number", seasonIndex + 1)
                val perPage = 40
                var page = 1
                var hasMore = true

                while (hasMore && page <= 20) {
                    val apiUrl = "https://galadriel.puhutv.com/seasons/$seasonId?page=$page&per=$perPage"
                    println("PuhuTV DEBUG: API isteği → $apiUrl")

                    val seasonData: JSONObject = try {
                        val response = app.get(apiUrl).text
                        println("PuhuTV DEBUG: Sezon API cevabı (ilk 300) = ${response.take(300)}")
                        JSONObject(response)
                    } catch (e: Exception) {
                        println("PuhuTV DEBUG: Sezon API HATASI = ${e.message}")
                        break
                    }

                    val seasonEpisodes = seasonData.optJSONArray("episodes")
                    println("PuhuTV DEBUG: episodes sayısı = ${seasonEpisodes?.length() ?: "null"}")

                    if (seasonEpisodes == null || seasonEpisodes.length() == 0) {
                        if (page == 1) {
                            println("PuhuTV DEBUG: Anahtarlar = ${seasonData.keys().asSequence().toList()}")
                        }
                        break
                    }

                    for (episodeIndex in 0 until seasonEpisodes.length()) {
                        val episodeJson: JSONObject = seasonEpisodes.optJSONObject(episodeIndex) ?: continue
                        val episodePath: String = episodeJson.optString("slugPath")
                            .ifBlank { episodeJson.optString("slug_path") }
                            .ifBlank { episodeJson.optString("slug") }
                        if (episodePath.isBlank()) continue

                        val episodePoster: String? =
                            episodeJson.optJSONObject("content")?.image()
                                ?: episodeJson.image()
                                ?: poster

                        episodes.add(newEpisode("$mainUrl/" + episodePath.trimStart('/')) {
                            name = episodeJson.optString("name")
                                .ifBlank { episodeJson.optString("eventLabel") }
                                .ifBlank { episodeJson.optString("event_label") }
                                .ifBlank { "Bölüm ${episodeIndex + 1}" }
                            season = seasonNumber
                            episode = episodeJson.optInt("number", episodeIndex + 1)
                            posterUrl = episodePoster
                            description = episodeJson.optString("description").takeIf { it.isNotBlank() }
                        })
                    }

                    val totalPages = seasonData.optInt("total_pages", 1)
                    hasMore = page < totalPages
                    page++
                }
            }

            if (episodes.isEmpty()) {
                println("PuhuTV load: $title için bölüm bulunamadı → film fallback denenecek")
                // ⬇️ Fallback: assets varsa film olarak dön
                if (assets != null && assets.length() > 0) {
                    val firstAsset: JSONObject = assets.optJSONObject(0) ?: return null
                    val videoSlug: String = firstAsset.optString("slug").removeSuffix("-izle")
                    if (videoSlug.isNotBlank()) {
                        return newMovieLoadResponse(title, "$mainUrl/$videoSlug-izle", TvType.Movie, "$mainUrl/$videoSlug-izle") {
                            posterUrl = poster
                            plot = data.optString("description").takeIf { it.isNotBlank() }
                        }
                    }
                }
                return null
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = data.optString("description").takeIf { it.isNotBlank() }
            }
        }

        // ⬇️ FİLM Mİ?
        if (assets != null && assets.length() > 0) {
            val firstAsset: JSONObject = assets.optJSONObject(0) ?: return null
            val videoSlug: String = firstAsset.optString("slug").removeSuffix("-izle")
            if (videoSlug.isBlank()) {
                println("PuhuTV load: $title için asset slug boş")
                return null
            }
            val watchUrl: String = "$mainUrl/$videoSlug-izle"
            return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                posterUrl = poster
                plot = data.optString("description").takeIf { it.isNotBlank() }
            }
        }

        println("PuhuTV load: $title için ne seasons ne assets bulundu")
        return null
    }

    // ============ LİNKLERİ YÜKLE (DEĞİŞTİRİLMEDİ) ============
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

        // Bölüm linklerini ana sayfa kartlarından çıkar
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
