// ! Bu araç @Blockades tarafından Tv2 için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.Locale

class Tv2 : MainAPI() {
    override var mainUrl = "https://www.tv2.com.tr"
    override var name = "Tv2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Live)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // ★ Logo
    private val logoUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSHCVVtAfWKc0F4y9Q35Un31VPzfErgIMKHucR2Xaxafg&s=10"

    // ★ Canlı yayın m3u8 linkleri
    private val liveStreams = listOf(
        "https://demiroren.daioncdn.net/teve2/teve2_1080p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to "Tv2 1080p",
        "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to "Tv2 720p",
        "https://demiroren.daioncdn.net/teve2/teve2_480p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to "Tv2 480p"
    )

    // ★ Ana sayfa menüsü
    override val mainPage = mainPageOf(
        "$mainUrl/canli-yayin" to "Canlı Yayın",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/diziler/arsiv" to "Arşivdeki Diziler",
        "$mainUrl/programlar" to "Programlar",
        "$mainUrl/programlar/arsiv" to "Arşivdeki Programlar",
        "$mainUrl/filmler" to "Filmler"
    )

    // ★ Ana sayfa
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()

        // Canlı Yayın sekmesi
        if (request.name == "Canlı Yayın") {
            results.add(
                newMovieSearchResponse("Tv2 Canlı", "$mainUrl/canli-yayin", TvType.Live) {
                    this.posterUrl = logoUrl
                }
            )
            return newHomePageResponse(listOf(HomePageList(request.name, results)))
        }

        try {
            val doc = app.get(request.data, headers = headers).document

            if (request.name == "Filmler") {
                doc.select("div.swiper-slide a.thumbnail, section.section-thumbnails a.swiper-slide.item.thumbnail")
                    .forEach { element ->
                        element.toFilmSearchResponse()?.let { results.add(it) }
                    }
            } else {
                doc.select("div.program-card").forEach { card ->
                    card.toProgramCardResponse()?.let { results.add(it) }
                }

                doc.select("section.section-thumbnails a.swiper-slide.item.thumbnail, a.swiper-slide.item.thumbnail")
                    .forEach { element ->
                        val parents = element.parents().map { it.tagName().lowercase() }
                        val isInNav = parents.any { it == "nav" || it == "header" || it == "footer" }
                        if (isInNav) return@forEach
                        element.toArchiveSearchResponse()?.let { results.add(it) }
                    }
            }

            Log.d(name, "getMainPage [${request.name}]: ${results.size} öğe bulundu")
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}")
        }

        val uniqueResults = results.distinctBy { it.url }
        return newHomePageResponse(listOf(HomePageList(request.name, uniqueResults)))
    }

    private fun Element.toProgramCardResponse(): SearchResponse? {
        val linkEl = this.selectFirst("div.program-card-footer a")
            ?: this.selectFirst("div.program-detail a[href]")
            ?: return null
        val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null
        val title = this.selectFirst("div.program-card-footer a div.title")?.text()?.trim()
            ?: this.selectFirst("div.detail-title")?.text()?.trim()
            ?: linkEl.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val poster: String? = this.selectFirst("div.program-image")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { fixUrlNull(it) }
            ?: this.selectFirst("img")?.let { img ->
                val dataSrc = img.attr("data-src")
                val src = img.attr("src")
                fixUrlNull(if (dataSrc.isNotEmpty()) dataSrc else src)
            }

        val type = if (fullUrl.contains("/filmler/")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
    }

    private fun Element.toFilmSearchResponse(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null
        if (!fullUrl.contains("/filmler/")) return null

        val title = this.selectFirst("div.desc-movie h1.title")?.text()?.trim()
            ?: this.selectFirst("div.desc-title")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val poster = this.selectFirst("img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val dataLazy = img.attr("data-lazy-src")
            val src = img.attr("src")
            val raw = when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazy.isNotEmpty() -> dataLazy
                src.isNotEmpty() -> src
                else -> ""
            }
            fixUrlNull(raw)
        }

        return newMovieSearchResponse(title, fullUrl, TvType.Movie) { this.posterUrl = poster }
    }

    private fun Element.toArchiveSearchResponse(): SearchResponse? {
        val href = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrlNull(href) ?: return null
        if (!fullUrl.contains("/diziler/") && !fullUrl.contains("/programlar/") && !fullUrl.contains("/filmler/")) return null

        val title = this.selectFirst("div.desc-title")?.text()?.trim()
            ?: this.selectFirst("div.title")?.text()?.trim()
            ?: this.selectFirst("span.desc-info")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = this.selectFirst("img")?.let { img ->
            val dataSrc = img.attr("data-src")
            val dataLazy = img.attr("data-lazy-src")
            val src = img.attr("src")
            val raw = when {
                dataSrc.isNotEmpty() -> dataSrc
                dataLazy.isNotEmpty() -> dataLazy
                src.isNotEmpty() -> src
                else -> ""
            }
            fixUrlNull(raw)
        }

        val type = if (fullUrl.contains("/filmler/")) TvType.Movie else TvType.TvSeries
        return newMovieSearchResponse(title, fullUrl, type) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/arama?q=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl, headers = headers).document

            doc.select("div.program-card").forEach { card ->
                card.toProgramCardResponse()?.let { results.add(it) }
            }
            doc.select("a.thumbnail, a.swiper-slide.item.thumbnail").forEach { element ->
                element.toArchiveSearchResponse()?.let { results.add(it) }
            }
            Log.d(name, "Arama '$query': ${results.size} sonuç")
        } catch (e: Exception) {
            Log.e(name, "Arama hatası: ${e.message}")
        }

        if (results.isEmpty()) {
            try {
                val allContent = mutableListOf<SearchResponse>()
                for (pageUrl in listOf(
                    "$mainUrl/diziler", "$mainUrl/diziler/arsiv",
                    "$mainUrl/programlar", "$mainUrl/programlar/arsiv",
                    "$mainUrl/filmler"
                )) {
                    try {
                        val doc = app.get(pageUrl, headers = headers).document
                        doc.select("div.program-card").forEach { card ->
                            card.toProgramCardResponse()?.let { allContent.add(it) }
                        }
                        doc.select("div.swiper-slide a.thumbnail, section.section-thumbnails a.swiper-slide.item.thumbnail")
                            .forEach { element ->
                                element.toFilmSearchResponse()?.let { allContent.add(it) }
                                    ?: element.toArchiveSearchResponse()?.let { allContent.add(it) }
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
            val episode = newEpisode(url) {
                this.name = "Tv2 Canlı"
                this.posterUrl = logoUrl
            } ?: return null
            return newTvSeriesLoadResponse("Tv2 Canlı", url, TvType.Live, listOf(episode)) {
                this.posterUrl = logoUrl
            }
        }

        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("div.program-detail a div.detail-title")?.text()?.trim()
            ?: document.selectFirst("h1.title")?.text()?.trim()
            ?: document.selectFirst("div.desc-wrapper h1.title")?.text()?.trim()
            ?: document.selectFirst("div.desc-movie h1.title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("|")
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: document.selectFirst("div.program-image")?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.image-area img")?.attr("data-src")?.let { fixUrlNull(it) }

        val description = document.selectFirst("div.detail-description")?.text()?.trim()
            ?: document.selectFirst("div.desc-info")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val isMovie = url.contains("/filmler/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = getEpisodes(document, url)
            Log.d(name, "Toplam ${episodes.size} bölüm: $title")
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        try {
            val baseUrlTrimmed = baseUrl.trimEnd('/')
            val bolumUrl = "$baseUrlTrimmed/bolumler"
            Log.d(name, "Bölümler sayfası: $bolumUrl")

            val bolumDoc = try {
                app.get(bolumUrl, headers = headers).document
            } catch (e: Exception) {
                Log.e(name, "Bölümler sayfası yüklenemedi: ${e.message}")
                document
            }

            val episodeCards = bolumDoc.select(
                "div.swiper-slide a.thumbnail, " +
                "section.section-thumbnails a.swiper-slide.item.thumbnail, " +
                "a.swiper-slide.item.thumbnail, " +
                "a[href*='/bolum/'], " +
                "a[href*='/kisa-klipler/']"
            )

            if (episodeCards.isNotEmpty()) {
                Log.d(name, "Bölüm kartlarında ${episodeCards.size} bölüm")

                episodeCards.forEachIndexed { index, card ->
                    val epHref = card.attr("href").takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    val epUrl = fixUrlNull(epHref) ?: return@forEachIndexed
                    if (allEpisodes.any { it.data == epUrl }) return@forEachIndexed

                    val epName = card.selectFirst("div.desc-title")?.text()?.trim()
                        ?: card.selectFirst("div.title")?.text()?.trim()
                        ?: card.selectFirst("span.desc-info")?.text()?.trim()
                        ?: card.selectFirst("div.desc-movie h1.title")?.text()?.trim()
                        ?: card.attr("title").trim().takeIf { it.isNotEmpty() }
                        ?: "Bölüm ${index + 1}"

                    val epPoster: String? = card.selectFirst("img")?.let {
                        val dataSrc = it.attr("data-src")
                        val src = it.attr("src")
                        fixUrlNull(if (dataSrc.isNotEmpty()) dataSrc else src)
                    }

                    val epNum = Regex("(\\d+)\\.\\s*Bölüm").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = epPoster
                    }?.let { allEpisodes.add(it) }
                }

                return allEpisodes.sortedByDescending { it.episode ?: 0 }
            }
        } catch (e: Exception) {
            Log.e(name, "Bölüm sayfası hatası: ${e.message}")
        }

        return allEpisodes.sortedByDescending { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: $data")
        var found = false

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

            // Öncelik 1: JSON-LD
            document.select("script[type=application/ld+json]").forEach { script ->
                try {
                    val json = JSONObject(script.data())
                    if (json.optString("@type") == "VideoObject") {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty() && contentUrl.startsWith("http")) {
                            Log.d(name, "JSON-LD contentUrl: $contentUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = contentUrl,
                                    type = if (contentUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

            // Öncelik 2: data-url / data-video
            if (!found) {
                val videoEl = document.selectFirst("div[data-url], div[data-video], video source")
                val rawUrl = videoEl?.attr("data-url")?.takeIf { it.isNotEmpty() }
                    ?: videoEl?.attr("data-video")?.takeIf { it.isNotEmpty() }
                    ?: videoEl?.attr("src")?.takeIf { it.isNotEmpty() }

                if (!rawUrl.isNullOrEmpty()) {
                    Log.d(name, "data-url: $rawUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = rawUrl,
                            type = if (rawUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            }

            // Öncelik 3: Regex
            if (!found) {
                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("\"videoUrl\"\\s*:\\s*\"([^\"]+)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)"),
                    Regex("(https?://[^\"'\\s]+\\.mp4[^\"'\\s]*)")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        val match = pattern.find(content)
                        if (match != null) {
                            val rawUrl = match.groupValues[1].replace("\\/", "/")
                            Log.d(name, "Regex: $rawUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = rawUrl,
                                    type = if (rawUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
            }

            // Öncelik 4: iframe
            if (!found) {
                val iframe = document.selectFirst("iframe[src*='embed'], iframe[src*='player'], iframe[src*='daion']")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d(name, "iframe: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
        }

        return found
    }
}
