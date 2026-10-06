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

    // ============ YARDIMCI: Göreceli URL'yi mutlak yap ============
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

        val elements = document.select(
            "a[href*=-detay], a[href*=-izle], " +
            "a[href^=list/], a[href*=/list/]"
        )

        println("PuhuTV getMainPage bulunan element sayısı: ${elements.size}")

        val results: List<SearchResponse> = elements
            .mapNotNull { element -> element.toResponse() }
            .distinctBy { it.url }
            .take(40)

        println("PuhuTV getMainPage sonuç sayısı: ${results.size}")

        val hasNext = results.isNotEmpty() && document.selectFirst("a[href*='sayfa=${page + 1}']") != null
        return newHomePageResponse(request.name, results, hasNext = hasNext)
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
                // ⬇️ DEĞİŞKEN ADI: season → seasonJson (KOTLIN ÇAKIŞMASINI ÖNLER)
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
                        // ⬇️ Artık dıştaki `season` yok, çakışma yok
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

    // ============ HTML ELEMENT → SearchResponse ============
    private fun Element.toResponse(): SearchResponse? {
        val rawHref: String = attr("href")
        val href: String = fixUrl(rawHref) ?: return null

        if (!href.startsWith(mainUrl)) return null

        val slug: String = href.substringAfterLast("/").substringBefore("?").trim('/')
        if (slug.isBlank()) return null

        val isDetail: Boolean = slug.endsWith("-detay")
        val isIzle: Boolean = slug.endsWith("-izle")
        val isList: Boolean = rawHref.contains("list/") || href.contains("/list/")

        if (!isDetail && !isIzle && !isList) return null

        val image: Element? = selectFirst("img")
        val alt: String = image?.attr("alt") ?: ""

        val title: String = if (alt.isNotBlank()) {
            alt
        } else {
            slug.removeSuffix("-detay").removeSuffix("-izle").titleTr()
        }

        val poster: String? = if (image == null) null else {
            val src: String = image.attr("src").ifBlank { image.attr("data-src") }
            fixUrl(src)
        }

        return when {
            isDetail -> newTvSeriesSearchResponse(title, href) { posterUrl = poster }
            isIzle -> newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            isList -> newTvSeriesSearchResponse(title, href) { posterUrl = poster }
            else -> null
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
