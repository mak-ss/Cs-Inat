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

    private val trailerKeywords = listOf(
        "fragman", "tanitim", "tanıtım", "onizleme", "önizleme",
        "teaser", "trailer", "ozet", "özet", "promo", "kamera-arkasi"
    )

    private val cardSelectors = listOf(
        "div.diziler-list div.card",
        "div.series-list div.card",
        "div.programlar-list div.card",
        "div.program-list div.card",
        "ul.dizi-list li",
        "ul.program-list li",
        "div[class*=dizi] a[href]",
        "div[class*=series] a[href]",
        "div[class*=program] a[href]",
        "div.card a[href]",
        "li.series-item a[href]",
        "li.program-item a[href]",
        "article.card a[href]",
        "figure a[href]"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"      to "Diziler",
        "${mainUrl}/eski-diziler" to "Eski Diziler",
        "${mainUrl}/programlar"   to "Programlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenPaths = mutableSetOf<String>()

        try {
            val listDoc = app.get(request.data).document

            var cards: List<Element> = emptyList()
            for (selector in cardSelectors) {
                val found = listDoc.select(selector)
                if (found.size >= 3) {
                    cards = found
                    Log.d("ATV", "Kart seçici tuttu: $selector (${found.size} kart)")
                    break
                }
            }

            if (cards.isEmpty()) {
                Log.w("ATV", "Hiçbir kart seçicisi tutmadı, tüm a[href] deneniyor")
                cards = listDoc.select("a[href]")
            }

            cards.forEach { element ->
                val result = if (element.tagName() == "a") {
                    element.toListPageResult()
                } else {
                    element.toCardResult()
                }
                if (result != null) {
                    val key = normalizeForCompare(result.url)
                    if (key != null && seenPaths.add(key)) {
                        results.add(result)
                    }
                }
            }

            Log.d("ATV", "Liste sayfasından ${results.size} öğe alındı (${request.name})")
        } catch (e: Exception) {
            Log.e("ATV", "Liste sayfası hatası: ${e.message}")
        }

        try {
            val mainDoc = app.get(mainUrl).document
            val menuSelector = when (request.name) {
                "Diziler"      -> "div.series-drop .sub-menu-list li a[href]"
                "Programlar"   -> "div.program-drop-menu .sub-menu-list li a[href]"
                "Eski Diziler" -> "div.series-drop .sub-menu-list li a[href]"
                else -> ""
            }
            if (menuSelector.isNotEmpty()) {
                mainDoc.select(menuSelector).forEach { element ->
                    val result = element.toMenuItemResult()
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            results.add(result)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ATV", "Menü çekme hatası: ${e.message}")
        }

        Log.d("ATV", "getMainPage: ${request.name} -> ${results.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, results))
        )
    }

    private fun normalizeForCompare(url: String): String? {
        return try {
            var p = url.lowercase(Locale.getDefault())
            p = p.replace("https://www.atv.com.tr/", "")
            p = p.replace("https://atv.com.tr/", "")
            p = p.replace("http://www.atv.com.tr/", "")
            p = p.replace("http://atv.com.tr/", "")
            p = p.removePrefix("/")
            p = p.substringBefore("?").substringBefore("#")
            p = p.trimEnd('/')
            if (p.isEmpty()) null else p
        } catch (e: Exception) {
            null
        }
    }

    private fun isTrailer(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault())
        return trailerKeywords.any { lower.contains(it) }
    }

    // ★ TÜM POSTER KAYNAKLARINI DENE
    // img[src], img[data-src], img[data-original], img[data-lazy-src], img[data-srcset], img[srcset],
    // picture > source[srcset], div[style*=background-image]
    private fun Element.extractPoster(): String? {
        // 1. İlk img'i al
        val img = this.selectFirst("img")
        if (img != null) {
            // Denenecek attribute sırası (öncelik sırasıyla)
            val attrs = listOf(
                "data-src",
                "data-original",
                "data-lazy-src",
                "data-lazy",
                "src",
                "data-srcset",
                "srcset"
            )
            for (attr in attrs) {
                val value = img.attr(attr).trim()
                if (value.isNotEmpty()) {
                    // srcset formatı: "url1 1x, url2 2x" → ilk URL'yi al
                    val url = if (attr.contains("srcset")) {
                        value.substringBefore(",").trim().substringBefore(" ")
                    } else {
                        value
                    }
                    val fixed = fixUrlNull(url)
                    if (!fixed.isNullOrBlank()) return fixed
                }
            }
        }

        // 2. picture > source[srcset]
        val source = this.selectFirst("picture source[srcset], source[data-srcset]")
        if (source != null) {
            val srcset = source.attr("srcset").ifEmpty { source.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                val url = srcset.substringBefore(",").trim().substringBefore(" ")
                fixUrlNull(url)?.let { if (it.isNotBlank()) return it }
            }
        }

        // 3. div[style*=background-image:url(...)]
        val styledDiv = this.selectFirst("[style*=background-image]")
        if (styledDiv != null) {
            val style = styledDiv.attr("style")
            val match = Regex("background-image\\s*:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)", RegexOption.IGNORE_CASE)
                .find(style)
            if (match != null) {
                fixUrlNull(match.groupValues[1])?.let { if (it.isNotBlank()) return it }
            }
        }

        // 4. Link'in kendisinde data-poster, data-image vs. var mı?
        val dataAttrs = listOf("data-poster", "data-image", "data-thumb", "data-cover")
        for (attr in dataAttrs) {
            val value = this.attr(attr).trim()
            if (value.isNotEmpty()) {
                fixUrlNull(value)?.let { if (it.isNotBlank()) return it }
            }
        }

        return null
    }

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

        val title = this.selectFirst("h3, h4, .title, .card-title, figcaption, span.name")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        // ★ extractPoster ile tüm kaynakları dene
        val poster = this.extractPoster()

        Log.d("ATV", "  ✓ [$path] → $title | poster: ${poster ?: "YOK"}")

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

        // ★ Menüde poster genelde parent'ta olur
        val poster = this.parent()?.extractPoster()

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

        // Başlık: önce link içinden dene
        val title = this.selectFirst("figcaption p, figcaption .title, h2, h3, .title, .caption")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        if (isTrailer(title)) return null

        // ★ extractPoster ile tüm kaynakları dene (bu element ve parent'ı)
        val poster = this.extractPoster() ?: this.parent()?.extractPoster()

        Log.d("ATV", "  ✓ [$path] → $title | poster: ${poster ?: "YOK"}")

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
        val seenPaths = mutableSetOf<String>()

        val pagesToScan = listOf(
            "${mainUrl}/diziler",
            "${mainUrl}/eski-diziler",
            "${mainUrl}/programlar"
        )

        for (pageUrl in pagesToScan) {
            try {
                val document = app.get(pageUrl).document

                var cards: List<Element> = emptyList()
                for (selector in cardSelectors) {
                    val found = document.select(selector)
                    if (found.size >= 3) {
                        cards = found
                        break
                    }
                }
                if (cards.isEmpty()) cards = document.select("a[href]")

                cards.forEach { element ->
                    val result = if (element.tagName() == "a") {
                        element.toListPageResult()
                    } else {
                        element.toCardResult()
                    }
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            allContent.add(result)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ATV", "Sayfa çekme hatası ($pageUrl): ${e.message}")
            }
        }

        try {
            val mainDoc = app.get(mainUrl).document
            mainDoc.select("div.series-drop .sub-menu-list li a[href], div.program-drop-menu .sub-menu-list li a[href]")
                .forEach { element ->
                    val result = element.toMenuItemResult()
                    if (result != null) {
                        val key = normalizeForCompare(result.url)
                        if (key != null && seenPaths.add(key)) {
                            allContent.add(result)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e("ATV", "Menü çekme hatası: ${e.message}")
        }

        allContentCache = allContent
        cacheTime = currentTime
        Log.d("ATV", "getAllContent: ${allContent.size} öğe önbelleğe alındı")
        return allContent
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
            Log.d("ATV", "Fragman sayfası atlandı: $url")
            return null
        }

        val document = app.get(url).document

        if (url.contains("/izle")) {
            if (isTrailer(url)) return null

            val title = document.selectFirst("h1.video-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null

            if (isTrailer(title)) return null

            val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

            val epNum = extractEpisodeNumber(url)

            val episode = newEpisode(url) {
                this.name = title
                this.episode = epNum
                this.posterUrl = poster
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOfNotNull(episode)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Dizi detay sayfası - poster için og:image + yedekleri dene
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("meta[name=twitter:image]")?.attr("content"))
            ?: document.body().extractPoster()
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getAllEpisodes(document, url)

        Log.d("ATV", "load: $title -> ${episodes.size} bölüm | poster: ${poster ?: "YOK"}")
        episodes.take(5).forEach { Log.d("ATV", "  Bölüm ${it.episode}: ${it.name} -> ${it.data}") }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun extractEpisodeNumber(url: String): Int? {
        val lowerUrl = url.lowercase(Locale.getDefault())

        Regex("/(\\d+)-bolum(?:[/\\-]|$)", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                return it.groupValues[1].toIntOrNull()
            }

        Regex("/bolum-(\\d+)(?:[/\\-]|$)", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                return it.groupValues[1].toIntOrNull()
            }

        Regex("(\\d+)-sezon-(\\d+)-bolum", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                return it.groupValues[2].toIntOrNull()
            }

        Regex("/(\\d+)(?:-[^/]*)?/izle", RegexOption.IGNORE_CASE)
            .find(lowerUrl)?.let {
                return it.groupValues[1].toIntOrNull()
            }

        return null
    }

    private suspend fun getAllEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        val cleanBaseUrl = baseUrl.substringBefore("?").substringBefore("#").trimEnd('/')

        val allEpisodesUrls = mutableListOf<String>()

        document.select("a[href*='/bolumler']").forEach { el ->
            fixUrlNull(el.attr("href"))?.let { allEpisodesUrls.add(it) }
        }
        document.select("a:contains(TÜMÜ), a:contains(Tümü), a:contains(Tüm Bölümler)").forEach { el ->
            fixUrlNull(el.attr("href"))?.let { allEpisodesUrls.add(it) }
        }

        allEpisodesUrls.add("$cleanBaseUrl/bolumler")

        val slug = cleanBaseUrl.substringAfter(mainUrl).trim('/').substringBefore("/")
        allEpisodesUrls.add("$mainUrl/ajax/series/$slug/episodes")
        allEpisodesUrls.add("$mainUrl/ajax/$slug/episodes")

        for (url in allEpisodesUrls.distinct()) {
            try {
                Log.d("ATV", "Bölüm listesi deneniyor: $url")
                val doc = app.get(
                    url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to baseUrl
                    )
                ).document

                val found = extractEpisodesFromDoc(doc)
                if (found.isNotEmpty()) {
                    Log.d("ATV", "✓ $url -> ${found.size} bölüm bulundu")
                    allEpisodes.addAll(found)
                } else {
                    Log.d("ATV", "✗ $url -> bölüm yok")
                }
            } catch (e: Exception) {
                Log.d("ATV", "Deneme başarısız ($url): ${e.message}")
            }
        }

        val staticFromDetail = extractEpisodesFromDoc(document)
        allEpisodes.addAll(staticFromDetail)

        try {
            val sezonLinks = document.select("a[href*='sezon'], a[href*='sezonlar'], a[href*='season']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .distinct()

            for (sezonUrl in sezonLinks) {
                try {
                    val doc = app.get(sezonUrl).document
                    val found = extractEpisodesFromDoc(doc)
                    if (found.isNotEmpty()) {
                        Log.d("ATV", "Sezon sayfası: $sezonUrl -> ${found.size} bölüm")
                        allEpisodes.addAll(found)
                    }
                } catch (e: Exception) {
                    Log.d("ATV", "Sezon hatası ($sezonUrl): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("ATV", "Sezon tarama hatası: ${e.message}")
        }

        val uniqueByUrl = allEpisodes.distinctBy { it.data }

        val withNumber = uniqueByUrl.filter { it.episode != null && it.episode!! > 0 }
        val withoutNumber = uniqueByUrl.filter { it.episode == null || it.episode == 0 }

        val sortedWithNumber = withNumber.sortedBy { it.episode }
        val sortedWithoutNumber = withoutNumber.sortedBy { extractEpisodeNumber(it.data) ?: Int.MAX_VALUE }

        val finalEpisodes = sortedWithNumber + sortedWithoutNumber

        val result = finalEpisodes.mapIndexed { index, ep ->
            if (ep.episode == null || ep.episode == 0) {
                ep.episode = index + 1
            }
            ep
        }

        Log.d("ATV", "Toplam ${result.size} benzersiz bölüm (numaralı: ${sortedWithNumber.size})")
        return result
    }

    private fun extractEpisodesFromDoc(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeLinks = document.select("a[href*='/izle']")
            .filter { element ->
                val href = element.attr("href")

                if (isTrailer(href)) return@filter false

                val text = element.text()
                if (isTrailer(text)) return@filter false

                href.contains("-bolum") && href.endsWith("/izle")
            }

        episodeLinks.distinctBy { it.attr("href") }.forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            if (isTrailer(href)) return@forEach

            val epName = element.selectFirst(".style-01, .style-02, h3, .title, .date, span")
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: element.text().trim().takeIf { it.isNotEmpty() }
                ?: "Bölüm"

            if (isTrailer(epName)) return@forEach

            val epNum = extractEpisodeNumber(href)

            newEpisode(href) {
                this.name = epName
                this.episode = epNum
            }?.let { episodes.add(it) }
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ATV", "Video data: $data")

        if (isTrailer(data)) {
            Log.d("ATV", "Fragman linki atlandı: $data")
            return false
        }

        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
            var found = false

            val pageTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            if (isTrailer(pageTitle) || isTrailer(data)) {
                Log.d("ATV", "Fragman sayfası algılandı: $pageTitle")
                return false
            }

            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject")) continue

                val lowerContent = content.lowercase(Locale.getDefault())
                if (trailerKeywords.any { lowerContent.contains(it) }) continue

                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        val videoName = json.optString("name", "").lowercase(Locale.getDefault())

                        if (trailerKeywords.any { videoName.contains(it) }) continue

                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
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
                    Log.e("ATV", "JSON-LD parse hatası: ${e.message}")
                }
            }

            if (!found) {
                val playerScripts = document.select("script").filter { script ->
                    val data = script.data().lowercase(Locale.getDefault())
                    (data.contains("player") || data.contains("video") ||
                     data.contains("hls") || data.contains("m3u8") ||
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
                            if (isTrailer(videoUrl)) return@let

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

            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    if (!isTrailer(embedUrl)) {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("ATV", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
