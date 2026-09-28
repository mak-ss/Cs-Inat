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

    private val genreRoutes = linkedMapOf(
        "Aile" to "aile", "Aksiyon" to "aksiyon", "Animasyon" to "animasyon",
        "Belgesel" to "belgesel", "Bilim Kurgu" to "bilim-kurgu", "Biyografi" to "biyografi",
        "Dram" to "dram", "Fantastik" to "fantastik", "Gençlik" to "genclik",
        "Gerilim" to "gerilim", "Gizem" to "gizem", "Komedi" to "komedi",
        "Korku" to "korku", "Macera" to "macera", "Polisiye" to "polisiye",
        "Romantik" to "romantik", "Savaş" to "savas", "Suç" to "suc", "Tarih" to "tarih"
    )

    override val mainPage = mainPageOf(
        *genreRoutes.map { (genre, slug) -> "$mainUrl/tur/$slug/" to genre }.toTypedArray()
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { fixUrlNull(it) }

    /**
     * SADECE kesin placeholder URL'lerini filtrele.
     * Gerçek poster URL'lerini (webp, jpg, jpeg, png) ASLA elemez.
     */
    private fun isPlaceholderImage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase().trim()

        if (lower.startsWith("data:image")) return true

        // Dosya adı kontrolü
        val fileName = lower.substringAfterLast("/").substringBefore("?")

        if (fileName == "lazy.png" || fileName == "lazy.gif" || fileName == "lazy.jpg") return true
        if (fileName == "blank.gif" || fileName == "blank.png") return true
        if (fileName == "placeholder.png" || fileName == "placeholder.jpg") return true
        if (fileName == "loading.gif" || fileName == "spinner.gif") return true

        // Yol kontrolü
        if (lower.contains("/images/lazy")) return true
        if (lower.contains("/images/blank")) return true
        if (lower.contains("/images/placeholder")) return true
        if (lower.contains("/images/loading")) return true

        // Gerçek poster uzantıları → kesinlikle geçerli
        if (lower.endsWith(".webp") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            return false
        }

        return false
    }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        if (style.isBlank()) return null
        val match = Regex("url\\(['\"]?([^'\"\\)]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    /**
     * Poster URL'sini al. Sitenin gerçek yapısına göre öncelik:
     *  1. img[data-src]     → lazy-load asıl URL (Son Eklenen Bölümler)
     *  2. img[data-lazy-src], img[data-original], img[data-image]
     *  3. img[srcset] / img[data-srcset] ilk URL
     *  4. img[src]          → direkt URL (Son Eklenen Diziler)
     *  5. Element data-* attribute'ları
     *  6. CSS background-image
     */
    private fun Element.posterUrl(): String? {
        val img = selectFirst("img")

        // Sıralama önemli: lazy-load attribute'ları ÖNCE, src SONRA
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

        // Başlık: card.text() KALDIRILDI, çok geniş metin yakalıyordu
        val title = sequenceOf(
            card.selectFirst("div.serie-name a")?.text(),
            card.selectFirst(".serie-name a")?.text(),
            card.selectFirst(".serie-name")?.text(),
            card.selectFirst("a[title]")?.attr("title"),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title"),
            card.attr("title"),
            if (tagName() == "a") text() else null
        ).mapNotNull { it?.trim()?.takeIf { v -> v.isNotBlank() } }.firstOrNull() ?: return null

        val href = sequenceOf(
            card.selectFirst("div.serie-name a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/dizi/']")?.attr("href"),
            if (tagName() == "a") attr("href") else null,
            card.selectFirst("a")?.attr("href"),
            attr("href")
        ).mapNotNull { cleanUrl(it) }.firstOrNull() ?: return null

        val poster = card.posterUrl()
        Log.d("DiziGom", "Card: title='$title' poster='$poster'")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        // SADECE kart konteynerlarını seç.
        // a[href*=...] seçicileri alfabetik liste linklerini de yakalıyordu → poster=null
        val results = document.select("div.list-series, div.list-episodes, div.episode-box, div.single-item")
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

        // Arama sonuçlarında episode-box veya single-item kullanılıyor
        return document.select("div.episode-box, div.single-item")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.firstText("div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1")
            ?: return null

        val poster = document.selectFirst("div.seriePoster")?.posterUrl()
            ?: document.selectFirst("div.seriePoster img")?.posterUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }

        val description = document.firstText(
            "div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { link ->
                val actor = link.text().trim()
                if (actor.isBlank()) null else Actor(actor, link.posterUrl())
            }.distinctBy { it.name }

        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                val source = "${element.text()} ${link.attr("title")}".trim()
                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim()
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

    private fun extractPlayerUrl(document: Document): String? {
        return document.select("iframe[src], frame[src]")
            .mapNotNull { cleanUrl(it.attr("src")) }
            .firstOrNull { it.contains("s.php", true) || it.contains("pilayerplay", true) }
            ?: Regex("https?://[^\\\"'\\s<>]+/s\\.php\\?[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
                .find(document.html())?.value?.let { cleanUrl(it) }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            "[\\\"']stream[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            "https?://[^\\\"'\\s<>]+/api/stream\\.php(?:\\?[^\\\"'\\s<>]+)?",
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
        if (!playerUrl.isNullOrBlank()) {
            val playerResponse = runCatching { app.get(playerUrl, referer = data) }.getOrNull()
            val playerHtml = playerResponse?.text.orEmpty()
            val streamUrl = extractPlayerStream(playerHtml)

            if (!streamUrl.isNullOrBlank()) {
                Log.d("DiziGom", "PilayerPlay stream bulundu")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = playerUrl
                        quality = 1080
                    }
                )
                return true
            }

            val directPlayerUrl = Regex(
                "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                RegexOption.IGNORE_CASE
            ).findAll(playerHtml).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

            for (stream in directPlayerUrl) {
                callback(
                    newExtractorLink(source = name, name = "DiziGom", url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = playerUrl
                        quality = getQualityFromName(stream)
                    }
                )
            }
            if (directPlayerUrl.isNotEmpty()) return true
        }

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
