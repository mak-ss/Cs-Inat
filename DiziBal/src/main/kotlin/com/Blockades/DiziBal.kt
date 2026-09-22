package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl              = "https://dizibal.org"
    override var name                 = "DiziBal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        Log.d(name, "getMainPage URL: $url")

        return try {
            val document = app.get(url).document
            // Ana sayfa ve liste sayfalarındaki tüm kartlar a.group.block yapısında
            val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
            Log.d(name, "getMainPage: ${items.size} öğe bulundu")

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e(name, "getMainPage hatası: ${e.message}", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"
        Log.d(name, "search URL: $url")

        return try {
            val document = app.get(url).document
            // Arama sonuç sayfasındaki tüm kartlar a.group.block yapısında
            val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
            Log.d(name, "search: ${items.size} sonuç bulundu")
            items
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load URL: $url")

        return try {
            val document = app.get(url).document

            // Türü URL'den belirle
            val type = when {
                url.contains("/movie/") -> TvType.Movie
                url.contains("/series/") -> TvType.TvSeries
                url.contains("/anime/") -> TvType.Anime
                else -> return null
            }

            // Başlık
            val title = document.selectFirst("h1.font-display")?.text()?.trim()
                ?: return null

            // Poster
            val poster = document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")

            // Özet
            val plot = document.selectFirst("p.whitespace-pre-line")?.text()?.trim()

            // Yıl - "Yapım Yılı" satırındaki dd elementinden
            val year = document.select("div:contains(Yapım Yılı) dd")
                .firstOrNull()?.text()?.trim()?.toIntOrNull()

            // Puan - "IMDB Puanı" satırındaki dd elementinden
            val score = document.select("div:contains(IMDB Puanı) dd")
                .firstOrNull()?.text()?.replace("★", "")?.trim()?.toDoubleOrNull()

            // Etiketler (Türler)
            val tags = document.select("div.flex.flex-wrap.gap-2 a.rounded-badge")
                .map { it.text().trim() }

            // Oyuncular
            val actors = document.select("section#cast-heading + div a.group").mapNotNull {
                val name = it.selectFirst("p.text-\\[13px\\]")?.text()?.trim() ?: return@mapNotNull null
                val image = it.selectFirst("img")?.attr("src")
                Actor(name, image)
            }

            // Fragman - YouTube embed URL'si sayfada bir yerde olabilir
            val trailer = document.selectFirst("iframe[src*=youtube]")?.attr("src")

            when (type) {
                TvType.Movie -> {
                    // Film için stream URL'si, film sayfasının kendisidir.
                    // loadLinks içinde data-pv aranacak.
                    newMovieLoadResponse(title, url, type, url) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { Score.from10(it.toString()) }
                        addActors(actors)
                        if (trailer != null) addTrailer(trailer)
                    }
                }
                else -> {
                    // Dizi veya Anime - bölümleri topla
                    val episodes = mutableListOf<Episode>()

                    // Sayfadaki tüm bölüm linklerini al
                    document.select("div#bolumler a.group").forEach { epElement ->
                        val epUrl = epElement.attr("href")
                        val epName = epElement.selectFirst("p.text-sm")?.text()?.trim()
                        val epInfo = epElement.selectFirst("p.text-xs")?.text()?.trim()
                        val (seasonNum, episodeNum) = parseEpisodeInfo(epInfo)

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = episodeNum
                        })
                    }

                    Log.d(name, "load: ${episodes.size} bölüm bulundu")

                    newTvSeriesLoadResponse(title, url, type, episodes) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = tags
                        this.score = score?.let { Score.from10(it.toString()) }
                        addActors(actors)
                        if (trailer != null) addTrailer(trailer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "load hatası: ${e.message}", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks URL: $data")

        return try {
            val document = app.get(data).document

            // data-pv attribute'una sahip elementi bul
            val playerElement = document.selectFirst("[data-pv]")
            val playerId = playerElement?.attr("data-pv")

            if (playerId.isNullOrEmpty()) {
                Log.e(name, "Player ID bulunamadı.")
                return false
            }

            Log.d(name, "Player ID: $playerId")

            // pilavyerplay.top adresinden stream URL'sini al
            // Bu kısım sitenin gerçek player API'sine göre uyarlanmalı.
            // Şimdilik, player ID'sini kullanarak bir embed URL'si oluşturuyoruz.
            val embedUrl = "https://pilavyerplay.top/embed/$playerId"

            Log.d(name, "Embed URL: $embedUrl")

            // Embed sayfasını çek ve gerçek stream URL'sini bul
            val embedDoc = app.get(embedUrl, referer = data).document
            val streamUrl = embedDoc.selectFirst("video source")?.attr("src")
                ?: embedDoc.selectFirst("video")?.attr("src")

            if (!streamUrl.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }

            // Eğer doğrudan video elementi yoksa, iframe ara
            val iframe = embedDoc.selectFirst("iframe")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = iframeUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embedUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            }

            Log.e(name, "Stream URL'si bulunamadı.")
            false
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}", e)
            false
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        val type = when {
            href.contains("/movie/") -> TvType.Movie
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            else -> return null
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun parseEpisodeInfo(info: String?): Pair<Int?, Int?> {
        if (info == null) return null to null
        // Örnek: "1. Sezon 1. Bölüm"
        val regex = Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""")
        val match = regex.find(info)
        return if (match != null) {
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            season to episode
        } else {
            null to null
        }
    }
}
