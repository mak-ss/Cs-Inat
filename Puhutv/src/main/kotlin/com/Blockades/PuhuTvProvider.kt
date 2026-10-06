package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
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

    // ============ YARDIMCI ============
    private fun fixUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // data: URI'lerini reddet
        if (raw.startsWith("data:")) return null
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

    // ============ ANA SAYFA (Next.js JSON parse) ============
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

        // Önce __NEXT_DATA__ JSON'unu dene
        val nextData = document.selectFirst("script#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            val results = parseNextData(nextData, path)
            if (results.isNotEmpty()) {
                println("PuhuTV getMainPage (JSON) sonuç sayısı: ${results.size}")
                return newHomePageResponse(request.name, results, hasNext = false)
            }
        }

        // Fallback: HTML'den noscript içindeki gerçek img'leri al
        println("PuhuTV getMainPage: __NEXT_DATA__ boş, HTML fallback")
        val results = document.select("a[href*=-detay], a[href*=-izle]")
            .mapNotNull { it.toResponse() }
            .distinctBy { it.url }
            .take(40)

        println("PuhuTV getMainPage (HTML) sonuç sayısı: ${results.size}")
        return newHomePageResponse(request.name, results, hasNext = false)
    }

    // __NEXT_DATA__ JSON'undan içerikleri çek
    private fun parseNextData(jsonText: String, path: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val root = JSONObject(jsonText)
            val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
            val data = pageProps.optJSONObject("data")?.optJSONObject("data") ?: return emptyList()
            val containers = data.optJSONArray("container_items") ?: return emptyList()

            for (ci in 0 until containers.length()) {
                val container = containers.optJSONObject(ci) ?: continue
                val containerType = container.optString("type")
                // Sadece poster/clips/spotlight içeriklerini al
                if (containerType != "poster" && containerType != "clips" && containerType != "spotlight") continue

                val items = container.optJSONArray("items") ?: continue
                for (ii in 0 until items.length()) {
                    val item = items.optJSONObject(ii) ?: continue
                    val parsed = item.toSearchResponse() ?: continue
                    if (results.none { it.url == parsed.url }) {
                        results.add(parsed)
                    }
                }
                if (results.size >= 60) break
            }
        } catch (e: Exception) {
            println("PuhuTV parseNextData HATA: ${e.message}")
        }
        return results.take(40)
    }

    // JSON item → SearchResponse
    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val type = optString("type")
        val name = optString("name").ifBlank { return null }

        // Poster URL: önce image_vertical_mobile (7x10 dikey), sonra image
        val poster: String? = sequenceOf(
            optString("image_vertical_mobile"),
            optString("image"),
            optString("image_tvs")
        ).firstOrNull { it.startsWith("http") }

        val meta = optJSONObject("meta")
        val slug = meta?.optString("slug")?.ifBlank { null }
            ?: optString("to_watch_asset_slug").ifBlank { null }
            ?: return null

        val webUrl = meta?.optString("web_url")?.ifBlank { null }
            ?: "$mainUrl/$slug"

        return when (type) {
            "title_serie", "asset_episode", "asset_fragment" ->
                newTvSeriesSearchResponse(name, webUrl) { posterUrl = poster }
            "title_movie" ->
                newMovieSearchResponse(name, webUrl, TvType.Movie) { posterUrl = poster }
            else -> null
        }
    }

    // ============ İÇERİK YÜKLE ============
    override suspend fun load(url: String): LoadResponse? {
        val requestedPath: String = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        val isDetail: Boolean = requestedPath.endsWith("-detay")
        val slug: String = requestedPath.removeSuffix("-detay").removeSuffix("-izle")
        if (slug.isBlank()) return null
        val suffix: String = if (isDetail) "-detay" else "-izle"
        val data: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$slug$suffix").text).getJSONObject("data")
        } catch (_: Exception) {
            return null
        }
        val title: String = data.optString("name").ifBlank { slug.titleTr() }
        val content: JSONObject? = data.optJSONObject("content")
        val poster: String? = content?.image() ?: data.image()
        val seasons = data.optJSONArray("seasons")

        if (isDetail && seasons != null && seasons.length() > 0) {
            val episodes = mutableListOf<Episode>()
            for (seasonIndex in 0 until seasons.length()) {
                val seasonJson: JSONObject = seasons.optJSONObject(seasonIndex) ?: continue
                val seasonId: String = seasonJson.optString("id")
                if (seasonId.isBlank()) continue
                val seasonData: JSONObject = try {
                    JSONObject(app.get("https://galadriel.puhutv.com/seasons/$seasonId?page=1&per=40").text)
                } catch (_: Exception) {
                    continue
                }
                val seasonNumber: Int = seasonJson.optInt("number", seasonIndex + 1)
                val seasonEpisodes = seasonData.optJSONArray("episodes") ?: continue
                for (episodeIndex in 0 until seasonEpisodes.length()) {
                    val episodeJson: JSONObject = seasonEpisodes.optJSONObject(episodeIndex) ?: continue
                    val episodePath: String = episodeJson.optString("slugPath")
                    if (episodePath.isBlank()) continue
                    episodes.add(newEpisode("$mainUrl/" + episodePath.trimStart('/')) {
                        name = episodeJson.optString("name").ifBlank { episodeJson.optString("eventLabel") }
                        season = seasonNumber
                        episode = episodeJson.optInt("number", episodeIndex + 1)
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = data.optString("description").takeIf { it.isNotBlank() }
            }
        }

        val assets = data.optJSONArray("assets")
        val firstAsset: JSONObject? = if (assets == null || assets.length() == 0) null else assets.optJSONObject(0)
        val videoSlug: String = if (firstAsset == null) slug else firstAsset.optString("slug").removeSuffix("-izle")
        val watchUrl: String = "$mainUrl/$videoSlug-izle"
        return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
            posterUrl = poster
            plot = data.optString("description").takeIf { it.isNotBlank() }
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

    // ============ HTML FALLBACK (noscript içindeki gerçek img) ============
    private fun Element.toResponse(): SearchResponse? {
        val rawHref: String = attr("href")
        val href: String = fixUrl(rawHref) ?: return null
        if (!href.startsWith(mainUrl)) return null

        val slug: String = href.substringAfterLast("/").substringBefore("?").trim('/')
        if (slug.isBlank()) return null

        val isDetail: Boolean = slug.endsWith("-detay")
        val isIzle: Boolean = slug.endsWith("-izle")
        if (!isDetail && !isIzle) return null

        // noscript içindeki <img> gerçek URL'yi içerir
        val noscriptImg: Element? = selectFirst("noscript img")
        val normalImg: Element? = selectFirst("img")

        val poster: String? = sequenceOf(
            noscriptImg?.attr("src"),
            normalImg?.attr("data-src"),
            normalImg?.attr("src")
        ).mapNotNull { fixUrl(it) }
            .firstOrNull { it.startsWith("https://") && !it.contains("data:") }

        val alt: String = normalImg?.attr("alt") ?: ""
        val title: String = if (alt.isNotBlank()) alt
        else slug.removeSuffix("-detay").removeSuffix("-izle").titleTr()

        return if (isDetail) {
            newTvSeriesSearchResponse(title, href) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }
}

// ============ YARDIMCI FONKSİYONLAR ============

private fun JSONObject.image(): String? {
    val images: JSONObject = optJSONObject("images") ?: return null
    val keys = images.keys()
    while (keys.hasNext()) {
        val value: String = images.optString(keys.next())
        if (value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
    }
    return null
}

private fun String.slug(): String = lowercase(Locale.ROOT)
    .replace('ı', 'i').replace('İ', 'i')
    .replace('ğ', 'g').replace('Ğ', 'g')
    .replace('ü', 'u').replace('Ü', 'u')
    .replace('ş', 's').replace('Ş', 's')
    .replace('ö', 'o').replace('Ö', 'o')
    .replace('ç', 'c').replace('Ç', 'c')
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private fun String.titleTr(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { c ->
        if (c.isLowerCase()) c.titlecase(Locale("tr")) else c.toString()
    }
}
