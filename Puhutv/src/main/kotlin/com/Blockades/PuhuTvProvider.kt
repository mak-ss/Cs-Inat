package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import org.json.JSONObject

// PuhuTV'nin Next.js veri yapıları için basit data class'lar
data class PuhuTvPageData(
    val props: PuhuTvProps
)
data class PuhuTvProps(
    val pageProps: PuhuTvPageProps
)
data class PuhuTvPageProps(
    val details: PuhuTvDetails?,
    val watchDetails: PuhuTvWatchDetails?,
    val episodes: PuhuTvEpisodeData?,
)
data class PuhuTvDetails(
    val `data`: PuhuTvSeriesData?
)
data class PuhuTvWatchDetails(
    val `data`: PuhuTvSeriesData?
)
data class PuhuTvEpisodeData(
    val `data`: PuhuTvSeasonData?
)
data class PuhuTvSeriesData(
    val name: String?,
    val image: String?,
    val meta: PuhuTvMeta?
)
data class PuhuTvSeasonData(
    val episodes: List<PuhuTvEpisode>?
)
data class PuhuTvEpisode(
    val name: String?,
    val slug: String?,
    val `type`: String?,
    val meta: PuhuTvEpisodeMeta?
)
data class PuhuTvMeta(
    val description: String?,
    val short_description: String?
)
data class PuhuTvEpisodeMeta(
    val position: Int?,
    val season_number: Int?,
    val short_description: String?
)


class PuhuTvProvider : MainAPI() {

    override var mainUrl = "https://puhutv.com"
    override var name = "PuhuTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Documentary
    )

    override val mainPage = mainPageOf(
        "list/anasayfa-one-cikanlar" to "Öne Çıkanlar",
        "list/en-yeni-dizi-bolumleri" to "Yeni Bölümler",
        "list/orijinal" to "puhutv Orijinal",
        "list/anasayfa-uzak-dogu-ruzgari" to "Uzak Doğu Rüzgarı",
        "list/komedi-dizileri" to "Romantik & Komik",
        "list/belgesel-yapimlar" to "Belgesel",
        "list/anasayfa-kultur-sahnesi" to "Kültür Sahnesi",
    )

    private fun cleanUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun posterOf(element: Element): String? {
        val img = if (element.tagName() == "img") element else element.selectFirst("img") ?: return null
        val attributes = listOf("data-src", "data-original", "data-lazy-src", "data-image", "src")
        for (attribute in attributes) {
            val value = img.attr(attribute).trim()
            if (value.isNotEmpty() && !value.startsWith("data:image")) {
                return cleanUrl(value)
            }
        }
        val srcSet = img.attr("srcset").trim()
        if (srcSet.isNotEmpty()) {
            val bestImage = srcSet.split(",").map { it.trim() }.lastOrNull()?.split(" ")?.firstOrNull()
            if (!bestImage.isNullOrEmpty()) return cleanUrl(bestImage)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}"
        val document = try {
            app.get(url, headers = PuhuTvExtractor.headers).document
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }

        val homeItems = mutableListOf<HomePageList>()

        val cards = document.select("div.swiper-slide > div[id] > div > a, div.swiper-slide > div[id] > a")

        val items = cards.mapNotNull { card ->
            val href = card.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val title = card.selectFirst("span.content-name")?.text()?.trim()
                ?: card.selectFirst("img")?.attr("alt")?.trim()

            if (title.isNullOrEmpty()) return@mapNotNull null

            val poster = posterOf(card)

            newTvSeriesSearchResponse(title, cleanUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        if (items.isNotEmpty()) {
            homeItems.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery"
        val document = try {
            app.get(searchUrl, headers = PuhuTvExtractor.headers).document
        } catch (_: Exception) {
            return emptyList()
        }
        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        document.select("div.hqfabl, div.cHPtuV, article, .content-card, a[href*='/detay']").forEach { element ->
            val anchor = if (element.tagName() == "a") element else element.selectFirst("a")
            val href = anchor?.attr("href")?.trim()
            if (href.isNullOrEmpty()) return@forEach

            val fullUrl = cleanUrl(href)
            if (!seen.add(fullUrl)) return@forEach

            val title = element.selectFirst(".content-name, h3, p, .title")?.text()?.trim() ?: anchor.text().trim()
            if (title.isEmpty()) return@forEach

            results.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterOf(element)
            })
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = PuhuTvExtractor.headers).document
        } catch (_: Exception) {
            return null
        }

        val nextDataJson = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null

        val pageData = try {
            parseJson<PuhuTvPageData>(nextDataJson)
        } catch (_: Exception) {
            return null
        }

        // Detay sayfası mı yoksa izleme sayfası mı olduğunu anlıyoruz
        val seriesData = pageData.props.pageProps.details?.data ?: pageData.props.pageProps.watchDetails?.data
            ?: return null

        val title = seriesData.name ?: document.selectFirst("title")?.text()?.substringBefore(" |") ?: "Bilinmeyen Başlık"
        val poster = seriesData.image
        val plot = seriesData.meta?.description ?: seriesData.meta?.short_description

        val episodes = mutableListOf<Episode>()

        // Bölüm listesini Next.js verisinden alıyoruz
        pageData.props.pageProps.episodes?.data?.episodes?.forEach { episode ->
            val episodeUrl = cleanUrl(episode.slug ?: "")
            if (episodeUrl.isNotBlank()) {
                val episodeNumber = episode.meta?.position ?: 1
                val seasonNumber = episode.meta?.season_number ?: 1

                episodes.add(newEpisode(episodeUrl) {
                    this.name = episode.name
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = poster
                    this.description = episode.meta?.short_description
                })
            }
        }

        // Eğer hiç bölüm bulunamazsa ve bu bir filmse, film olarak döndür
        if (episodes.isEmpty() && seriesData.meta?.description != null) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        episodes.sortWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        PuhuTvExtractor().getUrl(
            url = data,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }
}
