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

        // Ana sayfa + arama sonuçları için tüm başlık varyantları:
        //  - .serie-name a (list-series, list-episodes)
        //  - .categorytitle a (single-item / arama)
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

        // Dizi URL'si:
        //  - list-series: .serie-name a href → /diziler/slug/
        //  - single-item: .cat-img a href veya .categorytitle a href → /diziler/slug/
        //  - list-episodes: dizi linki YOK → episode URL'sinden slug türet
        val href = sequenceOf(
            card.selectFirst("div.serie-name a[href*='/diziler/']")?.attr("href"),
            card.selectFirst(".categorytitle a[href*='/diziler/']")?.attr("href"),
            card.selectFirst(".cat-img a[href*='/diziler/']")?.attr("href"),
            card.selectFirst("a[href*='/diziler/']")?.attr("href")
        ).mapNotNull { cleanUrl(it) }.firstOrNull()
            ?: run {
                // Bölüm URL'sinden dizi slug'ı türet: "/alikara-1-sezon-10-bolum/"
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
        val pageUrl = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/").document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        // SADECE dizi kartları. list-episodes bölümdür → dizi detayına değil bölüme gider.
        val results = document.select("div.list-series")
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

        // Detay sayfası HTML'inde: <h1 class="title-border bd-purple">Cape Fear izle - ...</h1>
        val rawTitle = document.firstText("h1.title-border", "h1.entry-title", "article h1", "h1")
            ?: return null
        val title = rawTitle
            .substringBefore(" izle")
            .substringBefore(" - Dizigom")
            .trim()
            .ifBlank { rawTitle }

        // Poster: div.category_image img (HTML'de doğrulandı)
        val poster = document.selectFirst("div.category_image img")?.posterUrl()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }

        // Açıklama: div.category_desc (HTML'de doğrulandı)
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

        // Türler: div.genres a (HTML'de doğrulandı)
        val tags = document.select("div.genres a, .genres a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Oyuncular: düz metin "Oyuncular : A, B, C" — link değil.
        // Meta satırından çıkar: <span class="dizimeta">... Oyuncular : </span> A, B, C
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
        // HTML'de yapı: <div class="bolumust"><button>...<a href="/...-1-sezon-1-bolum/"><div class="baslik">1. Sezon 1. Bölüm ...</div></a></div>
        val episodes = document.select("div.bolumust")
            .mapNotNull { element ->
                val link = element.selectFirst("a[href*='-sezon-'][href*='-bolum']")
                    ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null

                // Bölüm adı: div.baslik içindeki metin (button'ın "İzledim" metnini ALMA)
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
     * HTML: <iframe referrerpolicy="no-referrer" src="https://spidypro.com/embed/XXX" ...>
     * Site pilavyerplay/pilayerplay yerine spidypro kullanıyor.
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
