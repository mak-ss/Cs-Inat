package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Document

@Suppress("unused")
class NowTv : MainAPI() {
    override var mainUrl = "https://www.now.com.tr"
    override var name = "NOW TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    private val jsonMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class JsonLdItem(
        @JsonProperty("@type") val type: String? = null,
        val name: String? = null,
        val url: String? = null,
        val image: Any? = null,
        val description: String? = null,
        val actor: List<LdActor>? = null,
        val itemListElement: List<LdListItem>? = null
    )

    data class LdActor(val name: String? = null)

    data class LdListItem(val item: LdItemRef? = null)

    data class LdItemRef(
        @JsonProperty("@id") val id: String? = null,
        val name: String? = null,
        val image: Any? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle" to "Diziler",
        "$mainUrl/program-izle" to "Programlar"
    )

    private fun getJsonLd(document: Document): List<JsonLdItem> {
        val jsonLdScripts = document.select("script[type=application/ld+json]")
        val items = mutableListOf<JsonLdItem>()
        for (script in jsonLdScripts) {
            try {
                val json = script.data().trim()
                if (json.startsWith("[")) {
                    items.addAll(jsonMapper.readValue<List<JsonLdItem>>(json))
                } else if (json.startsWith("{")) {
                    items.add(jsonMapper.readValue<JsonLdItem>(json))
                }
            } catch (_: Exception) {
                // Geçersiz JSON-LD bloğunu yoksay
            }
        }
        return items
    }

    private fun extractImageUrl(image: Any?): String? {
        return when (image) {
            is String -> fixImageUrl(image)
            is List<*> -> image.firstOrNull()?.let { extractImageUrl(it) }
            else -> null
        }
    }

    private fun fixImageUrl(image: String?): String? {
        if (image.isNullOrBlank()) return null
        return when {
            image.startsWith("//") -> "https:$image"
            image.startsWith("/") -> "$mainUrl$image"
            else -> image
        }
    }

    private fun mapToSearchResponse(item: JsonLdItem): SearchResponse? {
        val title = item.name ?: return null
        val href = item.url ?: item.itemListElement?.firstOrNull()?.item?.id ?: return null
        val poster = extractImageUrl(item.image)
            ?: extractImageUrl(item.itemListElement?.firstOrNull()?.item?.image)

        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val jsonLdItems = getJsonLd(document)

        val shows = jsonLdItems
            .flatMap { item ->
                val direct = if (item.type == "TVSeries" || item.type == "Movie") listOf(item) else emptyList()
                val fromList = item.itemListElement?.mapNotNull { it.item }?.map {
                    JsonLdItem(
                        type = "TVSeries",
                        name = it.name,
                        url = it.id,
                        image = it.image
                    )
                } ?: emptyList()
                direct + fromList
            }
            .mapNotNull { mapToSearchResponse(it) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, shows)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)

        return jsonLdItems
            .filter { it.type == "TVSeries" || it.type == "Movie" }
            .mapNotNull { mapToSearchResponse(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val jsonLdItems = getJsonLd(document)
        val seriesInfo = jsonLdItems.find { it.type == "TVSeries" } ?: return null

        val title = seriesInfo.name ?: return null
        val description = seriesInfo.description
        val poster = extractImageUrl(seriesInfo.image)

        val episodes = mutableListOf<Episode>()

        document.select("section.videos:contains(BÖLÜMLER) .thumb a[href*='/bolum/'], a[href*='/bolum/']").forEach { element ->
            val epUrl = element.attr("href")
            val epTitle = element.select(".program-name, strong").text().trim()
                .ifBlank { element.text().trim() }
            val epImage = element.select("img").attr("data-src").ifBlank {
                element.select("img").attr("src")
            }

            if (epUrl.isNotEmpty() && epTitle.isNotEmpty() && epTitle.contains("bölüm", ignoreCase = true)) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.posterUrl = fixImageUrl(epImage)
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.plot = description
            this.posterUrl = poster
            this.tags = seriesInfo.actor?.mapNotNull { it.name }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

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

        val jsonLdItems = getJsonLd(document)
        val videoObject = jsonLdItems.find { it.type == "VideoObject" }
        val videoCode = videoObject?.name?.takeIf { it.isNotBlank() }

        if (videoCode != null) {
            val path = data.removePrefix(mainUrl).removePrefix("/").removeSuffix("/")
            val constructedUrl =
                "https://tdywsbbzdx.erbvr.com/$path/${videoCode}.smil/playlist.m3u8"

            M3u8Helper.generateM3u8(
                name,
                constructedUrl,
                data,
                headers = mapOf("Referer" to mainUrl)
            ).forEach(callback)
            return true
        }

        return false
    }
}
