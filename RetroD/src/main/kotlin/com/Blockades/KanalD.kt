// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır. (Kanal D için uyarlanmıştır)

package com.Blockades

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

class KanalD : MainAPI() {
    override var mainUrl              = "https://www.kanald.com.tr"
    override var name                 = "Retro-D"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Live)

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000

    override val mainPage = mainPageOf(
        "${mainUrl}/retro-d/"            to "Retro Diziler"   
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val results = mutableListOf<SearchResponse>()

        // Retro D sayfalarındaki içerik kartlarını seç
        val items = document.select("div.swiper-slide a.poster-card")

        items.forEach { element ->
            element.toMainPageResult()?.let { results.add(it) }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, results.distinctBy { it.url }))
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        // Sadece dizi detay sayfalarını al
        if (!href.contains("/retro-d/") && !href.matches(Regex(".*kanald\\.com\\.tr/[^/]+$"))) return null
        if (href.contains("/bolumler") || href.contains("/fragmanlar") || href.contains("/ozetler") || href.contains("/foto-galeri") || href.contains("/haber")) return null

        // Başlık
        val title = this.selectFirst("figcaption .title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        // Poster
        val poster = this.selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
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
            // Tüm kategorileri tara
            val pagesToScan = listOf(
                "${mainUrl}/retro-d/",
                "${mainUrl}/retro-d/romantik/",
                "${mainUrl}/retro-d/dram/",
                "${mainUrl}/retro-d/aksiyon/",
                "${mainUrl}/retro-d/komedi/"
            )
            for (pageUrl in pagesToScan) {
                try {
                    val document = app.get(pageUrl).document
                    val items = document.select("div.swiper-slide a.poster-card")
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

        val title = document.selectFirst("h1.program-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes = getEpisodes(document, url)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun getEpisodes(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        try {
            // Bölüm listesi için sayfa içindeki tüm story-card'ları seç
            val episodeLinks = document.select("div.swiper-slide a.story-card, section.listing-holder a.story-card")

            // Eğer ana sayfada bölüm yoksa, /bolumler sayfasına git
            val items = if (episodeLinks.isEmpty()) {
                val episodePageUrl = if (baseUrl.contains("/bolumler")) baseUrl else "$baseUrl/bolumler"
                try {
                    app.get(episodePageUrl).document
                        .select("section.listing-holder a.story-card")
                } catch (e: Exception) {
                    emptyList()
                }
            } else episodeLinks

            items.distinctBy { it.attr("href") }.forEachIndexed { index, element ->
                val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                if (href.trimEnd('/') == baseUrl.trimEnd('/')) return@forEachIndexed

                val epName = element.selectFirst("figcaption .title")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() }
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
