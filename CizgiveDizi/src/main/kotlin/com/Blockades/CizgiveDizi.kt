// CizgiveDizi.kt
package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class CizgiveDizi : MainAPI() {

    override var mainUrl = "https://cizgivedizi.com"
    override var name = "CizgiveDizi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    // Ana sayfa bölümleri
    override val mainPage = mainPageOf(
        "cizgi" to "Çizgi Diziler",
        "anime" to "Animeler",
        "dizi" to "Diziler",
        "film" to "Filmler"
    )

    // Ana sayfa içeriğini yükle
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) "$mainUrl/" else "$mainUrl/?page=$page"
        val document = app.get(url).document

        // HTML'deki poolData JSON'unu al
        val poolDataScript = document.selectFirst("script#poolData")?.data()
        
        val homePageList = mutableListOf<HomePageList>()

        if (poolDataScript != null) {
            // JSON'u parse et
            val items = parsePoolData(poolDataScript)
            
            // Türe göre filtrele
            val filteredItems = when (request.data) {
                "cizgi" -> items.filter { it.type == "cizgi" }
                "anime" -> items.filter { it.type == "anime" }
                "dizi" -> items.filter { it.type == "dizi" }
                "film" -> items.filter { it.type == "film" }
                else -> items
            }

            if (filteredItems.isNotEmpty()) {
                homePageList.add(
                    HomePageList(
                        request.name,
                        filteredItems.map { it.toSearchResponse() }
                    )
                )
            }
        } else {
            // Fallback: HTML'den direkt çek
            val items = document.select("a.item").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, items))
            }
        }

        return newHomePageResponse(homePageList, hasNext = true)
    }

    // Arama fonksiyonu
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document
        
        // Arama sonuçlarını HTML'den al
        val results = document.select("a.item").mapNotNull { it.toSearchResponse() }
        
        // Eğer HTML'de sonuç yoksa, poolData'dan ara
        if (results.isEmpty()) {
            val poolDataScript = document.selectFirst("script#poolData")?.data()
            if (poolDataScript != null) {
                val items = parsePoolData(poolDataScript)
                return items.filter { 
                    it.name.contains(query, ignoreCase = true) 
                }.map { it.toSearchResponse() }
            }
        }
        
        return results
    }

    // Detay yükleme fonksiyonu
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Bilinmiyor"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.poster-img")?.attr("src")

        val description = document.selectFirst("meta[name=description]")?.attr("content")

        val genres = document.select("div.genres a").map { it.text() }

        // Bölümleri veya film linklerini al
        val episodes = mutableListOf<Episode>()

        // HTML'de bölüm listesi yapısını kontrol edin
        document.select("div.bolum-list a, div.episode-list a, a.bolum").forEach { element ->
            val episodeName = element.text()
            val episodeUrl = element.attr("href")
            if (episodeUrl.isNotEmpty()) {
                episodes.add(
                    newEpisode("$mainUrl$episodeUrl") {
                        this.name = episodeName
                    }
                )
            }
        }

        // Eğer bölüm yoksa, tek film olarak ekle
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "İzle"
                }
            )
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
        }
    }

    // Video linklerini çıkarma - DÜZELTİLDİ
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // HTML'deki video kaynaklarını bul
        document.select("iframe, video source, a.video-link").forEach { element ->
            val videoUrl = when {
                element.tagName() == "iframe" -> element.attr("src")
                element.tagName() == "source" -> element.attr("src")
                else -> element.attr("href")
            }

            if (videoUrl.isNotEmpty()) {
                // --- DÜZELTME: newExtractorLink ve ExtractorLinkType kullanımı ---
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = ExtractorLink.QUALITY.HD
                    }
                )
            }
        }

        return true
    }

    // HTML Element'ini SearchResponse'a çevir
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isEmpty()) return null

        val title = this.attr("data-name").ifEmpty { 
            this.selectFirst("p.title")?.text() ?: return null 
        }
        
        val poster = this.attr("data-poster").ifEmpty {
            this.selectFirst("img.poster-img")?.attr("src")
        }

        val type = this.attr("data-type")
        val tvType = when (type) {
            "film" -> TvType.Movie
            "dizi" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "cizgi" -> TvType.Cartoon
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, "$mainUrl$href", tvType) {
            this.posterUrl = poster
        }
    }

    // poolData JSON'unu parse et
    private fun parsePoolData(jsonString: String): List<PoolItem> {
        val items = mutableListOf<PoolItem>()
        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val dataset = obj.optJSONObject("dataset")
                if (dataset != null) {
                    items.add(
                        PoolItem(
                            name = dataset.optString("name", ""),
                            href = obj.optString("href", ""),
                            poster = obj.optString("poster", ""),
                            type = dataset.optString("type", "cizgi")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }
}

// --- DÜZELTME: PoolItem sınıfı artık CizgiveDizi sınıfının içinde değil, dışında (static) ---
data class PoolItem(
    val name: String,
    val href: String,
    val poster: String,
    val type: String
) {
    fun toSearchResponse(): SearchResponse {
        val tvType = when (type) {
            "film" -> TvType.Movie
            "dizi" -> TvType.TvSeries
            "anime" -> TvType.Anime
            "cizgi" -> TvType.Cartoon
            else -> TvType.Movie
        }
        return newMovieSearchResponse(name, "https://cizgivedizi.com$href", tvType) {
            this.posterUrl = poster
        }
    }
}
