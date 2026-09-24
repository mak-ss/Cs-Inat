package com.Blockades

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipalt2.com"
    override var name = "Dizipal"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    // TXT içerisinden güncellenen CKey parametresi
    private val appCKey = "MTc5MDI5MTQwMGY1MjllNzg3ZjlhYWZkY2JjYjE0YmVlZWVlOWZmNzExYzc1Zjc1M2M5ZGJiZTQ4ODg0ZDQ3MjRhMmJkM2VlYWQzNjlmOGFjNw=="

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    )

    private suspend fun fetchDocument(url: String): Document = runCatching {
        app.get(url, referer = "$mainUrl/", headers = browserHeaders).document
    }.getOrElse {
        Thread.sleep(1000)
        app.get(url, referer = "$mainUrl/", headers = browserHeaders).document
    }

    private fun Document.catalogLinks(): Pair<List<Element>, List<Element>> {
        // Yeni HTML yapısına göre link yakalama
        val movies = select("a[href*=/film/]").distinctBy { it.absUrl("href") }
        val episodes = select("a[href*=/dizi/]").distinctBy { it.absUrl("href") }
        return movies to episodes
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = fetchDocument(mainUrl)
        val (movieLinks, episodeLinks) = document.catalogLinks()

        val movies = movieLinks
            .take(18)
            .mapNotNull { it.toSearchResponse(TvType.Movie) }
        val series = episodeLinks
            .take(18)
            .mapNotNull { it.toSearchResponse(TvType.TvSeries) }

        return newHomePageResponse(
            listOf(
                HomePageList("Günün / Haftanın Trendleri", series, true),
                HomePageList("Son Eklenen Filmler", movies, true),
            ).filter { it.list.isNotEmpty() }
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim().lowercase()
        if (normalized.length < 2) return emptyList()

        // Doğrudan arama yapamıyorsa ana sayfadan eşleştirme veya arama endpointi kullanımı
        val document = fetchDocument(mainUrl)
        val (movieLinks, episodeLinks) = document.catalogLinks()
        return (movieLinks + episodeLinks)
            .mapNotNull {
                val type = if (it.absUrl("href").contains("/film/")) TvType.Movie else TvType.TvSeries
                it.toSearchResponse(type)
            }
            .filter { it.name.lowercase().contains(normalized) }
            .take(40)
    }

    private fun Element.toSearchResponse(type: TvType): SearchResponse? {
        val url = absUrl("href").ifBlank { attr("href") }
        if (!url.startsWith(mainUrl)) return null
        val image = selectFirst("img")
        val title = image?.attr("alt")?.trim().orEmpty()
            .ifBlank { selectFirst("h4, h3, h2, span")?.text()?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = image?.attr("data-src")?.ifBlank { image.attr("src") }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("/film/") && !url.contains("/dizi/")) return null
        val document = fetchDocument(url)
        val title = document.selectFirst("h1")?.text()?.substringBeforeLast(" izle")
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[alt*=izle]")?.attr("src")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()

        // Video kaynağını içerebilecek player iframe ya da URL'si
        val playerUrl = document.selectFirst("iframe[src]")?.absUrl("src") 
            ?: document.selectFirst("[data-rm-k]")?.text()?.trim() 
            ?: url

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val season = Regex("(\\d+)[.-]?\\s*[Ss]ezon").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episode = Regex("(\\d+)[.-]?\\s*[Bb]ölüm").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            newTvSeriesLoadResponse(
                title.replace(Regex("\\s+\\d+[.-]?\\s*[Ss]ezon.*$"), "").trim(),
                url,
                TvType.TvSeries,
                listOf(newEpisode(playerUrl) {
                    name = title
                    this.season = season
                    this.episode = episode
                    this.posterUrl = poster
                })
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadExtractor(data, mainUrl, subtitleCallback, callback)) return true

        val response = app.get(
            data,
            referer = "$mainUrl/",
            headers = browserHeaders + ("Origin" to mainUrl)
        )
        val body = response.text.replace("\\/", "/").replace("\\u0026", "&")

        // 1. Doğrudan m3u8 veya mp4 bağlantılarını tara
        val streams = Regex("https?://[^\\s\\\"'<>]+?\\.(?:m3u8|mp4)(?:\\?[^\\s\\\"'<>]*)?", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.value }
            .distinct()
            .toList()

        var found = false
        streams.forEachIndexed { index, stream ->
            callback(
                newExtractorLink(name, "$name Kalite ${index + 1}", stream) {
                    referer = data
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            found = true
        }

        if (found) return true

        // 2. Player Token veya Source2 PHP isteği kontrolü
        val playlistToken = Regex("window\\.openPlayer\\(['\"]([^'\"]+)")
            .find(body)?.groupValues?.getOrNull(1)

        if (!playlistToken.isNullOrBlank()) {
            val uri = URI(data)
            val playerOrigin = "${uri.scheme}://${uri.authority}"
            val sourceUrl = "$playerOrigin/source2.php?v=${URLEncoder.encode(playlistToken, "UTF-8")}"
            val sourcePayload = app.get(
                sourceUrl,
                referer = data,
                headers = browserHeaders + ("Origin" to playerOrigin)
            ).text

            runCatching {
                val playlist = JSONObject(sourcePayload).optJSONArray("playlist")
                if (playlist != null) {
                    for (i in 0 until playlist.length()) {
                        val sources = playlist.optJSONObject(i)?.optJSONArray("sources") ?: continue
                        for (j in 0 until sources.length()) {
                            val source = sources.optJSONObject(j) ?: continue
                            val stream = source.optString("file").replace("/m.php?", "/master.m3u8?")
                            if (stream.isNotBlank()) {
                                callback(
                                    newExtractorLink(name, "$name - Otomatik HLS", stream) {
                                        referer = data
                                        type = ExtractorLinkType.M3U8
                                    }
                                )
                                found = true
                            }
                        }
                    }
                }
            }
        }

        return found
    }
}
