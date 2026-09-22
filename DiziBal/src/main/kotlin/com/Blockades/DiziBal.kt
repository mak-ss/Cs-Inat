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
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/animes" to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        Log.d(name, "getMainPage: $url")
        val document = app.get(url).document

        val items = document.select("a.group.block").mapNotNull { it.toSearchResponse() }
        val hasNext = document.select("a[rel=next]").isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"
        Log.d(name, "search: $url")
        val document = app.get(url).document

        // Arama sonuçları "Filmler" ve "Diziler" olarak gruplanmış.
        // Tüm sonuçları tek listede topluyoruz.
        return document.select("a.group.block").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load: $url")
        val document = app.get(url).document

        // URL'den türü belirle
        val type = when {
            url.contains("/movie/") -> TvType.Movie
            url.contains("/series/") -> TvType.TvSeries
            url.contains("/anime/") -> TvType.Anime
            else -> return null
        }

        val title = document.selectFirst("h1.font-display")?.text()?.trim() ?: return null
        val poster = document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
        val plot = document.selectFirst("p.whitespace-pre-line")?.text()?.trim()
        val year = document.selectFirst("div:contains(Yapım Yılı) dd")?.text()?.trim()?.toIntOrNull()
        val tags = document.select("div.flex.flex-wrap.gap-2 a.rounded-badge").map { it.text().trim() }

        val score = document.selectFirst("div:contains(IMDB Puanı) dd")?.text()
            ?.replace("★", "")?.trim()?.toDoubleOrNull()

        val trailer = document.selectFirst("button:contains(Fragmanı İzle)")?.let { button ->
            // Fragman butonuna tıklandığında açılan YouTube linkini bulmak için
            // sayfada bir iframe veya data attribute aramamız gerekebilir.
            // Şimdilik null bırakıyoruz.
            null
        }

        val actors = document.select("section#cast-heading + div a.group").mapNotNull {
            val name = it.selectFirst("p.text-\\[13px\\]")?.text()?.trim() ?: return@mapNotNull null
            val image = it.selectFirst("img")?.attr("src")
            Actor(name, image)
        }

        return if (type == TvType.Movie) {
            val streamUrl = url // Filmler için doğrudan sayfa URL'sini kullanacağız.
            newMovieLoadResponse(title, url, type, streamUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it.toString()) }
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            // Dizi veya Anime
            val episodes = mutableListOf<Episode>()

            // Sezonları bul
            val seasons = document.select("div#bolumler a[href*='?sezon=']").map { it.attr("href") }

            // Eğer sezon linki yoksa, doğrudan bölümleri al (tek sezonlu yapımlar için)
            if (seasons.isEmpty()) {
                document.select("div#bolumler a.group").forEach { epElement ->
                    val epUrl = epElement.attr("href")
                    val epName = epElement.selectFirst("p.text-sm")?.text()?.trim()
                    val epInfo = epElement.selectFirst("p.text-xs")?.text()?.trim() // "1. Sezon 1. Bölüm"
                    val (seasonNum, episodeNum) = parseEpisodeInfo(epInfo)

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = episodeNum
                    })
                }
            } else {
                // Her sezon için bölümleri çek
                for (seasonUrl in seasons) {
                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select("div#bolumler a.group").forEach { epElement ->
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
                }
            }

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = score?.let { Score.from10(it.toString()) }
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks: $data")
        val document = app.get(data).document

        // data-pv attribute'una sahip elementi bul
        val playerElement = document.selectFirst("[data-pv]")
        val playerId = playerElement?.attr("data-pv")

        if (playerId.isNullOrEmpty()) {
            Log.e(name, "Player ID bulunamadı.")
            return false
        }

        // Burada pilavyerplay.top domain'ine bir istek atıp gerçek stream URL'sini almamız gerekiyor.
        // Ancak bu, sitenin kendi oynatıcısının nasıl çalıştığına bağlı.
        // Şimdilik, doğrudan bir embed linki oluşturmayı deneyelim.
        // Bu kısım, sitenin gerçek oynatıcı yapısına göre uyarlanmalıdır.
        // Örnek olarak, playerId'yi kullanarak bir embed URL'si oluşturuyoruz.
        // Bu kısım muhtemelen çalışmayacaktır ve sitenin gerçek player API'sini
        // incelemek gerekecektir.

        // Geçici olarak, sayfadaki iframe'i arayalım.
        val iframe = document.selectFirst("iframe")
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
                        this.referer = data
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        Log.e(name, "Oynatıcı linki bulunamadı.")
        return false
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
