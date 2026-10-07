// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır. (ATV için uyarlanmıştır)

package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class Atv : MainAPI() {
    override var mainUrl              = "https://www.atv.com.tr"
    override var name                 = "ATV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    // 系统页面 - 这些不应作为剧集/节目显示
    private val systemPages = setOf(
        "diziler", "programlar", "yayin-akisi", "canli-yayin",
        "haberler", "haber", "eski-diziler", "a2tv", "arama",
        "kunye", "iletisim", "bize-ulasin", "gizlilik-bildirimi",
        "veri-politikasi", "uydu-frekanslari", "site-haritasi",
        "rss-bilgi", "adblock", "retro-d", "filmler",
        "milyoner", "webtv", "diger", "kadro", "fragmanlar",
        "ozetler", "ozel-klipler", "foto-galeri", "oyuncular",
        "hikaye-ve-kunye", "d-shorts", "bolumler"
    )

    // 预告片/预览关键词
    private val trailerKeywords = listOf(
        "fragman", "tanitim", "tanıtım", "onizleme", "önizleme",
        "teaser", "trailer", "ozet", "özet", "promo", "kamera-arkasi"
    )

    // ★ 精确选择器：定位剧集卡片容器，而非全页链接
    // 根据 ATV 实际页面结构调整，以下为基于页面结构的推断
    private val dizilerCardSelector = "div.diziler-list div.card, div.series-list div.card, ul.dizi-list li"
    private val programlarCardSelector = "div.programlar-list div.card, div.program-list div.card, ul.program-list li"

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"    to "Diziler",
        "${mainUrl}/programlar" to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        try {
            val listDoc = app.get(request.data).document

            // ★ 使用精确选择器定位卡片容器
            val cardSelector = if (request.name == "Diziler") {
                dizilerCardSelector
            } else {
                programlarCardSelector
            }

            val cards = listDoc.select(cardSelector)

            if (cards.isNotEmpty()) {
                Log.d("ATV", "精确选择器找到 ${cards.size} 个卡片")
                cards.forEach { card ->
                    card.toCardResult()?.let { results.add(it) }
                }
            } else {
                // 回退方案：如果精确选择器找不到，尝试更通用的容器选择
                Log.w("ATV", "精确选择器未匹配，尝试回退方案")
                val fallbackCards = if (request.name == "Diziler") {
                    listDoc.select("div[class*=dizi] a[href], div[class*=series] a[href]")
                } else {
                    listDoc.select("div[class*=program] a[href]")
                }
                fallbackCards.forEach { element ->
                    element.toListPageResult()?.let { results.add(it) }
                }
            }

            Log.d("ATV", "列表页面获取 ${results.size} 个结果")
        } catch (e: Exception) {
            Log.e("ATV", "获取主页失败: ${e.message}")
        }

        // 从菜单补充（菜单链接相对干净）
        try {
            val mainDoc = app.get(mainUrl).document
            val menuSelector = if (request.name == "Diziler") {
                "div.series-drop .sub-menu-list li a[href]"
            } else {
                "div.program-drop-menu .sub-menu-list li a[href]"
            }
            mainDoc.select(menuSelector).forEach { element ->
                element.toMenuItemResult()?.let { results.add(it) }
            }
        } catch (e: Exception) {
            Log.e("ATV", "菜单抓取失败: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        Log.d("ATV", "getMainPage: ${request.name} -> ${uniqueResults.size} 个唯一结果")

        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
    }

    /**
     * 检查文本或 URL 是否包含预告片关键词
     */
    private fun isTrailer(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        return trailerKeywords.any { lower.contains(it) }
    }

    /**
     * 从卡片容器中提取剧集信息（精确方式）
     */
    private fun Element.toCardResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val hrefRaw = link.attr("href")
        if (hrefRaw.isBlank()) return null

        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null

        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        // 标题：优先使用卡片内的标题元素
        val title = this.selectFirst("h3, h4, .title, .card-title, figcaption, span.name")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        // 封面图
        val img = this.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { img.attr("src") }
        )

        Log.d("ATV", "  ✓ [$path] → $title")

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toMenuItemResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null

        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        val title = this.text().trim().takeIf { it.isNotEmpty() } ?: return null

        if (isTrailer(title)) return null

        val poster = this.parent()?.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        }

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun Element.toListPageResult(): SearchResponse? {
        val hrefRaw = this.attr("href")
        if (hrefRaw.isBlank()) return null

        if (isTrailer(hrefRaw)) return null

        val fullUrl = fixUrlNull(hrefRaw) ?: return null
        if (!fullUrl.contains("atv.com.tr")) return null

        val path = normalizePath(fullUrl) ?: return null

        if (path.contains("/")) return null
        if (systemPages.contains(path)) return null

        val img = this.selectFirst("img") ?: return null

        val title = this.selectFirst("figcaption p, figcaption .title, h2, h3, .title, .caption")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: img.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        val poster = fixUrlNull(
            img.attr("data-src").ifEmpty {
                img.attr("src").ifEmpty { img.attr("data-lazy-src") }
            }
        )

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun normalizePath(url: String): String? {
        var path = url
        path = path.replace("https://www.atv.com.tr", "")
        path = path.replace("https://atv.com.tr", "")
        path = path.replace("http://www.atv.com.tr", "")
        path = path.replace("http://atv.com.tr", "")
        path = path.substringBefore("?").substringBefore("#")
        path = path.trim('/')

        if (path.isEmpty()) return null
        if (path.contains(".")) return null
        if (path.length < 2) return null

        return path
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()
        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            return allContentCache
        }

        val allContent = mutableListOf<SearchResponse>()
        try {
            val pagesToScan = listOf("${mainUrl}/diziler" to "Diziler", "${mainUrl}/programlar" to "Programlar")
            for ((pageUrl, categoryName) in pagesToScan) {
                try {
                    val document = app.get(pageUrl).document

                    // ★ 使用精确选择器
                    val cardSelector = if (categoryName == "Diziler") {
                        dizilerCardSelector
                    } else {
                        programlarCardSelector
                    }

                    var cards = document.select(cardSelector)
                    if (cards.isEmpty()) {
                        // 回退
                        cards = if (categoryName == "Diziler") {
                            document.select("div[class*=dizi] a[href], div[class*=series] a[href]")
                        } else {
                            document.select("div[class*=program] a[href]")
                        }
                    }

                    cards.forEach { element ->
                        if (element.tagName() == "a") {
                            element.toListPageResult()?.let { allContent.add(it) }
                        } else {
                            element.toCardResult()?.let { allContent.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ATV", "页面抓取失败 ($pageUrl): ${e.message}")
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            Log.d("ATV", "getAllContent: 缓存 ${uniqueContent.size} 个条目")
            return uniqueContent
        } catch (e: Exception) {
            Log.e("ATV", "收集内容时出错: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val allContent = getAllContent()
        val searchQuery = query.lowercase(Locale.getDefault())
        return allContent.filter {
            it.name.lowercase(Locale.getDefault()).contains(searchQuery)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        if (isTrailer(url)) {
            Log.d("ATV", "预告片页面跳过: $url")
            return null
        }

        val document = app.get(url).document

        // 单集播放页面
        if (url.contains("/izle")) {
            if (isTrailer(url)) {
                Log.d("ATV", "预告片播放页面跳过: $url")
                return null
            }

            val title = document.selectFirst("h1.video-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null

            if (isTrailer(title)) {
                Log.d("ATV", "预告片标题跳过: $title")
                return null
            }

            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val episode = newEpisode(url) {
                this.name = title
                this.episode = 1
                this.posterUrl = poster
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // 剧集详情页面
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // ★ 精确选择器：定位剧集列表容器
            val episodeContainer = document.selectFirst(
                "div.bolumler-list, div.episodes-list, div[class*=bolum], div[class*=episode]"
            )

            val episodeLinks = (episodeContainer ?: document).select("a[href*='/izle']")
                .filter { element ->
                    val href = element.attr("href")

                    if (isTrailer(href)) {
                        Log.d("ATV", "预告片链接跳过: $href")
                        return@filter false
                    }

                    val text = element.text()
                    if (isTrailer(text)) {
                        Log.d("ATV", "预告片文本跳过: $text")
                        return@filter false
                    }

                    href.contains("-bolum") && href.endsWith("/izle")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d("ATV", "找到 ${episodeLinks.size} 个剧集链接")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                    if (isTrailer(href)) return@forEachIndexed

                    val epName = element.selectFirst(".style-01, .style-02, h3, .title, .date")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element.text().trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    if (isTrailer(epName)) return@forEachIndexed

                    val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(href) {
                        this.name = epName
                        this.episode = epNum
                    }?.let { allEpisodes.add(it) }
                }

                return allEpisodes.sortedBy { it.episode }
            }

            // AJAX 备选方案
            val slug = baseUrl.substringAfter(mainUrl).trim('/').substringBefore("/")
            val ajaxUrls = listOf(
                "$mainUrl/ajax/series/$slug/episodes",
                "$mainUrl/ajax/$slug/episodes"
            )

            for (ajaxUrl in ajaxUrls) {
                try {
                    val response = app.get(
                        ajaxUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to baseUrl
                        )
                    )
                    val doc = response.document
                    val links = doc.select("a[href*='/izle']")
                        .filter { element ->
                            val href = element.attr("href")
                            if (isTrailer(href)) return@filter false
                            val text = element.text()
                            if (isTrailer(text)) return@filter false
                            href.contains("-bolum")
                        }

                    if (links.isNotEmpty()) {
                        Log.d("ATV", "AJAX 找到 ${links.size} 个剧集: $ajaxUrl")
                        links.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                            if (isTrailer(href)) return@forEachIndexed

                            val epName = element.selectFirst(".style-01, .style-02, h3, .title")
                                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                                ?: "Bölüm ${index + 1}"

                            if (isTrailer(epName)) return@forEachIndexed

                            val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                                ?: (index + 1)

                            newEpisode(href) {
                                this.name = epName
                                this.episode = epNum
                            }?.let { allEpisodes.add(it) }
                        }
                        if (allEpisodes.isNotEmpty()) return allEpisodes.sortedBy { it.episode }
                    }
                } catch (e: Exception) {
                    Log.d("ATV", "AJAX 尝试失败 ($ajaxUrl): ${e.message}")
                }
            }

            return emptyList()
        } catch (e: Exception) {
            Log.e("ATV", "剧集抓取失败: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ATV", "视频数据: $data")

        if (isTrailer(data)) {
            Log.d("ATV", "预告片链接跳过: $data")
            return false
        }

        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
            var found = false

            val pageTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            val isTrailerPage = isTrailer(pageTitle) || isTrailer(data)
            if (isTrailerPage) {
                Log.d("ATV", "检测到预告片页面，不提取视频: $pageTitle")
                return false
            }

            // 优先级 1: JSON-LD VideoObject
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject")) continue

                val lowerContent = content.lowercase(Locale.getDefault())
                if (trailerKeywords.any { lowerContent.contains(it) }) {
                    Log.d("ATV", "预告片 JSON-LD 跳过")
                    continue
                }

                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        val videoName = json.optString("name", "").lowercase(Locale.getDefault())

                        if (trailerKeywords.any { videoName.contains(it) }) {
                            Log.d("ATV", "预告片视频跳过: $videoName")
                            continue
                        }

                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            Log.d("ATV", "JSON-LD contentUrl 找到: $contentUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ATV", "JSON-LD 解析失败: ${e.message}")
                }
            }

            // 优先级 2: 仅在播放器相关 script 中搜索
            if (!found) {
                val playerScripts = document.select("script").filter { script ->
                    val data = script.data().lowercase(Locale.getDefault())
                    (data.contains("player") ||
                     data.contains("video") ||
                     data.contains("hls") ||
                     data.contains("m3u8") ||
                     data.contains("contenturl")) &&
                    !trailerKeywords.any { data.contains(it) }
                }

                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\""),
                    Regex("\"file\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"source\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )

                for (script in playerScripts) {
                    val content = script.data()

                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")

                            if (isTrailer(videoUrl)) {
                                Log.d("ATV", "预告片 URL 跳过: $videoUrl")
                                return@let
                            }

                            Log.d("ATV", "正则找到: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    name = this.name,
                                    source = this.name,
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                        if (found) break
                    }
                    if (found) break
                }
            }

            // 优先级 3: iframe 嵌入
            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))

                    if (isTrailer(embedUrl)) {
                        Log.d("ATV", "预告片 iframe 跳过: $embedUrl")
                    } else {
                        Log.d("ATV", "找到 iframe: $embedUrl")
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("ATV", "LoadLinks 错误: ${e.message}")
            return false
        }
    }
}
