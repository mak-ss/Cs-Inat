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
    // RemoteConfig'ten domaini al, yoksa varsayılanı kullan
    override var mainUrl = RemoteConfig.getDomain("dizigom", "https://www.dizigom.icu")
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
     * Lazy-load placeholder'ları filtrele:
     *  - "lazy", "placeholder", "blank", "loading" içeren URL'ler
     *  - .gif uzantılı (1px tracking pixel)
     */
    private fun isPlaceholderImage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return lower.contains("lazy") ||
                lower.contains("placeholder") ||
                lower.contains("blank") ||
                lower.contains("loading") ||
                lower.contains("spinner") ||
                lower.endsWith(".gif") ||
                lower.contains("data:image")
    }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        val match = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    /**
     * Poster URL'sini al. Öncelik sırası:
     *  1. Element'in kendi data-* attribute'ları (data-poster, data-bg, ...)
     *  2. img[data-src] (lazy load asıl URL)
     *  3. img[data-lazy-src], img[data-original]
     *  4. img[data-srcset], img[srcset] (ilk URL)
     *  5. img[src] (lazy placeholder değilse)
     *  6. Element'in CSS background-image'ı
     *  7. Alt elementlerin background-image'ları
     */
    private fun Element.posterUrl(): String? {
        val img = selectFirst("img")

        val candidates = sequenceOf(
            attr("data-poster"),
            attr("data-bg"),
            attr("data-background"),
            attr("data-image"),
            img?.attr("data-src"),                // ← Lazy load birincil kaynak
            img?.attr("data-lazy-src"),
            img?.attr("data-original"),
            img?.attr("data-image"),
            img?.attr("data-srcset")?.substringBefore(",")?.substringBefore(" ")?.trim(),
            img?.attr("srcset")?.substringBefore(",")?.substringBefore(" ")?.trim(),
            img?.attr("src"),                     // En sona koy, placeholder filtrelenecek
            backgroundUrl()
        )

        // Alt elementlerdeki background-image'ları da dene
        val descendantBackgrounds = select("[style]").asSequence()
            .mapNotNull { it.backgroundUrl() }

        return (candidates + descendantBackgrounds)
            .mapNotNull { raw ->
                raw?.substringBefore(",")?.trim()?.substringBefore(" ")?.trim()
            }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull { !isPlaceholderImage(it) }
    }

    private fun Element.findCard(): Element {
        if (hasClass("episode-box") || hasClass("single-item") || hasClass("list-series")) return this
        return generateSequence(this as Element?) { it.parent() }
            .take(12)
            .firstOrNull {
                it.hasClass("episode-box") || it.hasClass("single-item") || it.hasClass("list-series")
            }
            ?: this
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val card = findCard()

        val title = sequenceOf(
            card.selectFirst("div.serie-name a")?.text(),
            card.selectFirst(".serie-name a")?.text(),
            card.selectFirst(".serie-name")?.text(),
            card.selectFirst("a[title]")?.attr("title"),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title"),
            card.attr("title"),
            if (tagName() == "a") text() else null
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }.firstOrNull() ?: return null

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

        val results = (document.select("div.episode-box, div.single-item, div.list-series, a[href*='/diziler/'], a[href*='/dizi/']")
            .mapNotNull { it.toMainPageResult() })
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/"
        ).document
        return document.select("div.episode-box, div.single-item, div.list-series, a[href*='/diziler/'], a[href*='/dizi/']")
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

        // Poster: önce div.seriePoster, sonra og:image
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
            .firstOrNull {
                it.contains("s.php", true) ||
                        it.contains("pilayerplay", true) ||
                        it.contains("pilavyerplay", true)
            }
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
        val document = runCatching { app.get(data, referer = "$mainUrl/").document }.getOrNull()
            ?: run {
                Log.e("DiziGom", "Bölüm sayfası yüklenemedi: $data")
                return false
            }

        // 1. Yöntem: iframe içindeki player URL'sini bul ve WebView ile çöz
        val playerUrl = extractPlayerUrl(document)
        if (!playerUrl.isNullOrBlank()) {
            Log.d("DiziGom", "Player URL bulundu: $playerUrl")

            // Önce doğrudan HTTP isteğiyle stream aramayı dene
            val playerResponse = runCatching { app.get(playerUrl, referer = data) }.getOrNull()
            val playerHtml = playerResponse?.text.orEmpty()
            val directStream = extractPlayerStream(playerHtml)

            if (!directStream.isNullOrBlank()) {
                Log.d("DiziGom", "Doğrudan stream bulundu: $directStream")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom 1080p",
                        url = directStream,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = playerUrl
                        quality = 1080
                    }
                )
                return true
            }

            // Doğrudan bulunamazsa WebView ile çözmeyi dene
            val context = DiziGomPlugin.pluginContext
            if (context != null) {
                Log.d("DiziGom", "WebView extractor başlatılıyor: $playerUrl")
                return try {
                    val extractor = DiziGomWebViewExtractor(context, name)
                    extractor.getUrl(playerUrl, data, subtitleCallback, callback)
                    true
                } catch (e: Exception) {
                    Log.e("DiziGom", "WebView extractor hatası: ${e.message}", e)
                    false
                }
            } else {
                Log.e("DiziGom", "Plugin context null, WebView kullanılamıyor!")
            }
        }

        // 2. Yöntem: Sayfa HTML'inde doğrudan .m3u8 veya .mp4 URL'lerini ara
        val directUrls = Regex(
            "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

        for (stream in directUrls) {
            Log.d("DiziGom", "Doğrudan URL bulundu: $stream")
            callback(
                newExtractorLink(
                    source = name,
                    name = "DiziGom",
                    url = stream,
                    type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = data
                    quality = getQualityFromName(stream)
                }
            )
        }
        if (directUrls.isNotEmpty()) return true

        Log.e("DiziGom", "Hiçbir video kaynağı bulunamadı: $data")
        return false
    }
}
