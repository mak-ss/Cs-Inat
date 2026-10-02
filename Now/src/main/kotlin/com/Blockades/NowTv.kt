package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

@Suppress("unused")
class NowTvProvider : MainAPI() {
    override var mainUrl = "https://www.now.com.tr"
    override var name = "NOW TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    // Helper data classes for parsing JSON-LD
    data class JsonLdItem(
        @JsonProperty("@type") val type: String?,
        val name: String?,
        val url: String?,
        val image: String?,
        val description: String?,
        val actor: List<Actor>?,
        val containsSeason: List<Season>?
    )

    data class Actor(val name: String?)
    data class Season(val episode: Episode?)
    data class Episode(val name: String?, val episodeNumber: String?, val url: String?)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/program-izle" to "Programlar",
        "$mainUrl/film-izle" to "Filmler"
    )

    private fun getJsonLd(document: Document): List<JsonLdItem> {
        val jsonLdScripts = document.select("script[type=application/ld+json]")
        val items = mutableListOf<JsonLdItem>()
        for (script in jsonLdScripts) {
            try {
                val json = script.data()
                // JSON-LD can be a single object or an array of objects
                if (json.trim().startsWith("[")) {
                    items.addAll(jsonMapper.readValue<List<JsonLdItem>>(json))
                } else {
                    items.add(jsonMapper.readValue<JsonLdItem>(json))
                }
            } catch (e: Exception) {
                // Ignore invalid JSON-LD blocks
            }
        }
        return items
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val jsonLdItems = getJsonLd(document)

        val shows = jsonLdItems
            .filter { it.type == "TVSeries" || it.type == "Movie" }
            .mapNotNull {
                val title = it.name ?: return@mapNotNull null
                val href = it.url ?: return@mapNotNull null
                val poster = it.image

                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }

        return newHomePageResponse(request.name, shows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)

        return jsonLdItems
            .filter { it.type == "TVSeries" || it.type == "Movie" }
            .mapNotNull {
                val title = it.name ?: return@mapNotNull null
                val href = it.url ?: return@mapNotNull null
                val poster = it.image

                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)
        val seriesInfo = jsonLdItems.find { it.type == "TVSeries" } ?: return null

        val title = seriesInfo.name ?: return null
        val description = seriesInfo.description
        val poster = seriesInfo.image

        val episodes = mutableListOf<Episode>()

        // Episodes are not in the main JSON-LD, so we scrape them from the "BÖLÜMLER" section
        document.select("section.videos:contains(BÖLÜMLER) .thumb, .list-item").forEach { element ->
            val epUrl = element.select("a").attr("href")
            val epTitle = element.select(".program-name, .list-item-meta strong").text().trim()
            val epImage = element.select("img").attr("data-src")

            if (epUrl.isNotEmpty() && epTitle.isNotEmpty()) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.posterUrl = fixImageUrl(epImage)
                    }
                )
            }
        }

        // If no episodes found from the specific section, try finding all episode links
        if (episodes.isEmpty()) {
             document.select("a[href*='/bolum/']").forEach { element ->
                val epUrl = element.attr("href")
                val epTitle = element.text().trim()
                if (epUrl.isNotEmpty() && epTitle.isNotEmpty() && epTitle.contains("Bölüm", ignoreCase = true)) {
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.posterUrl = fixImageUrl(element.select("img").attr("data-src"))
                        }
                    )
                }
            }
        }


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.plot = description
            this.posterUrl = fixImageUrl(poster)
            this.tags = seriesInfo.actor?.mapNotNull { it.name }
        }
    }

    private fun fixImageUrl(image: String?): String? {
        if (image.isNullOrBlank()) return null
        return if (image.startsWith("//")) {
            "https:$image"
        } else if (image.startsWith("/")) {
            "$mainUrl$image"
        } else {
            image
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val jsonLdItems = getJsonLd(document)
        val videoObject = jsonLdItems.find { it.type == "VideoObject" }
        // The video_code is the key to build the final m3u8 URL
        val videoCode = videoObject?.name?.takeIf { it.isNotBlank() } ?: data.substringAfterLast("/")

        // Try to find the video source directly first
        val directVideoUrl = document.selectFirst("video source")?.attr("src")

        if (!directVideoUrl.isNullOrBlank() && directVideoUrl.contains(".m3u8")) {
             M3u8Helper.generateM3u8(
                name,
                directVideoUrl,
                data,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }
        
        // Fallback to the dynamic URL construction based on the provided m3u8 link structure
        // This part is fragile as the CDN domain might change.
        val cdnBaseUrl = "https://tdywsbbzdx.erbvr.com"
        val constructedUrl = "$cdnBaseUrl/Omur-Usta/bolumler/1/${videoCode}.smil/playlist.m3u8"
        
        // Note: This constructed URL is a guess and might not work for all videos or in the future.
        // A more robust solution would require reverse-engineering the /ajax/stream API call.
        M3u8Helper.generateM3u8(
            name,
            constructedUrl,
            data,
            headers = mapOf("Referer" to mainUrl)
        ).forEach(callback)


        return true
    }
}