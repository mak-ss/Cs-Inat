// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades



import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class Tv2 : MainAPI() {
    override var mainUrl              = "https://www.tv2.com.tr"
    override var name                 = "Tv2"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var iconUrl              = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSHCVVtAfWKc0F4y9Q35Un31VPzfErgIMKHucR2Xaxafg&s=10"

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000 // 30 dakika

    // Canlı yayın linkleri
    private val liveStreams = listOf(
        "https://demiroren.daioncdn.net/teve2/teve2_1080p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P1080.value,
        "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P720.value,
        "https://demiroren.daioncdn.net/teve2/teve2_480p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P480.value
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"      to "Diziler",
        "${mainUrl}/programlar"   to "Programlar",
        "${mainUrl}/filmler"      to "Filmler",
        "LIVE"                    to "Canlı Yayın"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Canlı Yayın kategorisi
        if (request.data == "LIVE") {
            val liveResponse = newMovieSearchResponse(
                "Tv2 Canlı Yayın",
                "LIVE_STREAM",
                TvType.Live
            ) {
                this.posterUrl = iconUrl
            }
            return newHomePageResponse(request.name, listOf(liveResponse), hasNext = false)
        }

        val document = app.get(request.data).document
        val results = mutableListOf<SearchResponse>()

        when (request.name) {
            "Diziler", "Programlar" -> {
                document.select("div.program-card").forEach { element ->
                    val title = element.selectFirst("div.program-card-footer a div.title")?.text()?.trim()
                        ?: return@forEach
                    val href = fixUrlNull(element.selectFirst("div.program-card-footer a")?.attr("href"))
                        ?: return@forEach
                    val poster = element.selectFirst("div.program-image")?.attr("style")
                        ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }

                // Arşiv kısmını da tara
                document.select("section.section-thumbnails a.swiper-slide.item.thumbnail").forEach { element ->
                    val title = element.selectFirst("div.desc-title")?.text()?.trim()
                        ?: element.selectFirst("span.desc-info")?.text()?.trim()
                        ?: return@forEach
                    val href = fixUrlNull(element.attr("href")) ?: return@forEach
                    val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }

            "Filmler" -> {
                document.select("div.swiper-slide a.thumbnail, section.section-thumbnails a.swiper-slide.item.thumbnail").forEach { element ->
                    val title = element.selectFirst("div.desc-movie h1.title")?.text()?.trim()
                        ?: element.selectFirst("div.desc-title")?.text()?.trim()
                        ?: return@forEach
                    val href = fixUrlNull(element.attr("href")) ?: return@forEach
                    val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return newHomePageResponse(request.name, results.distinctBy { it.url }, hasNext = false)
    }

    private suspend fun getAllContent(): List<SearchResponse> {
        val currentTime = System.currentTimeMillis()

        if (allContentCache.isNotEmpty() && (currentTime - cacheTime) < cacheValidityDuration) {
            Log.d("TV2", "Cache'den içerik döndürülüyor: ${allContentCache.size} öğe")
            return allContentCache
        }

        Log.d("TV2", "Tüm içerikler toplanıyor")
        val allContent = mutableListOf<SearchResponse>()

        try {
            val categories = listOf(
                "${mainUrl}/diziler" to TvType.TvSeries,
                "${mainUrl}/programlar" to TvType.TvSeries,
                "${mainUrl}/filmler" to TvType.Movie
            )

            for ((categoryUrl, type) in categories) {
                Log.d("TV2", "Kategori işleniyor: $categoryUrl")
                try {
                    val document = app.get(categoryUrl).document

                    if (categoryUrl.contains("filmler")) {
                        document.select("div.swiper-slide a.thumbnail, section.section-thumbnails a.swiper-slide.item.thumbnail").forEach { element ->
                            val title = element.selectFirst("div.desc-movie h1.title")?.text()?.trim()
                                ?: element.selectFirst("div.desc-title")?.text()?.trim()
                                ?: return@forEach
                            val href = fixUrlNull(element.attr("href")) ?: return@forEach
                            val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                            allContent.add(newMovieSearchResponse(title, href, type) {
                                this.posterUrl = poster
                            })
                        }
                    } else {
                        document.select("div.program-card").forEach { element ->
                            val title = element.selectFirst("div.program-card-footer a div.title")?.text()?.trim()
                                ?: return@forEach
                            val href = fixUrlNull(element.selectFirst("div.program-card-footer a")?.attr("href"))
                                ?: return@forEach
                            val poster = element.selectFirst("div.program-image")?.attr("style")
                                ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }

                            allContent.add(newMovieSearchResponse(title, href, type) {
                                this.posterUrl = poster
                            })
                        }

                        document.select("section.section-thumbnails a.swiper-slide.item.thumbnail").forEach { element ->
                            val title = element.selectFirst("div.desc-title")?.text()?.trim()
                                ?: element.selectFirst("span.desc-info")?.text()?.trim()
                                ?: return@forEach
                            val href = fixUrlNull(element.attr("href")) ?: return@forEach
                            val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                            allContent.add(newMovieSearchResponse(title, href, type) {
                                this.posterUrl = poster
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TV2", "Kategori hatası ($categoryUrl): ${e.message}")
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            Log.d("TV2", "Tekrar eden öğeler kaldırıldı. Toplam: ${uniqueContent.size}")

            allContentCache = uniqueContent
            cacheTime = currentTime

            return uniqueContent

        } catch (e: Exception) {
            Log.e("TV2", "Tüm içerikler toplanırken hata: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        Log.d("TV2", "Arama yapılıyor: '$query'")
        val allContent = getAllContent()

        if (allContent.isEmpty()) {
            Log.w("TV2", "Arama için içerik bulunamadı")
            return emptyList()
        }

        val searchQuery = query.lowercase(Locale.getDefault())
        val results = allContent.filter { content ->
            content.name.lowercase(Locale.getDefault()).contains(searchQuery)
        }

        Log.d("TV2", "Arama sonucu: ${results.size} öğe bulundu")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        // Canlı yayın
        if (url == "LIVE_STREAM") {
            return newLiveStreamLoadResponse(
                "Tv2 Canlı Yayın",
                "LIVE_STREAM",
                iconUrl
            )
        }

        val document = app.get(url).document

        val title = document.selectFirst("div.program-detail a div.detail-title")?.text()?.trim()
            ?: document.selectFirst("h1.title")?.text()?.trim()
            ?: document.selectFirst("div.desc-wrapper h1.title")?.text()?.trim()
            ?: document.selectFirst("div.desc-movie h1.title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("div.program-image")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.image-area img")?.attr("data-src")?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }

        val description = document.selectFirst("div.detail-description")?.text()?.trim()
            ?: document.selectFirst("div.desc-info")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val isMovie = url.contains("/filmler/")

        if (isMovie) {
            // Film sayfasından video URL'sini al
            val videoUrl = extractVideoUrl(document)
                ?: url // fallback

            return newMovieLoadResponse(title, url, TvType.Movie, videoUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Dizi veya Program - bölümleri çek
            val episodes = getEpisodes(url, document)

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private fun extractVideoUrl(document: org.jsoup.nodes.Document): String? {
        // data-url, data-video veya script içinden video URL'si bul
        document.selectFirst("div[data-url]")?.attr("data-url")?.takeIf { it.isNotEmpty() }?.let { return it }
        document.selectFirst("div[data-video]")?.attr("data-video")?.takeIf { it.isNotEmpty() }?.let { return it }
        document.selectFirst("video source")?.attr("src")?.takeIf { it.isNotEmpty() }?.let { return it }

        // Script içinden
        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            val regex = """"(?:videoUrl|video_url|file|source|src)"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
            regex.find(data)?.groupValues?.get(1)?.let { return it }
        }

        return null
    }

    private suspend fun getEpisodes(url: String, document: org.jsoup.nodes.Document): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        try {
            // Bölümler sayfası URL'i
            val baseUrl = url.trimEnd('/')
            val episodesUrl = "$baseUrl/bolumler"

            Log.d("TV2", "Bölümler URL: $episodesUrl")

            val episodesDoc = try {
                app.get(episodesUrl).document
            } catch (e: Exception) {
                Log.e("TV2", "Bölümler sayfası yüklenemedi: ${e.message}")
                document
            }

            // Farklı bölüm seçicileri
            val episodeSelectors = listOf(
                "div.swiper-slide a.thumbnail",
                "section.section-thumbnails a.swiper-slide.item.thumbnail",
                "a[href*='/bolum/']",
                "a[href*='/kisa-klipler/']"
            )

            for (selector in episodeSelectors) {
                val elements = episodesDoc.select(selector)
                if (elements.isNotEmpty()) {
                    elements.forEachIndexed { index, element ->
                        val epTitle = element.selectFirst("div.desc-title")?.text()?.trim()
                            ?: element.selectFirst("div.title")?.text()?.trim()
                            ?: element.selectFirst("span.desc-info")?.text()?.trim()
                            ?: element.selectFirst("div.desc-movie h1.title")?.text()?.trim()
                            ?: return@forEachIndexed

                        val epUrl = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

                        if (allEpisodes.any { it.data == epUrl }) return@forEachIndexed

                        val epPoster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                        allEpisodes.add(newEpisode(epUrl) {
                            name = epTitle
                            episode = allEpisodes.size + 1
                            this.posterUrl = epPoster
                        })
                    }

                    if (allEpisodes.isNotEmpty()) break
                }
            }

            Log.d("TV2", "Toplam episode: ${allEpisodes.size}")
            return allEpisodes

        } catch (e: Exception) {
            Log.e("TV2", "Episode fetch hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TV2", "Video data: $data")

        try {
            // Canlı yayın
            if (data == "LIVE_STREAM" || data.contains("canli-yayin")) {
                Log.d("TV2", "Canlı yayın linkleri ekleniyor")
                liveStreams.forEach { (url, quality) ->
                    callback.invoke(
                        newExtractorLink(
                            name = "$name Canlı",
                            source = name,
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                }
                return true
            }

            if (data.isBlank()) {
                Log.e("TV2", "Video data boş")
                return false
            }

            // Doğrudan video URL'si ise
            if ((data.startsWith("http://") || data.startsWith("https://")) &&
                (data.contains(".mp4") || data.contains(".m3u8"))
            ) {
                val httpsUrl = data.replace("http://", "https://")

                if (httpsUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = httpsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }

                // MP4 kalitelerini dene
                listOf(
                    "-1080p" to Qualities.P1080.value,
                    "-720p" to Qualities.P720.value,
                    "-480p" to Qualities.P480.value,
                    "" to Qualities.Unknown.value
                ).forEach { (suffix, quality) ->
                    val url = if (suffix.isNotEmpty()) {
                        httpsUrl.replace(".mp4", "$suffix.mp4")
                    } else {
                        httpsUrl
                    }

                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                }
                return true
            }

            // Video sayfasını yükle ve URL'yi çıkar
            val videoDoc = app.get(data).document
            val videoUrl = extractVideoUrl(videoDoc)

            if (videoUrl.isNullOrEmpty()) {
                Log.w("TV2", "Video URL bulunamadı")
                return false
            }

            Log.d("TV2", "Video URL bulundu: $videoUrl")

            val httpsUrl = videoUrl.replace("http://", "https://")

            if (httpsUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = httpsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                listOf(
                    "-1080p" to Qualities.P1080.value,
                    "-720p" to Qualities.P720.value,
                    "-480p" to Qualities.P480.value,
                    "" to Qualities.Unknown.value
                ).forEach { (suffix, quality) ->
                    val url = if (suffix.isNotEmpty() && httpsUrl.contains(".mp4")) {
                        httpsUrl.replace(".mp4", "$suffix.mp4")
                    } else {
                        httpsUrl
                    }

                    callback.invoke(
                        newExtractorLink(
                            name = name,
                            source = name,
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = quality
                        }
                    )
                }
            }

            return true

        } catch (e: Exception) {
            Log.e("TV2", "LoadLinks hatası: ${e.message}")
            return false
        }
    }

    /**
     * Canlı yayınları doğrudan döndüren yardımcı fonksiyon
     */
    suspend fun getLiveStreams(callback: (ExtractorLink) -> Unit): Boolean {
        liveStreams.forEach { (url, quality) ->
            callback.invoke(
                newExtractorLink(
                    name = "$name Canlı",
                    source = name,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
        }
        return true
    }
}
