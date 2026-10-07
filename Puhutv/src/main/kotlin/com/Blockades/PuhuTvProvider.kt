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

    override suspend fun search(query: String): List<SearchResponse> {
        val querySlug: String = query.slug()
        if (querySlug.isBlank()) return emptyList()
        val apiData: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$querySlug-detay").text).getJSONObject("data")
        } catch (_: Exception) {
            return emptyList()
        }
        val resultTitle: String = apiData.optString("name").ifBlank { querySlug.titleTr() }
        val seasonArr = apiData.optJSONArray("seasons")
        val contentObj: JSONObject? = apiData.optJSONObject("content")
        val resultPoster: String? = contentObj?.image() ?: apiData.image()
        return if (seasonArr != null && seasonArr.length() > 0) {
            listOf(newTvSeriesSearchResponse(resultTitle, "$mainUrl/$querySlug-detay") { posterUrl = resultPoster })
        } else {
            val assetArr = apiData.optJSONArray("assets")
            val firstAssetObj: JSONObject? = if (assetArr == null || assetArr.length() == 0) null else assetArr.optJSONObject(0)
            val videoSlug: String = if (firstAssetObj == null) querySlug else firstAssetObj.optString("slug").removeSuffix("-izle")
            listOf(newMovieSearchResponse(resultTitle, "$mainUrl/$videoSlug-izle", TvType.Movie) { posterUrl = resultPoster })
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pathSegment: String = request.data.ifBlank { "/" }
        val pageUrl = if (page > 1) "$mainUrl$pathSegment?sayfa=$page" else "$mainUrl$pathSegment"

        val pageDoc = try {
            app.get(pageUrl).document
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val posterMap: Map<String, String> = extractNextDataPosters(pageDoc)

        val anchorList = pageDoc.select(
            "a[href*=-detay], a[href*=-izle], " +
            "a[href^=list/], a[href*=/list/]"
        )

        val responseList: List<SearchResponse> = anchorList
            .mapNotNull { anchor -> anchor.toResponse(posterMap) }
            .distinctBy { it.url }
            .take(40)

        val hasMorePages = responseList.isNotEmpty() && pageDoc.selectFirst("a[href*='sayfa=${page + 1}']") != null
        return newHomePageResponse(request.name, responseList, hasNext = hasMorePages)
    }

    private fun extractNextDataPosters(doc: org.jsoup.nodes.Document): Map<String, String> {
        val resultMap = mutableMapOf<String, String>()
        try {
            val scriptTag = doc.selectFirst("script#__NEXT_DATA__") ?: return resultMap
            val jsonText = scriptTag.data().ifBlank { scriptTag.html() }
            if (jsonText.isBlank()) return resultMap

            val rootObj = JSONObject(jsonText)
            val containerArr = rootObj
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("data")
                ?.optJSONObject("data")
                ?.optJSONArray("container_items")
                ?: return resultMap

            for (i in 0 until containerArr.length()) {
                val containerObj = containerArr.optJSONObject(i) ?: continue
                val itemArr = containerObj.optJSONArray("items") ?: continue
                for (j in 0 until itemArr.length()) {
                    val itemObj = itemArr.optJSONObject(j) ?: continue
                    val metaObj = itemObj.optJSONObject("meta")
                    val itemSlug = metaObj?.optString("slug").orEmpty()
                    if (itemSlug.isBlank()) continue

                    val imgUrl = itemObj.optString("image")
                        .ifBlank { itemObj.optString("image_vertical_mobile") }
                    if (imgUrl.isNotBlank() && (imgUrl.startsWith("http") || imgUrl.startsWith("//"))) {
                        resultMap[itemSlug] = if (imgUrl.startsWith("//")) "https:$imgUrl" else imgUrl
                    }
                }
            }
        } catch (_: Exception) {
        }
        return resultMap
    }

    override suspend fun load(url: String): LoadResponse? {
        val requestedPath: String = url.substringAfter(mainUrl).substringBefore("?").trim('/')
        if (requestedPath.isBlank()) return null

        if (requestedPath.startsWith("list/")) {
            return null
        }

        val isWatchPage: Boolean = requestedPath.endsWith("-izle") || requestedPath.contains("-bolum-izle")

        val cleanSlug = requestedPath
            .removeSuffix("-detay")
            .removeSuffix("-izle")

        if (cleanSlug.isBlank()) return null

        val isEpisodeLink = isWatchPage && (
            cleanSlug.contains(Regex("""-\d+-bolum$""")) ||
            cleanSlug.endsWith("-pilot-bolum") ||
            cleanSlug.contains(Regex("""-\d+-sezon-\d+-bolum$""")) ||
            cleanSlug.contains("-bolum")
        )

        if (isEpisodeLink && isWatchPage) {
            val singleEpData: JSONObject = try {
                JSONObject(app.get("$mainUrl/api/slug/$cleanSlug-izle").text).getJSONObject("data")
            } catch (_: Exception) {
                return null
            }
            val singleTitle: String = singleEpData.optString("name").ifBlank { cleanSlug.titleTr() }
            val singleContent: JSONObject? = singleEpData.optJSONObject("content")
            val singlePoster: String? = singleContent?.image() ?: singleEpData.image()

            val singleParentPoster: String? = singleEpData.optJSONObject("title")
                ?.optString("image")
                ?.takeIf { it.isNotBlank() }
                ?: singleEpData.optJSONObject("title")
                    ?.optString("image_vertical_mobile")
                    ?.takeIf { it.isNotBlank() }

            return newMovieLoadResponse(singleTitle, url, TvType.Movie, url) {
                posterUrl = singleParentPoster ?: singlePoster
                plot = singleEpData.optString("description").takeIf { it.isNotBlank() }
            }
        }

        val detailDoc = try {
            app.get("$mainUrl/$requestedPath").document
        } catch (_: Exception) {
            return null
        }

        val nextJson: JSONObject? = try {
            detailDoc.selectFirst("script#__NEXT_DATA__")?.let { sc ->
                val jt = sc.data().ifBlank { sc.html() }
                if (jt.isNotBlank()) JSONObject(jt) else null
            }
        } catch (_: Exception) {
            null
        }

        val propsObj: JSONObject? = nextJson
            ?.optJSONObject("props")
            ?.optJSONObject("pageProps")

        val mainDataObj: JSONObject? = propsObj
            ?.optJSONObject("details")
            ?.optJSONObject("data")
            ?: findTitleData(nextJson)

        if (mainDataObj == null) {
            return null
        }

        val mainTitle: String = mainDataObj.optString("name")
            .ifBlank { mainDataObj.optString("title") }
            .ifBlank { cleanSlug.titleTr() }

        val mainMeta: JSONObject? = mainDataObj.optJSONObject("meta")

        val mainPoster: String? = mainDataObj.optString("image").takeIf { it.isNotBlank() }
            ?: mainDataObj.optString("background_image").takeIf { it.isNotBlank() }

        val mainPlot: String? = mainMeta?.optString("description")?.takeIf { it.isNotBlank() }
            ?: mainDataObj.optString("description").takeIf { it.isNotBlank() }

        val collected = mutableListOf<Episode>()

        val allEpsArr = propsObj?.optJSONArray("allEpisodes")
        if (allEpsArr != null && allEpsArr.length() > 0) {
            for (i in 0 until allEpsArr.length()) {
                val epObj = allEpsArr.optJSONObject(i) ?: continue
                val epSlug = epObj.optString("slug").ifBlank { epObj.optString("url") }
                if (epSlug.isBlank()) continue

                val epFullUrl = when {
                    epSlug.startsWith("http") -> epSlug
                    epSlug.startsWith("/") -> "$mainUrl$epSlug"
                    else -> "$mainUrl/$epSlug"
                }

                val rawTitle = epObj.optString("title").ifBlank { epObj.optString("name") }
                val epName = rawTitle.replace(Regex(""".*?\s+(\d+\.\s*Bölüm).*"""), "$1")
                    .ifBlank { rawTitle }

                val numMatch = Regex("""-(\d+)-bolum-izle""").find(epSlug)
                val epNo = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)

                collected.add(newEpisode(epFullUrl) {
                    name = epName.ifBlank { "Bölüm ${i + 1}" }
                    episode = epNo
                    season = 1
                    posterUrl = mainPoster
                })
            }
        }

        val episodeDataObj = propsObj?.optJSONObject("episodeData")?.optJSONObject("data")
        if (episodeDataObj != null) {
            val epArr = episodeDataObj.optJSONArray("episodes")
            val seasonName = episodeDataObj.optString("name")
            val seasonSlug = episodeDataObj.optString("slug")

            val seasonNo = Regex("""(\d+)\.\s*Sezon""").find(seasonName)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)-sezon""").find(seasonSlug)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            if (epArr != null && epArr.length() > 0) {
                for (i in 0 until epArr.length()) {
                    val epObj = epArr.optJSONObject(i) ?: continue
                    val epSlug = epObj.optString("slug").ifBlank { epObj.optString("url") }
                    if (epSlug.isBlank()) continue

                    val epFullUrl = when {
                        epSlug.startsWith("http") -> epSlug
                        epSlug.startsWith("/") -> "$mainUrl$epSlug"
                        else -> "$mainUrl/$epSlug"
                    }

                    val epMetaObj = epObj.optJSONObject("meta")
                    val pos = epMetaObj?.optInt("position", 0) ?: 0
                    val epNo = if (pos > 0) pos else (i + 1)

                    val epImg = epObj.optString("image").takeIf { it.isNotBlank() } ?: mainPoster

                    collected.add(newEpisode(epFullUrl) {
                        name = epObj.optString("name").ifBlank { "Bölüm $epNo" }
                        episode = epNo
                        season = seasonNo
                        posterUrl = epImg
                        description = epMetaObj?.optString("short_description")?.takeIf { it.isNotBlank() }
                    })
                }
            }
        }

        if (collected.isEmpty()) {
            val ldEps = extractEpisodesFromLdJson(detailDoc)
            collected.addAll(ldEps)
        }

        if (collected.isEmpty()) {
            val oldEps = extractEpisodesFromNextData(mainDataObj)
            collected.addAll(oldEps)
        }

        if (collected.isNotEmpty()) {
            val sortedEps = collected
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

            return newTvSeriesLoadResponse(mainTitle, url, TvType.TvSeries, sortedEps) {
                posterUrl = mainPoster
                plot = mainPlot
            }
        }

        val assetArr = mainDataObj.optJSONArray("assets")
            ?: mainMeta?.optJSONArray("assets")
        if (assetArr != null && assetArr.length() > 0) {
            val firstAssetObj = assetArr.optJSONObject(0)
            val videoSlug = firstAssetObj?.optString("slug")?.removeSuffix("-izle").orEmpty()
            if (videoSlug.isNotBlank()) {
                val watchLink = "$mainUrl/$videoSlug-izle"
                return newMovieLoadResponse(mainTitle, watchLink, TvType.Movie, watchLink) {
                    posterUrl = mainPoster
                    plot = mainPlot
                }
            }
        }

        return null
    }

    private fun extractEpisodesFromLdJson(doc: org.jsoup.nodes.Document): List<Episode> {
        val outList = mutableListOf<Episode>()
        try {
            val scriptTags = doc.select("script[type=application/ld+json]")
            for (sc in scriptTags) {
                val rawJson = sc.data().ifBlank { sc.html() }
                if (rawJson.isBlank() || !rawJson.contains("ItemList")) continue

                val jsonObj = try { JSONObject(rawJson) } catch (_: Exception) { continue }
                val typeStr = jsonObj.optString("@type")
                if (typeStr != "ItemList") continue

                val itemArr = jsonObj.optJSONArray("itemListElement") ?: continue
                for (i in 0 until itemArr.length()) {
                    val itemObj = itemArr.optJSONObject(i) ?: continue
                    val epLink = itemObj.optString("url")
                    if (epLink.isBlank()) continue

                    val numMatch = Regex("""-(\d+)-bolum-izle""").find(epLink)
                    val epNo = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)

                    outList.add(newEpisode(epLink) {
                        name = "$epNo. Bölüm"
                        episode = epNo
                        season = 1
                    })
                }
                if (outList.isNotEmpty()) break
            }
        } catch (_: Exception) {
        }
        return outList
    }

    private fun findTitleData(rootJson: JSONObject?): JSONObject? {
        if (rootJson == null) return null

        val props = rootJson.optJSONObject("props")?.optJSONObject("pageProps") ?: return null

        props.optJSONObject("details")?.optJSONObject("data")?.let { return it }

        props.optJSONObject("title")?.let { return it }
        props.optJSONObject("data")?.let { innerData ->
            innerData.optJSONObject("title")?.let { return it }
            innerData.optJSONObject("data")?.optJSONObject("title")?.let { return it }
        }

        return deepFindTitle(rootJson)
    }

    private fun deepFindTitle(obj: JSONObject): JSONObject? {
        val keyIter = obj.keys()
        while (keyIter.hasNext()) {
            val k = keyIter.next()
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> {
                    if ((v.has("assets") || v.has("seasons") || v.has("episodes")) &&
                        (v.has("name") || v.has("slug") || v.has("title"))
                    ) {
                        return v
                    }
                    val found = deepFindTitle(v)
                    if (found != null) return found
                }
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val itemObj = v.optJSONObject(i) ?: continue
                        if ((itemObj.has("assets") || itemObj.has("seasons") || itemObj.has("episodes")) &&
                            (itemObj.has("name") || itemObj.has("slug") || itemObj.has("title"))
                        ) {
                            return itemObj
                        }
                        val found = deepFindTitle(itemObj)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    private fun extractEpisodesFromNextData(dataObj: JSONObject): List<Episode> {
        val result = mutableListOf<Episode>()

        dataObj.optJSONArray("episodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val epObj = arr.optJSONObject(i) ?: continue
                parseEpisode(epObj, 1)?.let { result.add(it) }
            }
        }

        dataObj.optJSONArray("seasons")?.let { seasonsArr ->
            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(s) ?: continue
                val sn = seasonObj.optInt("number", s + 1).let { if (it <= 0) s + 1 else it }

                for (fieldName in listOf("episodes", "items", "assets")) {
                    seasonObj.optJSONArray(fieldName)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val epObj = arr.optJSONObject(i) ?: continue
                            parseEpisode(epObj, sn)?.let { result.add(it) }
                        }
                    }
                }
            }
        }

        dataObj.optJSONObject("meta")?.optJSONArray("seasons")?.let { seasonsArr ->
            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(s) ?: continue
                val sn = seasonObj.optInt("position", s + 1).let { if (it <= 0) s + 1 else it }
                for (fieldName in listOf("episodes", "items", "assets")) {
                    seasonObj.optJSONArray(fieldName)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val epObj = arr.optJSONObject(i) ?: continue
                            parseEpisode(epObj, sn)?.let { result.add(it) }
                        }
                    }
                }
            }
        }

        dataObj.optJSONArray("container_items")?.let { containersArr ->
            for (c in 0 until containersArr.length()) {
                val containerObj = containersArr.optJSONObject(c) ?: continue
                val itemsArr = containerObj.optJSONArray("items") ?: continue
                for (i in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.optJSONObject(i) ?: continue
                    parseEpisode(itemObj, 1)?.let { result.add(it) }

                    itemObj.optJSONArray("assets")?.let { assetsArr ->
                        for (a in 0 until assetsArr.length()) {
                            val assetObj = assetsArr.optJSONObject(a) ?: continue
                            parseEpisode(assetObj, 1)?.let { result.add(it) }
                        }
                    }
                }
            }
        }

        return result.distinctBy { it.data }
    }

    private fun parseEpisode(epObj: JSONObject, defaultSeason: Int): Episode? {
        val slugPath = epObj.optString("url")
            .ifBlank { epObj.optString("slug") }
            .ifBlank { epObj.optString("slugPath") }
            .ifBlank { epObj.optString("slug_path") }
            .ifBlank { epObj.optString("path") }

        if (slugPath.isBlank()) return null

        val epUrl = when {
            slugPath.startsWith("http") -> slugPath
            slugPath.startsWith("/") -> "$mainUrl$slugPath"
            else -> "$mainUrl/$slugPath"
        }

        val epName = epObj.optString("name")
            .ifBlank { epObj.optString("title") }
            .ifBlank { epObj.optString("eventLabel") }
            .ifBlank { epObj.optString("event_label") }
            .ifBlank { epObj.optString("display_name") }

        val sn = epObj.optInt("season", defaultSeason).let {
            if (it <= 0) defaultSeason else it
        }
        val en = epObj.optInt("number", 0).let {
            if (it <= 0) epObj.optInt("episode", 0) else it
        }

        val epPoster: String? = epObj.optJSONObject("content")?.image()
            ?: epObj.optString("image").takeIf { it.isNotBlank() }
            ?: epObj.optString("image_vertical_mobile").takeIf { it.isNotBlank() }

        return newEpisode(epUrl) {
            this.name = epName.ifBlank { "Bölüm" }
            this.season = sn
            this.episode = en
            this.posterUrl = epPoster
            this.description = epObj.optString("description").takeIf { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkSlug: String = data.substringAfter(mainUrl).substringBefore("?").trim('/').removeSuffix("-izle")
        val linkInfo: JSONObject = try {
            JSONObject(app.get("$mainUrl/api/slug/$linkSlug-izle").text).getJSONObject("data")
        } catch (_: Exception) {
            return false
        }
        val assetId: String = linkInfo.getString("id")
        if (assetId.isBlank()) return false
        val videoArr = try {
            JSONObject(app.get("$mainUrl/api/assets/$assetId/videos").text).getJSONObject("data").getJSONArray("videos")
        } catch (_: Exception) {
            return false
        }
        for (index in 0 until videoArr.length()) {
            val videoObj: JSONObject = videoArr.getJSONObject(index)
            val streamUrl: String = videoObj.optString("url")
            if (!streamUrl.startsWith("https://")) continue
            val qualityVal: Int = videoObj.optInt("quality", Qualities.Unknown.value)
            val formatStr: String = videoObj.optString("video_format")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (formatStr == "hls" || streamUrl.contains(".m3u8", true))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityVal
                    this.referer = "$mainUrl/"
                }
            )
        }
        return true
    }

    private fun Element.toResponse(posterMap: Map<String, String>): SearchResponse? {
        val rawHref: String = attr("href")
        val fullHref: String = fixUrl(rawHref) ?: return null

        if (!fullHref.startsWith(mainUrl)) return null

        val itemSlug: String = fullHref.substringAfterLast("/").substringBefore("?").trim('/')
        if (itemSlug.isBlank()) return null

        val isDetail = itemSlug.endsWith("-detay")
        val isIzle = itemSlug.endsWith("-izle")
        val isList = rawHref.contains("list/") || fullHref.contains("/list/")

        if (!isDetail && !isIzle && !isList) return null

        val isEpisode = isIzle && itemSlug.contains("-bolum-izle")
        if (isEpisode) return null

        val imgTag: Element? = selectFirst("img")
        val altText: String = imgTag?.attr("alt") ?: ""

        val cardTitle: String = if (altText.isNotBlank()) {
            altText
        } else {
            itemSlug.removeSuffix("-detay").removeSuffix("-izle").titleTr()
        }

        val slugKey = itemSlug.removeSuffix("-detay").removeSuffix("-izle") + "-detay"
        val fromJson = posterMap[slugKey]
        val fromHtml = if (fromJson == null) findPoster() else null
        val cardPoster: String? = fromJson ?: fromHtml

        return when {
            isDetail -> newTvSeriesSearchResponse(cardTitle, fullHref) { posterUrl = cardPoster }
            isIzle -> newMovieSearchResponse(cardTitle, fullHref, TvType.Movie) { posterUrl = cardPoster }
            isList -> newTvSeriesSearchResponse(cardTitle, fullHref) { posterUrl = cardPoster }
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

    val imagesObj = optJSONObject("images")
    if (imagesObj != null) {
        for (key in listOf("poster", "cover", "thumbnail", "wide", "large", "medium", "small")) {
            val v = imagesObj.optString(key)
            if (v.isNotBlank() && (v.startsWith("http") || v.startsWith("//"))) {
                return if (v.startsWith("//")) "https:$v" else v
            }
        }
        val keys = imagesObj.keys()
        while (keys.hasNext()) {
            val v = imagesObj.optString(keys.next())
            if (v.isNotBlank() && (v.startsWith("http") || v.startsWith("//"))) {
                return if (v.startsWith("//")) "https:$v" else v
            }
        }
    }

    val contentObj = optJSONObject("content")
    if (contentObj != null) {
        val nested = contentObj.image()
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
