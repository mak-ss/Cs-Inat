package com.UmayTrade

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Element

@Suppress("unused")
class StarTv : MainAPI() {
    override var mainUrl = "https://www.startv.com.tr"
    override var name = "Star TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val posterBaseUrl = "https://media.startv.com.tr"
    private val mapper = ObjectMapper()

    override val mainPage = mainPageOf(
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/program" to "Programlar"
    )

    private fun getNextData(document: org.jsoup.nodes.Document): JsonNode? {
        val script = document.selectFirst("script#__NEXT_DATA__") ?: return null
        return try {
            mapper.readTree(script.data())
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    /** __NEXT_DATA__ içinde verilen isimdeki ilk array'i bulur (recursive). */
    private fun findArrayByName(node: JsonNode?, name: String): JsonNode? {
        if (node == null || node.isMissingNode) return null
        if (node.isObject) {
            val field = node.get(name)
            if (field != null && field.isArray) return field
            node.fields().forEach { (_, v) ->
                findArrayByName(v, name)?.let { return it }
            }
        } else if (node.isArray) {
            for (child in node) {
                findArrayByName(child, name)?.let { return it }
            }
        }
        return null
    }

    /** Bir item'dan başlık, url, poster çıkarır. Farklı key isimlerini dener. */
    private fun parseItem(item: JsonNode): Triple<String, String, String?>? {
        val title = listOf("name", "title", "seriesName")
            .firstNotNullOfOrNull { item.path(it).asText().takeIf { s -> s.isNotBlank() } }
            ?: return null

        val href = listOf("url", "slug", "path", "link")
            .firstNotNullOfOrNull { item.path(it).asText().takeIf { s -> s.isNotBlank() } }
            ?: return null

        val posterPath = listOf("poster", "image", "thumbnail")
            .firstNotNullOfOrNull { key ->
                val n = item.path(key)
                when {
                    n.isTextual -> n.asText()
                    n.isObject -> n.path("fullPath").asText()
                        .ifBlank { n.path("url").asText() }
                        .ifBlank { n.path("path").asText() }
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }

        val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
        val poster = posterPath?.let {
            if (it.startsWith("http")) it else "$posterBaseUrl$it"
        }
        return Triple(title, fullHref, poster)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        // 1) Önce __NEXT_DATA__ içinde dizi/program array'i ara
        val nextData = getNextData(document)
        val candidates = listOf("items", "contents", "series", "programs", "list", "data")
        var items: JsonNode? = null
        for (c in candidates) {
            items = findArrayByName(nextData, c)
            if (items != null && items.size() > 0) break
        }

        val shows = mutableListOf<SearchResponse>()
        if (items != null && items.isArray) {
            for (item in items) {
                val parsed = parseItem(item) ?: continue
                val (title, href, poster) = parsed
                shows.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }

        // 2) Fallback: HTML'den linkleri topla
        if (shows.isEmpty()) {
            val selector = "a[href*=/dizi/], a[href*=/program/], a[href*=/video/]"
            document.select(selector).forEach { a ->
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                if (href.endsWith("/dizi") || href.endsWith("/program")) return@forEach
                val title = a.selectFirst("h2, h3, h4, [class*=title], [class*=Title]")
                    ?.text()?.trim()
                    ?: a.attr("title").takeIf { it.isNotBlank() }
                    ?: return@forEach
                val poster = a.selectFirst("img")?.let { img ->
                    (img.attr("src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() })
                }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

                shows.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }

        return newHomePageResponse(request.name, shows.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val doc = app.get(url).document
        val out = mutableListOf<SearchResponse>()

        val nextData = getNextData(doc)
        val items = findArrayByName(nextData, "items")
            ?: findArrayByName(nextData, "results")
        if (items != null) {
            for (item in items) {
                val p = parseItem(item) ?: continue
                out.add(newTvSeriesSearchResponse(p.first, p.second, TvType.TvSeries) {
                    this.posterUrl = p.third
                })
            }
        }
        if (out.isEmpty()) {
            doc.select("a[href*=/dizi/], a[href*=/program/]").forEach { a ->
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val title = a.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
            }
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val nextData = getNextData(document)

        val seriesData = nextData?.path("props")?.path("pageProps")?.path("data")

        val title = seriesData?.path("name")?.asText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val description = seriesData?.path("summary")?.asText()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val posterPath = seriesData?.path("poster")?.path("fullPath")?.asText()?.takeIf { it.isNotBlank() }
        val poster = posterPath?.let { if (it.startsWith("http")) it else "$posterBaseUrl$it" }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = mutableListOf<Episode>()

        // __NEXT_DATA__ içinden bölümleri topla
        val sections = seriesData?.path("sections")
        if (sections != null && sections.isArray) {
            for (section in sections) {
                val items = section.path("items")
                if (!items.isArray) continue
                for (item in items) {
                    val type = item.path("resourceType").asText()
                    if (type.equals("Episode", true) || type.equals("Video", true)) {
                        val id = item.path("_id").asText().takeIf { it.isNotBlank() } ?: continue
                        val epTitle = item.path("name").asText().takeIf { it.isNotBlank() } ?: "Bölüm"
                        val epUrl = item.path("url").asText().takeIf { it.isNotBlank() }
                            ?: "$mainUrl/video/$id"
                        val epPosterPath = item.path("poster").path("fullPath").asText()
                        val epPoster = if (epPosterPath.isNotBlank()) "$posterBaseUrl$epPosterPath" else poster
                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.posterUrl = epPoster
                        })
                    }
                }
            }
        }

        // Fallback: HTML'den bölüm linkleri
        if (episodes.isEmpty()) {
            document.select("a[href*=/video/], a[href*=/bolum], a[href*=/izle]").forEach { a ->
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }
                val name = a.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                episodes.add(newEpisode(href) { this.name = name; this.posterUrl = poster })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val referer = mainUrl
        var found = false

        // 1) __NEXT_DATA__ içinden m3u8 / video url ara
        val nd = getNextData(document)
        val urls = mutableSetOf<String>()

        fun walk(node: JsonNode?) {
            if (node == null) return
            when {
                node.isTextual -> {
                    val t = node.asText()
                    if (t.contains(".m3u8") || t.contains(".mp4")) urls.add(t)
                }
                node.isObject -> node.fields().forEach { (_, v) -> walk(v) }
                node.isArray -> node.forEach { walk(it) }
            }
        }
        walk(nd)

        // 2) HTML fallback
        document.select("video source, source[src], video[src], iframe[src]").forEach { el ->
            val src = el.attr("src").takeIf { it.isNotBlank() }
                ?: el.attr("data-src").takeIf { it.isNotBlank() }
            if (src != null) urls.add(src)
        }

        // 3) <script> içindeki m3u8 geçen yerleri tara
        val regex = Regex("""https?://[^\s"'\\]+\.(m3u8|mp4)[^\s"'\\]*""")
        document.select("script").forEach { s ->
            regex.findAll(s.data()).forEach { urls.add(it.value) }
        }

        for (u in urls) {
            val full = if (u.startsWith("http")) u else "$mainUrl$u"
            if (full.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    full,
                    referer = data,
                    headers = mapOf("Referer" to referer)
                ).forEach { callback(it); found = true }
            } else if (full.contains(".mp4")) {
                callback(newExtractorLink(name, name, full) {
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer)
                })
                found = true
            }
        }

        return found
    }
}
