// ! Bu araç @kerimmkirac tarafından | @kerimmkirac için yazılmıştır. (Kanal D için uyarlanmıştır)

package com.kerimmkirac

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class KanalD : MainAPI() {
    override var mainUrl              = "https://www.kanald.com.tr"
    override var name                 = "Kanal D"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"        to "Diziler",
        "${mainUrl}/programlar"     to "Programlar",
        "${mainUrl}/retro-d"        to "Retro D",
        "${mainUrl}/diziler/arsiv"  to "Kanal D Arşiv"
    )

    // Ortak seçici: tüm liste sayfaları için genişletilmiş
    private val listSelectors = (
        "section.listing-holder .item, " +
        "section.listing-holder .story-card, " +
        "div.listing-holder > div, " +
        "a.story-card, " +
        "a[href*='/retro-d/'], " +
        "a[href*='/diziler/']"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select(listSelectors)
        Log.d("KanalD", "getMainPage(${request.name}) seçici ${items.size} öğe buldu")

        val results = items.mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        Log.d("KanalD", "getMainPage(${request.name}) -> ${results.size} sonuç")

        return newHomePageResponse(
            listOf(HomePageList(request.name, results))
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Bazen <a> kendisi item olabilir, bazen içinde olabilir
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // Liste/ana sayfa linklerini atla
        val trimmed = href.trimEnd('/')
        if (trimmed == "${mainUrl}/retro-d" ||
            trimmed == "${mainUrl}/diziler" ||
            trimmed == "${mainUrl}/diziler/arsiv" ||
            trimmed == "${mainUrl}/programlar") return null

        // İstenmeyen alt sayfa linklerini atla
        if (href.contains("/bolumler") || href.contains("/fragmanlar") ||
            href.contains("/ozetler") || href.contains("/foto-galeri") ||
            href.contains("/haber") || href.contains("/oyuncular") ||
            href.contains("/kategoriler")) return null

        // Sadece içerik sayfası gibi görünen linkleri kabul et
        val isRetroD = href.contains("/retro-d/")
        val isDizi   = href.contains("/diziler/")
        val isNormal = href.matches(Regex(".*kanald\\.com\\.tr/[^/]+/?$"))
        if (!isRetroD && !isDizi && !isNormal) return null

        // Başlık: sırayla figcaption, h3, img alt, link title, link text
        val title = this.selectFirst("figcaption p, figcaption .title, h3.title, .caption .title, h2, h3")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() }
            ?: return null

        // Poster: data-src veya src, yoksa link içindeki img
        val poster = (this.selectFirst("img") ?: link.selectFirst("img"))?.let { img ->
            fixUrlNull(
                img.attr("data-src").ifEmpty { img.attr("src") }
                    .ifEmpty { img.attr("data-lazy-src") }
            )
        }

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()
        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            return allContentCache
        }

        val allContent = mutableListOf<SearchResponse>()
        try {
            val pagesToScan = listOf(
                "${mainUrl}/diziler",
                "${mainUrl}/programlar",
                "${mainUrl}/retro-d",
                "${mainUrl}/diziler/arsiv"
            )
            for (pageUrl in pagesToScan) {
                try {
                    val document = app.get(pageUrl).document
                    val items = document.select(listSelectors)
                    Log.d("KanalD", "$pageUrl -> ${items.size} öğe")
                    items.forEach { element ->
                        element.toMainPageResult()?.let { allContent.add(it) }
                    }
                } catch (e: Exception) {
                    Log.e("KanalD", "$pageUrl taranırken hata: ${e.message}")
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            allContentCache = uniqueContent
            cacheTime = currentTime
            Log.d("KanalD", "Toplam cache içerik: ${uniqueContent.size}")
            return uniqueContent
        } catch (e: Exception) {
            Log.e("KanalD", "İçerik toplanırken hata: ${e.message}")
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
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
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
            // Bölüm seçicileri: /bolumler linkleri, retro-d ise kendi linkleri
            val episodeSelector = (
                "section.listing-holder a.story-card, " +
                "section.listing-holder a[href*='/bolumler/'], " +
                "a.story-card[href*='/bolumler/'], " +
                "a[href*='/bolumler/'], " +
                "a[href*='/retro-d/']"
            )

            var episodeLinks = document.select(episodeSelector)

            // Hiç bölüm bulunamadıysa /bolumler alt sayfasını dene
            if (episodeLinks.isEmpty() && !baseUrl.contains("/bolumler")) {
                val episodePageUrl = "$baseUrl/bolumler"
                try {
                    episodeLinks = app.get(episodePageUrl).document.select(episodeSelector)
                } catch (e: Exception) {
                    Log.e("KanalD", "Bölümler sayfası hatası: ${e.message}")
                }
            }

            episodeLinks.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                val trimmed = href.trimEnd('/')
                if (trimmed == baseUrl.trimEnd('/')) return@forEachIndexed
                if (trimmed == "${mainUrl}/retro-d" ||
                    trimmed == "${mainUrl}/diziler" ||
                    trimmed == "${mainUrl}/diziler/arsiv" ||
                    trimmed == "${mainUrl}/programlar") return@forEachIndexed

                val epName = element.selectFirst("figcaption .title, figcaption p, h3.title, .caption .title, h2, h3")
                    ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: element.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: element.text().trim().takeIf { it.isNotEmpty() }
                    ?: "Bölüm ${index + 1}"

                newEpisode(href) {
                    this.name = epName
                    this.episode = index + 1
                }?.let { allEpisodes.add(it) }
            }
            return allEpisodes
        } catch (e: Exception) {
            Log.e("KanalD", "Bölüm çekme hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("KanalD", "Video data: $data")
        try {
            if (data.isBlank()) return false

            val document = app.get(data).document
            var found = false

            // ★ Öncelik 1: JSON-LD VideoObject > contentUrl
            val ldJsonScripts = document.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val content = script.data()
                if (!content.contains("VideoObject") && !content.contains("contentUrl")) continue
                try {
                    val json = JSONObject(content)
                    if (json.optString("@type").contains("VideoObject")) {
                        val contentUrl = json.optString("contentUrl", "")
                        if (contentUrl.isNotEmpty()) {
                            Log.d("KanalD", "JSON-LD contentUrl bulundu: $contentUrl")
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
                        }
                    }
                } catch (e: Exception) {
                    Log.e("KanalD", "JSON-LD parse hatası: ${e.message}")
                }
            }

            // ★ Öncelik 2: Regex ile contentUrl / m3u8 arama
            if (!found) {
                val patterns = listOf(
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\""),
                    Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\""),
                    Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)")
                )
                for (script in document.select("script")) {
                    val content = script.data()
                    for (pattern in patterns) {
                        pattern.find(content)?.let { match ->
                            val videoUrl = match.groupValues[1].replace("\\/", "/")
                            Log.d("KanalD", "Regex ile bulundu: $videoUrl")
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
                    }
                }
            }

            // ★ Öncelik 3: iframe embed
            if (!found) {
                val iframe = document.selectFirst("iframe[src]")
                if (iframe != null) {
                    val embedUrl = fixUrl(iframe.attr("src"))
                    Log.d("KanalD", "iframe bulundu: $embedUrl")
                    if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }

            return found
        } catch (e: Exception) {
            Log.e("KanalD", "LoadLinks hatası: ${e.message}")
            return false
        }
    }
}
