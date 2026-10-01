// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır. (Star TV için uyarlanmıştır)

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale

class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // ★ Logo
    private val logoUrl = "https://upload.wikimedia.org/wikipedia/tr/2/20/Star_TV_logo.png"

    // ★ Canlı yayın m3u8 linkleri
    private val liveStreams = listOf(
        "https://dogus.daioncdn.net/startv/startv_1080p.m3u8" to "Star TV 1080p",
        "https://dogus.daioncdn.net/startv/startv_720p.m3u8" to "Star TV 720p",
        "https://dogus.daioncdn.net/startv/startv_480p.m3u8" to "Star TV 480p"
    )

    // ★ Ana sayfa menüsü – Canlı Yayın en başta
    override val mainPage = mainPageOf(
        "$mainUrl/canli-yayin" to "Canlı Yayın",
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/program" to "Programlar"
    )

    // ★ Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        // Canlı Yayın sekmesi
        if (request.name == "Canlı Yayın") {
            results.add(
                newMovieSearchResponse("Star TV Canlı", "$mainUrl/canli-yayin", TvType.Live) {
                    this.posterUrl = logoUrl
                }
            )
            return newHomePageResponse(
                listOf(HomePageList(request.name, results))
            )
        }

        try {
            val doc = app.get(request.data, headers = headers).document

            // ★ Tüm dizi/program kartlarını al
            val allLinks = doc.select(
                "a[href*='/dizi/'], a[href*='/program/']"
            )

            Log.d(name, "getMainPage [${request.name}]: toplam ${allLinks.size} ham link")

            allLinks.forEach { element ->
                // Nav/header/footer içindeki linkleri atla
                val parents = element.parents().map { it.tagName().lowercase() }
                val isInNav = parents.any { it == "nav" || it == "header" || it == "footer" }
                if (isInNav) return@forEach

                element.toSearchResponse()?.let { results.add(it) }
            }

            Log.d(name, "getMainPage [${request.name}]: ${results.size} öğe bulundu")
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, uniqueResults))
        )
    }

    // ★ Link elementini SearchResponse'a çevirir
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null

        // Sadece dizi/program linkleri
        val path = fullUrl.replace(mainUrl, "").trim('/')
        if (!path.startsWith("dizi/") && !path.startsWith("program/")) return null
        if (path.split("/").size != 2) return null

        // ★ Kart kapsayıcısını bul
        val card = this.closest("figure, article, li, div[class*=card], div[class*=item], div[class*=box], div.poster-card") ?: this

        // ★ Başlık – çok geniş seçici listesi
        val title = card.selectFirst(
            "figcaption, .title, .name, h2, h3, h4, " +
            "span[class*=title], span[class*=name], p[class*=title]"
        )?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return null

        // ★ Poster
        val img = this.selectFirst("img") ?: card.selectFirst("img")
        val poster: String? = img?.let {
            val dataSrc = it.attr("data-src")
            val dataLazy = it.attr("data-lazy-src")
            val src = it.attr("src")
            val raw = when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazy.isNotEmpty() -> dataLazy
                src.isNotEmpty() -> src
                else -> ""
            }
            fixUrlNull(raw)
        }

        return newMovieSearchResponse(title, fullUrl, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ★ Arama
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/arama?q=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl, headers = headers).document
            doc.select("a[href*='/dizi/'], a[href*='/program/']")
                .forEach { element ->
                    element.toSearchResponse()?.let { results.add(it) }
                }
            Log.d(name, "Arama '$query': ${results.size} sonuç")
        } catch (e: Exception) {
            Log.e(name, "Arama hatası: ${e.message}")
        }

        // Fallback: tüm listeden filtrele
        if (results.isEmpty()) {
            try {
                val allContent = mutableListOf<SearchResponse>()
                for (pageUrl in listOf("$mainUrl/dizi", "$mainUrl/program")) {
                    try {
                        val doc = app.get(pageUrl, headers = headers).document
                        doc.select("a[href*='/dizi/'], a[href*='/program/']")
                            .forEach { element ->
                                element.toSearchResponse()?.let { allContent.add(it) }
                            }
                    } catch (e: Exception) {
                        Log.e(name, "Liste hatası ($pageUrl): ${e.message}")
                    }
                }
                val q = query.lowercase(Locale.getDefault())
                allContent.distinctBy { it.url }
                    .filter { it.name.lowercase(Locale.getDefault()).contains(q) }
                    .let { results.addAll(it) }
            } catch (e: Exception) {
                Log.e(name, "Fallback hatası: ${e.message}")
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ★ Detay sayfası
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: $url")

        // Canlı yayın
        if (url.contains("/canli-yayin")) {
            val episode = newEpisode(url) {
                this.name = "Star TV Canlı"
                this.posterUrl = logoUrl
            } ?: return null
            return newTvSeriesLoadResponse("Star TV Canlı", url, TvType.Live, listOf(episode)) {
                this.posterUrl = logoUrl
            }
        }

        // Bölüm sayfası mı?
        if (url.contains("/bolumler/")) {
            return loadEpisodePage(url)
        }

        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("|")
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getEpisodes(document, url)
        Log.d(name, "Toplam ${episodes.size} bölüm: $title")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ★ Bölüm sayfası (tek bölümlük dizi olarak)
    private suspend fun loadEpisodePage(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        var title: String? = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        var poster: String? = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var description: String? = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // JSON-LD'den ek bilgi
        document.select("script[type=application/ld+json]").forEach { script ->
            try {
                val json = JSONObject(script.data())
                if (json.optString("@type") == "VideoObject") {
                    if (title.isNullOrEmpty()) {
                        title = json.optString("name").substringBefore("|").trim()
                    }
                    if (poster.isNullOrEmpty()) {
                        poster = fixUrlNull(json.optString("thumbnailUrl"))
                    }
                    if (description.isNullOrEmpty()) {
                        description = json.optString("description").trim()
                    }
                }
            } catch (_: Exception) {}
        }

        if (title.isNullOrEmpty()) return null

        val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(title!!)?.groupValues?.get(1)?.toIntOrNull()

        val episode = newEpisode(url) {
            this.name = title!!
            this.episode = epNum
            this.posterUrl = poster
        } ?: return null

        return newTvSeriesLoadResponse(title!!, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ★ Bölümleri çek
    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        try {
            // Bölüm linklerini ara
            val episodeLinks = document.select("a[href*='/bolumler/']")
                .filter { element ->
                    val href = element.attr("href")
                    href.contains("/bolumler/") && !href.contains("fragman")
                }

            if (episodeLinks.isNotEmpty()) {
                Log.d(name, "Statik ${episodeLinks.size} bölüm linki bulundu")
                episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                    val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                    val epName = element.selectFirst(".video-card-title, h4, h3, .title")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: element.text().trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    val epNum = Regex("/(\\d+)-bolum").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    val epPoster = element.selectFirst("img")?.let { img ->
                        fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
                    }

                    newEpisode(href) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }?.let { allEpisodes.add(it) }
                }

                return allEpisodes.sortedBy { it.episode }
            }
        } catch (e: Exception) {
            Log.e(name, "Bölüm çekme hatası: ${e.message}")
        }

        return allEpisodes
    }

    // ★ Video linklerini çek
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: $data")
        var found = false

        // Canlı yayın linkleri
        if (data.contains("/canli-yayin")) {
            liveStreams.forEach { (url, qualityName) ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - $qualityName",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = when {
                            qualityName.contains("1080") -> Qualities.P1080.value
                            qualityName.contains("720") -> Qualities.P720.value
                            qualityName.contains("480") -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
            }
            return true
        }

        try {
            val document = app.get(data, headers = headers).document

            // Öncelik 1: JSON-LD VideoObject > contentUrl
            document.select("script[type=application/ld+json]").forEach { script ->
                try {
                    val json = JSONObject(script.data())
                    if (json.optString("@type") == "VideoObject") {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            Log.d(name, "JSON-LD contentUrl: $contentUrl")

                            val isHls = contentUrl.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = contentUrl,
                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                    }
                } catch (_: Exception) {}
            }

            // Öncelik 2: Regex ile m3u8/mp4 ara
            if (!found) {
                val patterns = listOf(
                    Regex("(https?://[^\"'\\s<>]*mncdn\\.com[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
                    Regex("(https?://[^\"'\\s<>]*akamaized\\.net[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
                    Regex("(https?://[^\"'\\s<>]+/smil:[^\"'\\s<>]+)"),
                    Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)"),
                    Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)"),
                    Regex("\"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist)\"\\s*:\\s*\"([^\"]+)\"")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        val match = pattern.find(content)
                        if (match != null) {
                            val rawUrl = match.groupValues[1]
                                .replace("\\/", "/")
                                .replace("\\u0026", "&")
                                .replace("\\u003d", "=")
                                .replace("\\u002f", "/")
                                .replace("\\u003a", ":")
                                .replace("\\u003F", "?")
                                .replace("\\u002E", ".")

                            if (rawUrl.startsWith("http") &&
                                (rawUrl.contains(".m3u8") || rawUrl.contains(".mp4") ||
                                 rawUrl.contains("mncdn") || rawUrl.contains("akamaized") ||
                                 rawUrl.contains("smil:"))) {
                                Log.d(name, "Regex: $rawUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = this.name,
                                        url = rawUrl,
                                        type = if (rawUrl.contains(".m3u8") || rawUrl.contains("smil:"))
                                            ExtractorLinkType.M3U8
                                        else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = mainUrl
                                    }
                                )
                                found = true
                                break
                            }
                        }
                    }
                    if (found) break
                }
            }

            // Öncelik 3: iframe embed
            if (!found) {
                val iframes = document.select("iframe[src]")
                Log.d(name, "Toplam ${iframes.size} iframe bulundu")

                for (iframe in iframes) {
                    if (found) break
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isBlank()) continue

                    val embedUrl = if (iframeSrc.startsWith("http")) iframeSrc else fixUrl(iframeSrc)
                    Log.d(name, "iframe: $embedUrl")

                    // 3a. Cloudstream'in kendi extractor'ları
                    try {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            Log.d(name, "loadExtractor BAŞARILI: $embedUrl")
                            found = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.d(name, "loadExtractor hatası: ${e.message}")
                    }

                    // 3b. Manuel iframe kazıma
                    findMediaInIframe(embedUrl, data)?.let { url ->
                        Log.d(name, "Manuel iframe BAŞARILI: $url")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = url,
                                type = if (url.contains(".m3u8") || url.contains("smil:"))
                                    ExtractorLinkType.M3U8
                                else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
        }

        return found
    }

    // ★ iframe içeriğini çek ve içindeki medya URL'ini ara (özyinelemeli)
    private suspend fun findMediaInIframe(
        embedUrl: String,
        referer: String,
        depth: Int = 0
    ): String? {
        if (depth > 3) {
            Log.d(name, "iframe derinlik limiti aşıldı (3)")
            return null
        }

        try {
            Log.d(name, "  → iframe çekiliyor (depth=$depth): $embedUrl")
            val iframeDoc = app.get(
                embedUrl,
                headers = headers + mapOf("Referer" to referer)
            ).document

            // 1. iframe HTML'inde medya URL'i ara
            extractMediaUrl(iframeDoc.html())?.let { url ->
                Log.d(name, "  ✓ iframe HTML'inde bulundu: $url")
                return url
            }

            // 2. <video> ve <source> elementleri
            iframeDoc.select("video[src], video source[src], source[src]").forEach { src ->
                val url = src.attr("src")
                if (url.isNotEmpty() && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("smil:"))) {
                    val fullUrl = if (url.startsWith("http")) url else fixUrl(url)
                    Log.d(name, "  ✓ iframe video element: $fullUrl")
                    return fullUrl
                }
            }

            // 3. Script'lerde ara
            for (script in iframeDoc.select("script")) {
                extractMediaUrl(script.data())?.let { url ->
                    Log.d(name, "  ✓ iframe script'te bulundu: $url")
                    return url
                }
            }

            // 4. İç içe iframe'lerde ara (özyinelemeli)
            for (nestedIframe in iframeDoc.select("iframe[src]")) {
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isBlank()) continue
                val nestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else fixUrl(nestedSrc)
                Log.d(name, "  → iç iframe bulundu: $nestedUrl")
                findMediaInIframe(nestedUrl, embedUrl, depth + 1)?.let { return it }
            }
        } catch (e: Exception) {
            Log.e(name, "  ✗ iframe çekme hatası ($embedUrl): ${e.message}")
        }
        return null
    }

    // ★ HTML metninden m3u8/mp4/medya URL'i çıkarır
    private fun extractMediaUrl(text: String): String? {
        val decoded = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002f", "/")
            .replace("\\u003a", ":")
            .replace("\\u003F", "?")
            .replace("\\u002E", ".")

        val patterns = listOf(
            Regex("(https?://[^\"'\\s<>]*mncdn\\.com[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
            Regex("(https?://[^\"'\\s<>]*akamaized\\.net[^\"'\\s<>]*\\.m3u8[^\"'\\s<>]*)"),
            Regex("(https?://[^\"'\\s<>]+/smil:[^\"'\\s<>]+)"),
            Regex("(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)"),
            Regex("(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)"),
            Regex("\"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist)\"\\s*:\\s*\"([^\"]+)\"")
        )

        for (pattern in patterns) {
            pattern.find(decoded)?.let { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") &&
                    (url.contains(".m3u8") || url.contains(".mp4") ||
                     url.contains("mncdn") || url.contains("akamaized") ||
                     url.contains("smil:"))) {
                    return url
                }
            }
        }
        return null
    }
}