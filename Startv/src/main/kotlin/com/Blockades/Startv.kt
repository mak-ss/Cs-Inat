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
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // ★ Logo
    private val logoUrl = "https://www.google.com/s2/favicons?domain=www.startv.com.tr&sz=256"

    // ★ Canlı yayın için ÇALIŞAN URL (logcat'te doğrulandı)
    // dogus.daioncdn.net + ?app=startv_web&ce=3 parametresi ile 403 vermiyor
    private val workingLiveUrl = "https://dogus.daioncdn.net/startv/startv_720p.m3u8?app=startv_web&ce=3"
    private val workingLiveUrlAlt = "https://dogus.daioncdn.net/startv/startv.m3u8?app=startv_web&ce=3"

    // ★ Ana sayfa menüsü
    override val mainPage = mainPageOf(
        "$mainUrl/canli-yayin" to "Canlı Yayın",
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/program" to "Programlar"
    )

    // ★ Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

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
            val allLinks = doc.select("a[href*='/dizi/'], a[href*='/program/']")

            Log.d(name, "getMainPage [${request.name}]: toplam ${allLinks.size} ham link")

            allLinks.forEach { element ->
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

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null

        val path = fullUrl.replace(mainUrl, "").trim('/')
        if (!path.startsWith("dizi/") && !path.startsWith("program/")) return null
        if (path.split("/").size != 2) return null

        val card = this.closest("figure, article, li, div[class*=card], div[class*=item], div[class*=box], div.poster-card") ?: this

        val title = card.selectFirst(
            "figcaption, .title, .name, h2, h3, h4, " +
            "span[class*=title], span[class*=name], p[class*=title]"
        )?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?: return null

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

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: $url")

        if (url.contains("/canli-yayin")) {
            return newLiveStreamLoadResponse("Star TV Canlı", url, url) {
                this.posterUrl = logoUrl
            }
        }

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

    private suspend fun loadEpisodePage(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        var foundTitle: String? = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        var foundPoster: String? = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var foundDesc: String? = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        document.select("script[type=application/ld+json]").forEach { script ->
            try {
                val json = JSONObject(script.data())
                if (json.optString("@type") == "VideoObject") {
                    if (foundTitle.isNullOrEmpty()) {
                        foundTitle = json.optString("name").substringBefore("|").trim()
                    }
                    if (foundPoster.isNullOrEmpty()) {
                        foundPoster = fixUrlNull(json.optString("thumbnailUrl"))
                    }
                    if (foundDesc.isNullOrEmpty()) {
                        foundDesc = json.optString("description").trim()
                    }
                }
            } catch (_: Exception) {}
        }

        val finalTitle = foundTitle ?: return null
        val finalPoster = foundPoster
        val finalDesc = foundDesc

        val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(finalTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("/(\\d+)-bolum").find(url)?.groupValues?.get(1)?.toIntOrNull()

        val episode = newEpisode(url) {
            this.name = finalTitle
            this.episode = epNum
            this.posterUrl = finalPoster
        } ?: return null

        return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, listOf(episode)) {
            this.posterUrl = finalPoster
            this.plot = finalDesc
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        try {
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

    private fun isValidVideoUrl(url: String): Boolean {
        val lower = url.lowercase()

        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") ||
            lower.endsWith(".avif")
        ) return false

        if (Regex("""\.(jpg|jpeg|png|webp|gif|svg)(\?|$)""").containsMatchIn(lower)) return false

        if (lower.contains("_snapshot_") ||
            lower.contains("thumbnail") ||
            lower.contains("poster") ||
            (lower.contains("_web_") && lower.contains(".jpg"))
        ) return false

        if (lower.contains("/bolumler/") || lower.contains("/dizi/") || lower.contains("/program/")) return false

        return lower.contains(".m3u8") ||
               lower.contains(".mp4") ||
               lower.contains("smil:") ||
               lower.contains("manifest") ||
               lower.contains(".mpd")
    }

    private fun isTrackingIframe(src: String): Boolean {
        val lower = src.lowercase()
        return lower.contains("googletagmanager") ||
               lower.contains("google-analytics") ||
               lower.contains("doubleclick") ||
               lower.contains("facebook.com/tr") ||
               lower.contains("hotjar") ||
               lower.contains("yandex") ||
               lower.contains("segment.io") ||
               lower.contains("mixpanel")
    }

    // ★ Script'lerden video ID'sini çıkar (birden çok kalıp)
    private fun extractVideoId(scriptsJoined: String): String? {
        val patterns = listOf(
            Regex("""["']videoId["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']contentId["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']mediaId["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']assetId["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']entryId["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']video_id["']\s*:\s*["']([^"']+)["']"""),
            Regex("""data-video-id=["']([^"']+)["']"""),
            Regex("""data-content-id=["']([^"']+)["']"""),
        )
        for (p in patterns) {
            p.find(scriptsJoined)?.groupValues?.get(1)?.let { id ->
                if (id.length in 4..100 && !id.contains(" ")) return id
            }
        }
        return null
    }

    // ★ Doğuş VOD URL'i üret (logcat'te doğrulanmış pattern)
    private fun buildDogusVodUrls(videoId: String): List<String> = listOf(
        "https://dogus.daioncdn.net/startv/$videoId.m3u8?app=startv_web&ce=3",
        "https://dogus.daioncdn.net/startv/$videoId/playlist.m3u8?app=startv_web&ce=3",
        "https://dogus-vod.daioncdn.net/startv/$videoId.m3u8?app=startv_web&ce=3",
        "https://dogus-vod.daioncdn.net/startv/$videoId/playlist.m3u8?app=startv_web&ce=3"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: $data")
        var found = false

        if (data.contains("/canli-yayin")) {
            return loadLiveStreams(data, callback)
        }

        try {
            val document = app.get(data, headers = headers).document

            // Tüm script içeriklerini birleştir (id-arama ve regex için)
            val scriptsJoined = document.select("script:not([src])").joinToString("\n") { it.data() }

            // ★ Öncelik 1: JSON-LD VideoObject
            if (!found) {
                document.select("script[type=application/ld+json]").forEach { script ->
                    if (found) return@forEach
                    try {
                        val json = JSONObject(script.data())
                        if (json.optString("@type") == "VideoObject") {
                            val contentUrl = json.optString("contentUrl", "")
                            if (contentUrl.isNotEmpty() && contentUrl.startsWith("http") && isValidVideoUrl(contentUrl)) {
                                Log.d(name, "JSON-LD contentUrl: $contentUrl")
                                callback.invoke(newExtractorLink(
                                    source = this.name, name = this.name, url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) { this.referer = mainUrl; this.quality = Qualities.Unknown.value })
                                found = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // ★ Öncelik 2: Script'lerde doğrudan m3u8/mp4/daion/kaltura URL'i
            if (!found) {
                val urlPatterns = listOf(
                    Regex("""(https?://[^"'\s<>]*daioncdn\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]*mncdn\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]*akamaized\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]*kaltura\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]+/smil:[^"'\s<>]+)"""),
                    Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]+\.mp4)(?:[?"'\s<>]|$)"""),
                    Regex("""(?:"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist|dataUrl|manifestUrl)"\s*:\s*")([^"]+)""")
                )
                for (script in document.select("script")) {
                    if (found) break
                    val content = script.data()
                        .replace("\\/", "/").replace("\\u0026", "&")
                        .replace("\\u003d", "=").replace("\\u002f", "/")
                        .replace("\\u003a", ":").replace("\\u003F", "?")
                        .replace("\\u002E", ".")

                    for (pattern in urlPatterns) {
                        val match = pattern.find(content) ?: continue
                        val rawUrl = match.groupValues[1]
                        if (!rawUrl.startsWith("http") || !isValidVideoUrl(rawUrl)) continue
                        Log.d(name, "Script regex hit: $rawUrl")
                        callback.invoke(newExtractorLink(
                            source = this.name, name = this.name, url = rawUrl,
                            type = if (rawUrl.contains(".m3u8") || rawUrl.contains("smil:")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { this.referer = mainUrl })
                        found = true
                        break
                    }
                }
            }

            // ★ Öncelik 3: Video ID bul ve Doğuş VOD URL'i dene
            if (!found) {
                val videoId = extractVideoId(scriptsJoined)
                if (videoId != null) {
                    Log.d(name, "Video ID bulundu: $videoId, Doğuş VOD URL'leri deneniyor...")
                    // HEAD isteği ile hangisi yaşıyor test et
                    for (candidate in buildDogusVodUrls(videoId)) {
                        try {
                            // Sadece küçük bir istek yap
                            val resp = app.get(candidate, headers = headers + mapOf(
                                "Referer" to data,
                                "Origin" to mainUrl
                            ), allowRedirects = true)
                            if (resp.isSuccessful) {
                                Log.d(name, "Doğuş VOD hit: $candidate")
                                callback.invoke(newExtractorLink(
                                    source = this.name, name = this.name, url = candidate,
                                    type = ExtractorLinkType.M3U8
                                ) { this.referer = mainUrl })
                                found = true
                                break
                            }
                        } catch (e: Exception) {
                            Log.d(name, "Doğuş VOD denemesi başarısız ($candidate): ${e.message}")
                        }
                    }
                }
            }

            // ★ Öncelik 4: iframe (GTM/analytics hariç)
            if (!found) {
                val iframes = document.select("iframe[src]")
                    .filterNot { isTrackingIframe(it.attr("src")) }

                Log.d(name, "Toplam ${iframes.size} geçerli iframe bulundu")

                for (iframe in iframes) {
                    if (found) break
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isBlank()) continue
                    val embedUrl = if (iframeSrc.startsWith("http")) iframeSrc else fixUrl(iframeSrc)
                    Log.d(name, "iframe: $embedUrl")

                    try {
                        if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                            Log.d(name, "loadExtractor BAŞARILI: $embedUrl")
                            found = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.d(name, "loadExtractor hatası: ${e.message}")
                    }

                    findMediaInIframe(embedUrl, data)?.let { url ->
                        if (!isValidVideoUrl(url)) return@let
                        Log.d(name, "Manuel iframe BAŞARILI: $url")
                        callback.invoke(newExtractorLink(
                            source = this.name, name = this.name, url = url,
                            type = if (url.contains(".m3u8") || url.contains("smil:")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) { this.referer = data; this.quality = Qualities.Unknown.value })
                        found = true
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
        }

        return found
    }

    // ★ Canlı yayın — çalışan URL önce, fallback sonra
    private suspend fun loadLiveStreams(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = mutableSetOf<String>()

        // Önce sayfadan dene (hata olsa bile devam)
        try {
            val doc = app.get(pageUrl, headers = headers).document
            for (script in doc.select("script")) {
                val content = script.data().replace("\\/", "/").replace("\\u0026", "&")
                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
                    .findAll(content).forEach { found.add(it.groupValues[1]) }
            }
            doc.select("video[src], source[src]").forEach { el ->
                val src = el.attr("src")
                if (src.contains(".m3u8")) found.add(if (src.startsWith("http")) src else fixUrl(src))
            }
            Log.d(name, "Canlı sayfadan ${found.size} m3u8 bulundu")
        } catch (e: Exception) {
            Log.e(name, "Canlı sayfa çekilemedi: ${e.message}")
        }

        // Çalışan URL önce, sonra alternatifler
        if (found.isEmpty()) {
            Log.d(name, "Fallback canlı URL'ler kullanılıyor")
            // ★ Logcat'te ÇALIŞTIĞI DOĞRULANAN URL — BAŞTA
            found.add(workingLiveUrl)
            found.add(workingLiveUrlAlt)
            // Yedekler
            found.add("https://dogus.daioncdn.net/startv/startv_1080p.m3u8?app=startv_web&ce=3")
            found.add("https://dogus-live.daioncdn.net/startv/startv.m3u8?app=startv_web&ce=3")
        }

        fun guessQuality(url: String): Int = when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720")  -> Qualities.P720.value
            url.contains("480")  -> Qualities.P480.value
            url.contains("360")  -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

        found.forEachIndexed { i, url ->
            Log.d(name, "Canlı m3u8: $url")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "$name - Canlı ${i + 1}",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = guessQuality(url)
            })
        }
        return found.isNotEmpty()
    }

    private suspend fun findMediaInIframe(
        embedUrl: String,
        referer: String,
        depth: Int = 0
    ): String? {
        if (depth > 3) return null

        try {
            Log.d(name, "  → iframe çekiliyor (depth=$depth): $embedUrl")
            val iframeDoc = app.get(embedUrl, headers = headers + mapOf("Referer" to referer)).document

            extractMediaUrl(iframeDoc.html())?.let { url ->
                Log.d(name, "  ✓ iframe HTML: $url")
                return url
            }

            iframeDoc.select("video[src], video source[src], source[src]").forEach { src ->
                val url = src.attr("src")
                if (url.isNotEmpty() && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("smil:"))) {
                    val fullUrl = if (url.startsWith("http")) url else fixUrl(url)
                    Log.d(name, "  ✓ iframe video element: $fullUrl")
                    return fullUrl
                }
            }

            for (script in iframeDoc.select("script")) {
                extractMediaUrl(script.data())?.let { url ->
                    Log.d(name, "  ✓ iframe script: $url")
                    return url
                }
            }

            for (nestedIframe in iframeDoc.select("iframe[src]")) {
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isBlank()) continue
                if (isTrackingIframe(nestedSrc)) continue
                val nestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else fixUrl(nestedSrc)
                findMediaInIframe(nestedUrl, embedUrl, depth + 1)?.let { return it }
            }
        } catch (e: Exception) {
            Log.e(name, "  ✗ iframe hatası ($embedUrl): ${e.message}")
        }
        return null
    }

    private fun extractMediaUrl(text: String): String? {
        val decoded = text
            .replace("\\/", "/").replace("\\u0026", "&")
            .replace("\\u003d", "=").replace("\\u002f", "/")
            .replace("\\u003a", ":").replace("\\u003F", "?")
            .replace("\\u002E", ".")

        val patterns = listOf(
            Regex("""(https?://[^"'\s<>]*daioncdn\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]*mncdn\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]*akamaized\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]*kaltura\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]+/smil:[^"'\s<>]+)"""),
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]+\.mp4)(?:[?"'\s<>]|$)"""),
            Regex("""(?:"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist|dataUrl|manifestUrl)"\s*:\s*")([^"]+)""")
        )

        for (pattern in patterns) {
            pattern.find(decoded)?.let { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") && isValidVideoUrl(url) && !url.contains("/bolumler/")) {
                    return url
                }
            }
        }
        return null
    }
}
