package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

@Suppress("unused")
class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val posterBaseUrl = "https://media.startv.com.tr"

    override val mainPage = mainPageOf(
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/program" to "Programlar"
    )

    // __NEXT_DATA__ JSON'unu çeken yardımcı fonksiyon
    private fun getNextData(document: org.jsoup.nodes.Document): JsonNode? {
        val nextDataScript = document.selectFirst("script#__NEXT_DATA__") ?: return null
        return try {
            ObjectMapper().readTree(nextDataScript.data())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val nextData = getNextData(document) ?: return newHomePageResponse(request.name, emptyList())

        // JSON içindeki dizi/program listesini bul
        val items = nextData.path("props").path("pageProps").path("data").path("items")
        val shows = items.mapNotNull { item ->
            val title = item.path("name").asText()
            val href = item.path("url").asText()
            val posterPath = item.path("poster").path("fullPath").asText()
            val poster = if (posterPath.isNotEmpty()) "$posterBaseUrl$posterPath" else null

            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, shows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama fonksiyonu için site içi arama sayfasının da benzer bir yapıda olduğunu varsayıyoruz.
        // Gerekirse bu kısım da __NEXT_DATA__ kullanacak şekilde güncellenebilir.
        val url = "$mainUrl/ara?q=$query"
        val document = app.get(url).document
        val nextData = getNextData(document) ?: return emptyList()
        
        // Arama sonuçları sayfasındaki JSON yapısı farklı olabilir, kontrol edilmeli.
        // Örnek olarak "items" yolunu kullanıyoruz.
        val items = nextData.path("props").path("pageProps").path("data").path("items")
        return items.mapNotNull { item ->
            val title = item.path("name").asText()
            val href = item.path("url").asText()
            val posterPath = item.path("poster").path("fullPath").asText()
            val poster = if (posterPath.isNotEmpty()) "$posterBaseUrl$posterPath" else null

            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val nextData = getNextData(document) ?: return null

        // Dizi detay sayfasındaki JSON yapısı
        val seriesData = nextData.path("props").path("pageProps").path("data")
        
        val title = seriesData.path("name").asText()
        val description = seriesData.path("summary").asText() // HTML içerebilir, temizlenmeli
        val posterPath = seriesData.path("poster").path("fullPath").asText()
        val poster = if (posterPath.isNotEmpty()) "$posterBaseUrl$posterPath" else null

        val episodes = mutableListOf<Episode>()
        // Bölüm listesi JSON içinde "sections" veya benzeri bir alanda olabilir.
        // Bu kısım, dizi sayfasının gerçek JSON yapısına göre uyarlanmalıdır.
        // Örnek olarak, "sections" altındaki "items"ları tarıyoruz.
        val sections = seriesData.path("sections")
        if (sections.isArray) {
            for (section in sections) {
                val items = section.path("items")
                if (items.isArray) {
                    for (item in items) {
                        if (item.path("resourceType").asText() == "Episode") {
                            // Bölüm detaylarına ulaşmak için ekstra bir istek gerekebilir.
                            // Şimdilik sadece ID'yi alıp bir placeholder oluşturuyoruz.
                            val episodeId = item.path("_id").asText()
                            val episodeTitle = "Bölüm" // JSON'dan başlık çekilebilir
                            episodes.add(
                                newEpisode("$mainUrl/video/$episodeId") { // Varsayımsal bir URL
                                    name = episodeTitle
                                    posterUrl = poster
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Eğer bölümler yukarıdaki gibi bulunamazsa, alternatif bir yol denenebilir.
        // Örneğin, doğrudan bir "episodes" dizisi olup olmadığına bakılabilir.

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            plot = description
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Video linklerini çekmek için de benzer şekilde __NEXT_DATA__ veya bir API kullanılması gerekebilir.
        // Bu kısım, video sayfasının yapısına göre tamamen yeniden yazılmalıdır.
        // Şimdilik mevcut yapıyı koruyoruz, ancak çalışmayabilir.
        val document = app.get(data).document
        val videoUrl = document.select("video source").attr("src")
        val referer = mainUrl

        if (videoUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                videoUrl,
                data,
                headers = mapOf("Referer" to referer)
            ).forEach { callback(it) }
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl
                ) {
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer)
                }
            )
        }
        return true
    }
}
