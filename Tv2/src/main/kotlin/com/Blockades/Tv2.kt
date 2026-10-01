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

    private var allContentCache: List<SearchResponse> = emptyList()
    private var cacheTime: Long = 0
    private val cacheValidityDuration = 30 * 60 * 1000 // 30 dakika

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"      to "Diziler",
        "${mainUrl}/programlar"   to "Programlar",
        "${mainUrl}/filmler"      to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val results = mutableListOf<SearchResponse>()

        when (request.name) {
            "Diziler" -> {
                document.select("div.program-card").forEach { element ->
                    val title = element.selectFirst("div.program-card-footer a div.title")?.text()?.trim() ?: return@forEach
                    val href = fixUrlNull(element.selectFirst("div.program-card-footer a")?.attr("href")) ?: return@forEach
                    val poster = element.selectFirst("div.program-image")?.attr("style")
                        ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
            "Programlar" -> {
                document.select("div.program-card").forEach { element ->
                    val title = element.selectFirst("div.program-card-footer a div.title")?.text()?.trim() ?: return@forEach
                    val href = fixUrlNull(element.selectFirst("div.program-card-footer a")?.attr("href")) ?: return@forEach
                    val poster = element.selectFirst("div.program-image")?.attr("style")
                        ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
            "Filmler" -> {
                document.select("div.swiper-slide a.thumbnail").forEach { element ->
                    val title = element.selectFirst("div.desc-movie h1.title")?.text()?.trim() ?: return@forEach
                    val href = fixUrlNull(element.attr("href")) ?: return@forEach
                    val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return newHomePageResponse(request.name, results, hasNext = false)
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
                val document = app.get(categoryUrl).document

                if (categoryUrl.contains("filmler")) {
                    document.select("div.swiper-slide a.thumbnail").forEach { element ->
                        val title = element.selectFirst("div.desc-movie h1.title")?.text()?.trim() ?: return@forEach
                        val href = fixUrlNull(element.attr("href")) ?: return@forEach
                        val poster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                        allContent.add(newMovieSearchResponse(title, href, type) {
                            this.posterUrl = poster
                        })
                    }
                } else {
                    document.select("div.program-card").forEach { element ->
                        val title = element.selectFirst("div.program-card-footer a div.title")?.text()?.trim() ?: return@forEach
                        val href = fixUrlNull(element.selectFirst("div.program-card-footer a")?.attr("href")) ?: return@forEach
                        val poster = element.selectFirst("div.program-image")?.attr("style")
                            ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }

                        allContent.add(newMovieSearchResponse(title, href, type) {
                            this.posterUrl = poster
                        })
                    }
                }
            }

            val uniqueContent = allContent.distinctBy { it.url }
            Log.d("TV2", "Tekrar eden öğeler kaldırıldı. Toplam: ${uniqueContent.size}")

            allContentCache = uniqueContent
            cacheTime = currentTime

            Log.d("TV2", "Tüm içerikler toplandı: ${uniqueContent.size} öğe")
            return uniqueContent

        } catch (e: Exception) {
            Log.e("TV2", "Tüm içerikler toplanırken hata: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) {
            return emptyList()
        }

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
        val document = app.get(url).document

        val title = document.selectFirst("div.program-detail a div.detail-title")?.text()?.trim()
            ?: document.selectFirst("h1.title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("div.program-image")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div.image-area img")?.attr("data-src")?.let { fixUrlNull(it) }

        val description = document.selectFirst("div.detail-description")?.text()?.trim()
            ?: document.selectFirst("div.desc-info")?.text()?.trim()

        val isMovie = url.contains("/filmler/")

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
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

    private suspend fun getEpisodes(url: String, document: org.jsoup.nodes.Document): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()

        try {
            // Bölümler sayfasına git
            val episodesUrl = when {
                url.contains("/diziler/guncel/") -> "$url/bolumler"
                url.contains("/programlar/guncel/") -> "$url/bolumler"
                else -> "$url/bolumler"
            }

            Log.d("TV2", "Bölümler URL: $episodesUrl")
            val episodesDoc = app.get(episodesUrl).document

            // Bölüm kartlarını bul
            val episodeElements = episodesDoc.select("div.swiper-slide a.thumbnail, div.program-card, div.video-item")

            if (episodeElements.isEmpty()) {
                // Alternatif seçici dene
                val altElements = episodesDoc.select("a[href*='/bolum/'], a[href*='/kisa-klipler/'], a[href*='/fragmanlar/']")
                altElements.forEach { element ->
                    val epTitle = element.selectFirst("div.desc-title, div.title, span.desc-info")?.text()?.trim() ?: return@forEach
                    val epUrl = fixUrlNull(element.attr("href")) ?: return@forEach

                    allEpisodes.add(newEpisode(epUrl) {
                        name = epTitle
                        episode = allEpisodes.size + 1
                    })
                }
            } else {
                episodeElements.forEachIndexed { index, element ->
                    val epTitle = element.selectFirst("div.desc-title, div.title, span.desc-info")?.text()?.trim() ?: return@forEachIndexed
                    val epUrl = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
                    val epPoster = element.selectFirst("img")?.attr("data-src")?.let { fixUrlNull(it) }

                    allEpisodes.add(newEpisode(epUrl) {
                        name = epTitle
                        episode = index + 1
                        this.posterUrl = epPoster
                    })
                }
            }

            Log.d("TV2", "Toplam episode: ${allEpisodes.size}")
            return allEpisodes

        } catch (e: Exception) {
            Log.e("TV2", "Episode fetch hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("TV2", "Video data: $data")

        try {
            if (data.isBlank()) {
                Log.e("TV2", "Video data boş")
                return false
            }

            // Canlı yayın linkleri
            if (data.contains("canli-yayin") || data.contains("teve2_1080p") || data.contains("teve2_720p") || data.contains("teve2_480p")) {
                val liveStreams = listOf(
                    "https://demiroren.daioncdn.net/teve2/teve2_1080p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P1080.value,
                    "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P720.value,
                    "https://demiroren.daioncdn.net/teve2/teve2_480p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P480.value
                )

                liveStreams.forEach { (url, quality) ->
                    callback.invoke(
                        newExtractorLink(
                            name = name,
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

            // Video sayfasını yükle
            val videoDoc = app.get(data).document

            // Video URL'sini bul
            val videoElement = videoDoc.selectFirst("div[data-url], div[data-video], video source")
            val videoUrl = videoElement?.attr("data-url")
                ?: videoElement?.attr("data-video")
                ?: videoElement?.attr("src")
                ?: videoDoc.selectFirst("script:containsData(videoUrl)")?.data()?.let { script ->
                    val regex = """"videoUrl"\s*:\s*"([^"]+)"""".toRegex()
                    regex.find(script)?.groupValues?.get(1)
                }

            if (videoUrl.isNullOrEmpty()) {
                Log.w("TV2", "Video URL bulunamadı")
                return false
            }

            Log.d("TV2", "Video URL bulundu: $videoUrl")

            if (videoUrl.contains(".mp4")) {
                val httpsUrl = videoUrl.replace("http://", "https://")

                // Farklı kaliteleri dene
                listOf("-720p", "-480p", "").forEach { qualitySuffix ->
                    val url = if (qualitySuffix.isNotEmpty()) {
                        httpsUrl.replace(".mp4", "$qualitySuffix.mp4")
                    } else {
                        httpsUrl
                    }

                    val quality = when (qualitySuffix) {
                        "-720p" -> Qualities.P720.value
                        "-480p" -> Qualities.P480.value
                        else -> Qualities.Unknown.value
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
            } else if (videoUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            return false

        } catch (e: Exception) {
            Log.e("TV2", "LoadLinks hatası: ${e.message}")
            return false
        }
    }

    // Canlı yayın için özel fonksiyon
    suspend fun getLiveStreams(callback: (ExtractorLink) -> Unit): Boolean {
        val liveStreams = listOf(
            "https://demiroren.daioncdn.net/teve2/teve2_1080p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P1080.value,
            "https://demiroren.daioncdn.net/teve2/teve2_720p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P720.value,
            "https://demiroren.daioncdn.net/teve2/teve2_480p.m3u8?&sid=8sqx8frxe09f&app=6aab838a-437e-4a1b-bbd0-e30f79cdbbbd&ce=3" to Qualities.P480.value
        )

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
