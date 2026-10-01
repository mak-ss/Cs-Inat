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
    private val logoUrl = "https://upload.wikimedia.org/wikipedia/tr/2/20/Star_TV_logo.png"

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

        // ★ DÜZELTME: Canlı yayın artık LiveStreamLoadResponse döndürüyor.
        // TvType.Live için newTvSeriesLoadResponse kullanmak yanlış response tipi
        // ürettiğinden canlı yayın açılırken hata/uyumsuzluk yaratıyordu.
        if (url.contains("/canli-yayin")) {
            return newLiveStreamLoadResponse("Star TV Canlı", url) {
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

        var foundTitle: String? = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        var foundPoster: String? = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        var foundDesc: String? = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // JSON-LD'den ek bilgi
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
        // ★ DÜZELTME: !! yerine güvenli yerel değişken kullanımı
        val finalPoster = foundPoster
        val finalDesc = foundDesc

        // Başlıktan çıkmazsa URL'den çıkar
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

    // ★ Video URL doğrulama
    private fun isValidVideoUrl(url: String): Boolean {
        val lower = url.lowercase()

        // Görsel uzantıları reddet
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") ||
            lower.endsWith(".avif")
        ) return false

        // Sorgu parametreli görseller (?width=... gibi)
        if (Regex("""\.(jpg|jpeg|png|webp|gif|svg)(\?|$)""").containsMatchIn(lower)) return false

        // Snapshot / thumbnail / poster kalıpları
        if (lower.contains("_snapshot_") ||
            lower.contains("thumbnail") ||
            lower.contains("poster") ||
            (lower.contains("_web_") && lower.contains(".jpg"))
        ) return false

        // Gerçek medya işareti var mı?
        return lower.contains(".m3u8") ||
               lower.contains(".mp4") ||
               lower.contains("smil:") ||
               lower.contains("manifest") ||
               lower.contains(".mpd")
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

        // Canlı yayın linkleri dinamik olarak çekiliyor
        if (data.contains("/canli-yayin")) {
            return loadLiveStreams(data, callback)
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

                            if (!isValidVideoUrl(contentUrl)) {
                                Log.d(name, "JSON-LD geçersiz video URL atlandı: $contentUrl")
                                return@forEach
                            }

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
                    Regex("""(https?://[^"'\s<>]*mncdn\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]*akamaized\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]+/smil:[^"'\s<>]+)"""),
                    Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)"""),
                    Regex("""(https?://[^"'\s<>]+\.mp4)(?:[?"'\s<>]|$)"""),
                    Regex("""(?:"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist)"\s*:\s*")([^"]+)""")
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

                            if (!rawUrl.startsWith("http") || !isValidVideoUrl(rawUrl)) {
                                Log.d(name, "Regex geçersiz video URL atlandı: $rawUrl")
                                continue
                            }

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
                        if (!isValidVideoUrl(url)) {
                            Log.d(name, "Manuel iframe geçersiz video URL atlandı: $url")
                            return@let
                        }

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

    // ★ Canlı yayın linklerini sayfadan dinamik çıkar
    private suspend fun loadLiveStreams(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(pageUrl, headers = headers).document
            val found = mutableSetOf<String>()

            // 1) Script'lerde m3u8 ara
            for (script in doc.select("script")) {
                val content = script.data()
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""")
                    .findAll(content)
                    .forEach { found.add(it.groupValues[1]) }
            }

            // 2) video/source elementleri
            doc.select("video[src], source[src]").forEach { el ->
                val src = el.attr("src")
                if (src.contains(".m3u8")) {
                    found.add(if (src.startsWith("http")) src else fixUrl(src))
                }
            }

            // 3) data-* attribute'ları
            doc.select("[data-hls], [data-video], [data-stream], [data-src]").forEach { el ->
                listOf("data-hls", "data-video", "data-stream", "data-src").forEach { attr ->
                    val v = el.attr(attr)
                    if (v.contains(".m3u8")) {
                        found.add(if (v.startsWith("http")) v else fixUrl(v))
                    }
                }
            }

            if (found.isEmpty()) {
                Log.e(name, "Canlı yayın m3u8 bulunamadı")
                return false
            }

            // Kalite tahmini
            fun guessQuality(url: String): Int = when {
                url.contains("1080") -> Qualities.P1080.value
                url.contains("720")  -> Qualities.P720.value
                url.contains("480")  -> Qualities.P480.value
                url.contains("360")  -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            found.forEachIndexed { i, url ->
                Log.d(name, "Canlı m3u8 bulundu: $url")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - Canlı ${i + 1}",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = guessQuality(url)
                    }
                )
            }
            return true
        } catch (e: Exception) {
            Log.e(name, "Canlı yayın hatası: ${e.message}")
            return false
        }
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
            Regex("""(https?://[^"'\s<>]*mncdn\.com[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]*akamaized\.net[^"'\s<>]*\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]+/smil:[^"'\s<>]+)"""),
            Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)"""),
            Regex("""(https?://[^"'\s<>]+\.mp4)(?:[?"'\s<>]|$)"""),
            Regex("""(?:"(?:file|src|url|hls|hlsUrl|streamUrl|videoUrl|source|contentUrl|playlist)"\s*:\s*")([^"]+)""")
        )

        for (pattern in patterns) {
            pattern.find(decoded)?.let { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") && isValidVideoUrl(url)) {
                    return url
                }
            }
        }
        return null
    }
}
