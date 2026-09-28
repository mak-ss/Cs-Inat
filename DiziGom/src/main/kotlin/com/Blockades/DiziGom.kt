package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.icu"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Site yapısı: tek liste sayfası /dizi-izle/ + ?tur=X query filtresi
    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle/" to "Tüm Diziler",
        "$mainUrl/dizi-izle/?tur=Aksiyon" to "Aksiyon",
        "$mainUrl/dizi-izle/?tur=Animasyon" to "Animasyon",
        "$mainUrl/dizi-izle/?tur=Belgesel" to "Belgesel",
        "$mainUrl/dizi-izle/?tur=Bilim Kurgu" to "Bilim Kurgu",
        "$mainUrl/dizi-izle/?tur=Biyografi" to "Biyografi",
        "$mainUrl/dizi-izle/?tur=Dram" to "Dram",
        "$mainUrl/dizi-izle/?tur=Fantastik" to "Fantastik",
        "$mainUrl/dizi-izle/?tur=Gençlik" to "Gençlik",
        "$mainUrl/dizi-izle/?tur=Gerilim" to "Gerilim",
        "$mainUrl/dizi-izle/?tur=Gizem" to "Gizem",
        "$mainUrl/dizi-izle/?tur=Komedi" to "Komedi",
        "$mainUrl/dizi-izle/?tur=Korku" to "Korku",
        "$mainUrl/dizi-izle/?tur=Macera" to "Macera",
        "$mainUrl/dizi-izle/?tur=Polisiye" to "Polisiye",
        "$mainUrl/dizi-izle/?tur=Romantik" to "Romantik",
        "$mainUrl/dizi-izle/?tur=Savaş" to "Savaş",
        "$mainUrl/dizi-izle/?tur=Suç" to "Suç",
        "$mainUrl/dizi-izle/?tur=Tarih" to "Tarih"
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { fixUrlNull(it) }

    private fun isPlaceholderImage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase().trim()
        if (lower.startsWith("data:image")) return true
        val fileName = lower.substringAfterLast("/").substringBefore("?")
        if (fileName == "lazy.png" || fileName == "lazy.gif" || fileName == "lazy.jpg") return true
        if (fileName == "blank.gif" || fileName == "blank.png") return true
        if (fileName == "placeholder.png" || fileName == "placeholder.jpg") return true
        if (fileName == "loading.gif" || fileName == "spinner.gif") return true
        if (lower.contains("/images/lazy")) return true
        if (lower.contains("/images/blank")) return true
        if (lower.contains("/images/placeholder")) return true
        if (lower.contains("/images/loading")) return true
        return false
    }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        if (style.isBlank()) return null
        val match = Regex("url\\(['\"]?([^'\"\\)]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img")
        val imgCandidates = listOfNotNull(
            img?.attr("data-src"),
            img?.attr("data-lazy-src"),
            img?.attr("data-original"),
            img?.attr("data-image"),
            img?.attr("data-srcset")?.substringBefore(",")?.substringBefore(" ")?.trim(),
            img?.attr("srcset")?.substringBefore(",")?.substringBefore(" ")?.trim(),
            img?.attr("src")
        )
        val elementCandidates = listOfNotNull(
            attr("data-poster"),
            attr("data-bg"),
            attr("data-background"),
            attr("data-image")
        )
        val bgCandidates = listOfNotNull(backgroundUrl())
        val descendantBackgrounds = select("[style]").asSequence()
            .mapNotNull { it.backgroundUrl() }
            .toList()

        val allCandidates = imgCandidates + elementCandidates + bgCandidates + descendantBackgrounds

        return allCandidates
            .mapNotNull { raw -> raw?.substringBefore(",")?.trim()?.substringBefore(" ")?.trim() }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull { !isPlaceholderImage(it) }
    }

    private fun Element.findCard(): Element {
        if (hasClass("episode-box") || hasClass("single-item") ||
            hasClass("list-series") || hasClass("list-episodes")) {
            return this
        }
        return generateSequence(this as Element?) { it.parent() }
            .take(8)
            .firstOrNull {
                it.hasClass("episode-box") || it.hasClass("single-item") ||
                        it.hasClass("list-series") || it.hasClass("list-episodes")
            } ?: this
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val card = findCard()

        // Title: list-series (.serie-name a) + single-item (.categorytitle a) + arama
        val title = sequenceOf(
            card.selectFirst("div.serie-name a")?.text(),
            card.selectFirst(".serie-name a")?.text(),
            card.selectFirst(".serie-name")?.text(),
            card.selectFirst("div.categorytitle a")?.text(),
            card.selectFirst(".categorytitle a")?.text(),
            card.selectFirst("a[title]")?.attr("title"),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title"),
            card.attr("title"),
            if (tagName() == "a") text() else null
        ).mapNotNull { it?.trim()?.takeIf { v -> v.isNotBlank() } }.firstOrNull() ?: return null

        // href: /diziler/slug/ — single-item ve list-series aynı formatta
        val href = sequenceOf(
            card.selectFirst("div.serie-name a[href*='/diziler/']")?.attr("href"),
            card.selectFirst(".categorytitle a[href*='/diziler/']")?.attr("href"),
            card.selectFirst(".cat-img a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/diziler/']")?.attr("href")
        ).mapNotNull { cleanUrl(it) }.firstOrNull()
            ?: run {
                // Fallback: bölüm URL'sinden dizi slug'ı türet
                val episodeHref = cleanUrl(card.selectFirst("a[href]")?.attr("href"))
                episodeHref?.let { ep ->
                    Regex("/([^/]+?)-\\d+-sezon-\\d+-bolum/?$", RegexOption.IGNORE_CASE)
                        .find(ep)?.groupValues?.getOrNull(1)?.let { slug ->
                            "$mainUrl/diziler/$slug/"
                        }
                }
            }
            ?: return null

        val poster = card.posterUrl()
        Log.d("DiziGom", "Card: title='$title' href='$href' poster='$poster'")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfalama: /dizi-izle/page/N/ + ?tur=X query'sini koru
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.substringBefore("?")
            val query = request.data.substringAfter("?", "")
            val paginated = base.trimEnd('/') + "/page/$page/"
            if (query.isBlank()) paginated else "$paginated?$query"
        }

        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        // Hem single-item (dizi-izle) hem list-series (ana sayfa) desteği
        val results = document.select("div.single-item, div.list-series")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document

        // Arama sonuçlarında: single-item = dizi, list-episodes = bölüm.
        // Sadece single-item al → bölümler sonuca karışmaz.
        return document.select("div.single-item")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        // Title: h1.title-border içinde "Cape Fear izle - Dizigom | ..." → temizle
        val rawTitle = document.firstText("h1.title-border", "h1.entry-title", "article h1", "h1")
            ?: return null
        val title = rawTitle
            .substringBefore(" izle")
            .substringBefore(" - Dizigom")
            .trim()
            .ifBlank { rawTitle }

        // Poster: div.category_image img
        val poster = document.selectFirst("div.category_image img")?.posterUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }

        // Açıklama: div.category_desc
        val description = document.firstText(
            "div.category_desc",
            ".category_desc",
            ".serieDescription p",
            ".description p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(",", ".")

        // Türler: div.genres a
        val tags = document.select("div.genres a, .genres a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Oyuncular: düz metin "Oyuncular : A, B, C"
        val actors = Regex("Oyuncular\\s*:\\s*([^<\\n]+)", RegexOption.IGNORE_CASE)
            .find(document.selectFirst("div#icerikcat2, div#icerikcatright")?.html() ?: document.html())
            ?.groupValues?.getOrNull(1)
            ?.split(",")
            ?.mapNotNull { name ->
                val clean = name.trim().trimEnd('.', ',', ' ')
                if (clean.isBlank() || clean.length < 2) null else Actor(clean, null)
            }
            ?.distinctBy { it.name }
            .orEmpty()

        // Bölümler: div.bolumust > a[href*='-sezon-'][href*='-bolum']
        val episodes = document.select("div.bolumust")
            .mapNotNull { element ->
                val link = element.selectFirst("a[href*='-sezon-'][href*='-bolum']")
                    ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null

                val baslik = element.selectFirst("div.baslik")?.text()?.trim().orEmpty()
                val bolumIsmi = element.selectFirst("div.bolum-ismi")?.text()?.trim().orEmpty()
                val source = "$baslik $href"

                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (season == null || episode == null) return@mapNotNull null

                newEpisode(href) {
                    name = baslik.ifBlank { bolumIsmi }.ifBlank { "$season. Sezon $episode. Bölüm" }
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        Log.d("DiziGom", "Load: '$title' poster='$poster' episodes=${episodes.size}")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    /**
     * Bölüm sayfasındaki iframe'i bul.
     * HTML: <div class="video-container"><iframe src="https://spidypro.com/embed/XXX" ...></iframe></div>
     * YouTube fragman iframe'ini hariç tut.
     */
    private fun extractPlayerUrl(document: Document): String? {
        return document.select("div.video-container iframe[src], div.dizialani iframe[src], iframe[src]")
            .mapNotNull { cleanUrl(it.attr("src")) }
            .firstOrNull { it.isNotBlank() && !it.contains("youtube.com", true) }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            "[\\\"'](?:file|source|stream|src)[\\\"']\\s*:\\s*[\\\"']([^\\\"']+\\.(?:m3u8|mp4)[^\\\"']*)[\\\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).find(html)?.value?.let { cleanUrl(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziGom", "Resolving episode: $data")
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull() ?: return false

        val playerUrl = extractPlayerUrl(document)
        Log.d("DiziGom", "Player URL: $playerUrl")

        if (!playerUrl.isNullOrBlank()) {
            val playerResponse = runCatching { app.get(playerUrl, referer = data) }.getOrNull()
            val playerHtml = playerResponse?.text.orEmpty()
            val streamUrl = extractPlayerStream(playerHtml)

            if (!streamUrl.isNullOrBlank()) {
                Log.d("DiziGom", "Stream bulundu: $streamUrl")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = playerUrl
                        quality = getQualityFromName(streamUrl)
                    }
                )
                return true
            }

            // Player HTML içinde doğrudan m3u8/mp4 araması
            val directPlayerUrls = Regex(
                "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                RegexOption.IGNORE_CASE
            ).findAll(playerHtml).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

            for (stream in directPlayerUrls) {
                callback(
                    newExtractorLink(source = name, name = "DiziGom", url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = playerUrl
                        quality = getQualityFromName(stream)
                    }
                )
            }
            if (directPlayerUrls.isNotEmpty()) return true
        }

        // Fallback: bölüm sayfasında doğrudan m3u8/mp4 ara
        val directUrls = Regex(
            "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

        for (stream in directUrls) {
            callback(
                newExtractorLink(source = name, name = "DiziGom", url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = data
                    quality = getQualityFromName(stream)
                }
            )
        }
        return directUrls.isNotEmpty()
    }
}
