package com.Blockades

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class TvDiziler : MainAPI() {

    override var mainUrl = "https://tvdiziler.cc/home"
    override var name = "TvDiziler"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private val playerHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Son Bölümler",

        "${mainUrl}/dizi/tur/aile" to "Aile",
        "${mainUrl}/dizi/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/dizi/tur/aksiyon-macera" to "Aksiyon-Macera",
        "${mainUrl}/dizi/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "${mainUrl}/dizi/tur/fantastik" to "Fantastik",
        "${mainUrl}/dizi/tur/gerilim" to "Gerilim",
        "${mainUrl}/dizi/tur/gizem" to "Gizem",
        "${mainUrl}/dizi/tur/komedi" to "Komedi",
        "${mainUrl}/dizi/tur/korku" to "Korku",
        "${mainUrl}/dizi/tur/macera" to "Macera",
        "${mainUrl}/dizi/tur/pembe-dizi" to "Pembe Dizi",
        "${mainUrl}/dizi/tur/romantik" to "Romantik",
        "${mainUrl}/dizi/tur/savas" to "Savaş",
        "${mainUrl}/dizi/tur/savas-politik" to "Savaş & Politik",
        "${mainUrl}/dizi/tur/suc" to "Suç",
        "${mainUrl}/dizi/tur/talk" to "Talk",
        "${mainUrl}/dizi/tur/tarih" to "Tarih",
        "${mainUrl}/dizi/tur/yarisma" to "Yarışma"
    )

    // ---------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {
            if (request.name == "Son Bölümler") {

                val document = app.get(
                    request.data,
                    headers = browserHeaders,
                    referer = "$mainUrl/"
                ).document

                val home = document
                    .select("div.poster-xs")
                    .mapNotNull { it.sonBolumler() }

                newHomePageResponse(
                    request.name,
                    home
                )

            } else {

                val pageUrl =
                    if (page <= 1) {
                        request.data
                    } else {
                        "${request.data}/$page"
                    }

                val document = app.get(
                    pageUrl,
                    headers = browserHeaders,
                    referer = "$mainUrl/"
                ).document

                val home = document
                    .select("div.poster-long")
                    .mapNotNull { it.diziler() }

                newHomePageResponse(
                    request.name,
                    home
                )
            }

        } catch (e: Exception) {

            Log.e(
                "TVD",
                "getMainPage error: ${e.message}",
                e
            )

            newHomePageResponse(
                request.name,
                emptyList()
            )
        }
    }

    // ---------------------------------------------------------
    // SERIES / MOVIE CARDS
    // ---------------------------------------------------------

    private fun Element.diziler(): SearchResponse? {

        val title = selectFirst(
            "div.poster-long-subject h2"
        )
            ?.text()
            ?.trim()
            ?.replace(Regex("\\s+izle$"), "")
            ?: return null

        val href = fixUrlNull(
            selectFirst(
                "div.poster-long-subject a"
            )?.attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst(
                "div.poster-long-image img"
            )?.attr("data-src")
                ?: selectFirst(
                    "div.poster-long-image img"
                )?.attr("src")
        )

        val score = selectFirst(
            "span.rating"
        )?.text()?.trim()

        return if (href.contains("/dizi/")) {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    private fun Element.sonBolumler(): SearchResponse? {

        val title = selectFirst(
            "div.poster-xs-subject p"
        )
            ?.text()
            ?.trim()
            ?.replace(Regex("\\s+izle$"), "")
            ?: return null

        val href = fixUrlNull(
            selectFirst("a")?.attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("div.poster-xs-image img")?.attr("data-src")
                ?: selectFirst("div.poster-xs-image img")?.attr("src")
        )

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {

        val title = selectFirst(
            "h3.truncate"
        )
            ?.text()
            ?.trim()
            ?.replace(Regex("\\s+izle$"), "")
            ?: return null

        val href = fixUrlNull(
            selectFirst("a")?.attr("href")
        ) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("src")
        )

        return if (
            href.contains("/dizi/", ignoreCase = true)
        ) {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return try {

            val encodedQuery = URLEncoder.encode(
                query,
                "UTF-8"
            )

            val response = app.post(
                "${mainUrl}/search?qr=$encodedQuery",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                ),
                referer = "$mainUrl/"
            )

            val objectMapper = ObjectMapper()
                .registerModule(
                    KotlinModule.Builder().build()
                )

            objectMapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false
            )

            val searchResult: SearchResult =
                objectMapper.readValue(
                    response.text
                )

            if (searchResult.success != 1) {
                return emptyList()
            }

            val searchDocument = Jsoup.parse(
                searchResult.data.toString()
            )

            searchDocument
                .select("ul li")
                .mapNotNull { listItem ->

                    val href = listItem
                        .selectFirst("a")
                        ?.attr("href")

                    if (
                        href.isNullOrBlank() ||
                        (
                            !href.contains("/dizi/") &&
                            !href.contains("/film/")
                        )
                    ) {
                        return@mapNotNull null
                    }

                    listItem.toPostSearchResult()
                }

        } catch (e: Exception) {

            Log.e(
                "TVD",
                "Search error: ${e.message}",
                e
            )

            emptyList()
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    // ---------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.d(
            "TVD",
            "Load -> $url"
        )

        return try {

            val document = app.get(
                url,
                headers = browserHeaders,
                referer = "$mainUrl/"
            ).document

            /*
             * Eğer URL doğrudan bölüm sayfası ise
             * bunu tek bölümlük dizi olarak gösteriyoruz.
             */
            if (!url.contains("/dizi/")) {

                val title =
                    document.selectFirst(
                        "div.page-title h1"
                    )?.text()
                        ?.trim()
                        ?.replace(Regex("\\s+izle$"), "")
                        ?: document.selectFirst(
                            "meta[property=og:title]"
                        )?.attr("content")
                        ?: "TvDiziler"

                val episodeNumber =
                    extractEpisodeNumber(
                        title
                    )
                        ?: extractEpisodeNumber(
                            url
                        )
                        ?: 1

                val poster =
                    fixUrlNull(
                        document.selectFirst(
                            "meta[property=og:image]"
                        )?.attr("content")
                    )

                val description =
                    document.selectFirst(
                        "meta[name=og:description]"
                    )?.attr("content")

                val episodes = mutableListOf<Episode>()

                episodes.add(
                    newEpisode(url) {

                        this.name = title
                        this.season = 1
                        this.episode = episodeNumber
                    }
                )

                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }

            // -------------------------------------------------
            // SERIES PAGE
            // -------------------------------------------------

            val title =
                document.selectFirst(
                    "div.page-title p"
                )?.text()
                    ?.trim()
                    ?.replace(Regex("\\s+izle$"), "")
                    ?: document.selectFirst(
                        "meta[property=og:title]"
                    )?.attr("content")
                    ?: "TvDiziler"

            val poster =
                fixUrlNull(
                    document.selectFirst(
                        "div.series-profile-image img"
                    )?.attr("data-src")
                        ?: document.selectFirst(
                            "div.series-profile-image img"
                        )?.attr("src")
                )

            val year =
                document.selectFirst(
                    "h1 span"
                )?.text()
                    ?.let {
                        Regex("(\\d{4})")
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    }

            val rating =
                document.selectXpath(
                    "//span[text()='IMDb Puanı']//following-sibling::p"
                )
                    .text()
                    .trim()

            val duration =
                document.selectXpath(
                    "//span[text()='Süre']//following-sibling::p"
                )
                    .text()
                    .trim()
                    .split(" ")
                    .firstOrNull()
                    ?.toIntOrNull()

            val description =
                document.selectFirst(
                    "div.series-profile-summary p"
                )?.text()?.trim()

            val tags =
                document.selectFirst(
                    "div.series-profile-type"
                )
                    ?.select("a")
                    ?.mapNotNull {
                        it.text()
                            .trim()
                            .takeIf { value -> value.isNotBlank() }
                    }

            val trailer =
                document.selectFirst(
                    "div.series-profile-trailer"
                )?.attr("data-yt")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            val actors = mutableListOf<Actor>()

            document.select(
                "div.series-profile-cast li"
            ).forEach {

                val actorName =
                    it.selectFirst(
                        "h5.truncate"
                    )?.text()?.trim()

                if (actorName.isNullOrBlank()) {
                    return@forEach
                }

                val actorImage =
                    fixUrlNull(
                        it.selectFirst(
                            "img"
                        )?.attr("data-src")
                            ?: it.selectFirst(
                                "img"
                            )?.attr("src")
                    )

                actors.add(
                    Actor(
                        actorName,
                        actorImage
                    )
                )
            }

            // -------------------------------------------------
            // EPISODES
            // -------------------------------------------------

            val episodes = mutableListOf<Episode>()

            val seasons =
                document.select(
                    "div.series-profile-episode-list"
                )

            if (seasons.isNotEmpty()) {

                seasons.forEachIndexed { seasonIndex, seasonElement ->

                    val seasonNumber =
                        extractSeasonNumber(
                            seasonElement
                        ) ?: (seasonIndex + 1)

                    val rawEpisodes =
                        seasonElement.select("li")

                    rawEpisodes.forEachIndexed { episodeIndex, episodeElement ->

                        val episodeLink =
                            episodeElement.selectFirst(
                                "h6.truncate a"
                            )
                                ?: episodeElement.selectFirst(
                                    "a[href]"
                                )
                                ?: return@forEachIndexed

                        val episodeUrl =
                            fixUrlNull(
                                episodeLink.attr("href")
                            ) ?: return@forEachIndexed

                        val episodeName =
                            episodeLink.text()
                                .trim()
                                .ifBlank {
                                    "Bölüm ${episodeIndex + 1}"
                                }

                        val episodeNumber =
                            extractEpisodeNumber(
                                episodeElement.text()
                            )
                                ?: extractEpisodeNumber(
                                    episodeUrl
                                )
                                ?: (episodeIndex + 1)

                        episodes.add(
                            newEpisode(
                                episodeUrl
                            ) {

                                this.name =
                                    episodeName

                                this.season =
                                    seasonNumber

                                this.episode =
                                    episodeNumber
                            }
                        )
                    }
                }
            }

            /*
             * Bölümleri sezon ve bölüm numarasına göre
             * sıralıyoruz.
             */
            episodes.sortWith(
                compareBy<Episode>(
                    { it.season ?: Int.MAX_VALUE },
                    { it.episode ?: Int.MAX_VALUE }
                )
            )

            if (episodes.isEmpty()) {

                Log.w(
                    "TVD",
                    "No episodes found: $url"
                )
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)

                if (actors.isNotEmpty()) {
                    addActors(actors)
                }

                /*
                 * Trailer yoksa:
                 * https://youtube.com/embed/null
                 * gibi hatalı URL oluşturulmuyor.
                 */
                trailer?.let { trailerValue ->

                    val trailerUrl =
                        normalizeYoutubeUrl(
                            trailerValue
                        )

                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                "TVD",
                "Load error: ${e.message}",
                e
            )

            null
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "TVD",
            "loadLinks -> $data"
        )

        var found = false

        try {

            val episodeDocument =
                app.get(
                    data,
                    headers = browserHeaders,
                    referer = "$mainUrl/"
                ).document

            /*
             * Önce doğrudan bölüm sayfasındaki scriptlerden
             * video kaynaklarını arıyoruz.
             */
            val directSources =
                parseVideoSources(
                    episodeDocument.html()
                )

            if (directSources.isNotEmpty()) {

                Log.d(
                    "TVD",
                    "Direct sources: ${directSources.size}"
                )

                found = processSources(
                    directSources,
                    data,
                    subtitleCallback,
                    callback
                ) || found
            }

            /*
             * Sonra bütün player bağlantılarını topluyoruz.
             *
             * Eski kod sadece:
             *
             * li.series-alter-active button[data-hhs]
             *
             * kullanıyordu.
             *
             * Bu yüzden diğer kaynaklar hiç denenmiyordu.
             */
            val playerUrls =
                collectPlayerUrls(
                    episodeDocument
                )

            Log.d(
                "TVD",
                "Player count: ${playerUrls.size}"
            )

            /*
             * Her player tek tek deneniyor.
             */
            for (playerUrl in playerUrls) {

                if (isInvalidPlayerUrl(playerUrl)) {

                    Log.d(
                        "TVD",
                        "Skipping invalid player -> $playerUrl"
                    )

                    continue
                }

                Log.d(
                    "TVD",
                    "Trying player -> $playerUrl"
                )

                /*
                 * YouTube ise:
                 *
                 * ARTIK:
                 * substringAfter("/embed/")
                 *
                 * VE:
                 * ivc.ggtyler.dev
                 *
                 * KULLANILMIYOR.
                 *
                 * Direkt geçerli YouTube URL'si Cloudstream
                 * extractor sistemine veriliyor.
                 */
                if (isYoutubeUrl(playerUrl)) {

                    val youtubeUrl =
                        normalizeYoutubeUrl(
                            playerUrl
                        )

                    if (youtubeUrl != null) {

                        Log.d(
                            "TVD",
                            "Valid YouTube -> $youtubeUrl"
                        )

                        runCatching {

                            loadExtractor(
                                youtubeUrl,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        }.onFailure {

                            Log.e(
                                "TVD",
                                "YouTube extractor failed: ${it.message}"
                            )
                        }
                    }

                    continue
                }

                /*
                 * Player sayfasını aç.
                 */
                val playerDocument =
                    runCatching {

                        app.get(
                            playerUrl,
                            headers = playerHeaders,
                            referer = data
                        ).document

                    }.getOrNull()

                if (playerDocument == null) {

                    Log.w(
                        "TVD",
                        "Player could not be opened -> $playerUrl"
                    )

                    continue
                }

                /*
                 * Player HTML içerisindeki bütün kaynakları
                 * bul.
                 */
                val sources =
                    parseVideoSources(
                        playerDocument.html()
                    )

                if (sources.isEmpty()) {

                    Log.w(
                        "TVD",
                        "No sources found -> $playerUrl"
                    )

                    /*
                     * Player JWPlayer tarzı bir sayfaysa
                     * Cloudstream JWPlayer extractor'ını da
                     * deneyelim.
                     */
                    runCatching {

                        loadExtractor(
                            playerUrl,
                            subtitleCallback,
                            callback
                        )

                        found = true

                    }.onFailure {

                        Log.d(
                            "TVD",
                            "JWPlayer fallback failed -> ${it.message}"
                        )
                    }

                    continue
                }

                Log.d(
                    "TVD",
                    "Sources found: ${sources.size}"
                )

                val processed =
                    processSources(
                        sources,
                        playerUrl,
                        subtitleCallback,
                        callback
                    )

                found = processed || found
            }

        } catch (e: Exception) {

            Log.e(
                "TVD",
                "loadLinks error: ${e.message}",
                e
            )
        }

        Log.d(
            "TVD",
            "loadLinks result -> $found"
        )

        return found
    }

    // ---------------------------------------------------------
    // PLAYER URL COLLECTION
    // ---------------------------------------------------------

    private fun collectPlayerUrls(
        document: Document
    ): List<String> {

        val result =
            linkedSetOf<String>()

        /*
         * data-hhs:
         * Sitenin kullandığı ana player bağlantısı.
         */
        document.select(
            "[data-hhs]"
        ).forEach { element ->

            val value =
                element.attr("data-hhs")
                    .trim()

            if (value.isNotBlank()) {

                fixUrlNull(value)?.let {
                    result.add(it)
                }
            }
        }

        /*
         * iframe src:
         * Bazı player alternatiflerinde doğrudan iframe
         * bulunabilir.
         */
        document.select(
            "iframe[src]"
        ).forEach { iframe ->

            val src =
                iframe.attr("src")
                    .trim()

            if (src.isNotBlank()) {

                fixUrlNull(src)?.let {
                    result.add(it)
                }
            }
        }

        /*
         * series-alter içerisindeki linkler.
         */
        document.select(
            "li.series-alter a[href], " +
                "div.series-alter a[href], " +
                ".series-alter [data-url], " +
                ".series-alter [data-link]"
        ).forEach { element ->

            val candidates =
                listOf(
                    element.attr("href"),
                    element.attr("data-url"),
                    element.attr("data-link")
                )

            candidates.forEach { candidate ->

                if (candidate.isNotBlank()) {

                    fixUrlNull(candidate)?.let {
                        result.add(it)
                    }
                }
            }
        }

        return result
            .filterNot {
                isInvalidPlayerUrl(it)
            }
            .distinct()
    }

    // ---------------------------------------------------------
    // VIDEO SOURCE PARSER
    // ---------------------------------------------------------

    private fun parseVideoSources(
        html: String
    ): List<TvDiziFile> {

        if (html.isBlank()) {
            return emptyList()
        }

        val result =
            linkedMapOf<String, TvDiziFile>()

        /*
         * Öncelikle JSON/JS içerisindeki klasik source
         * objelerini yakalıyoruz:
         *
         * {
         *   file: "...",
         *   type: "hls",
         *   label: "720p"
         * }
         */
        val objectRegex = Regex(
            """\{[^{}]*?["']?file["']?\s*:\s*["']([^"']+)["'][^{}]*\}""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        )

        objectRegex.findAll(html).forEach { match ->

            val obj =
                match.value

            val file =
                extractJsValue(
                    obj,
                    "file"
                )

            if (file.isNullOrBlank()) {
                return@forEach
            }

            val type =
                extractJsValue(
                    obj,
                    "type"
                ) ?: guessSourceType(file)

            val label =
                extractJsValue(
                    obj,
                    "label"
                )
                    ?: extractJsValue(
                        obj,
                        "quality"
                    )
                    ?: ""

            val source =
                TvDiziFile(
                    file = cleanSourceUrl(file),
                    label = label,
                    type = type
                )

            result.putIfAbsent(
                source.file,
                source
            )
        }

        /*
         * Bazı sayfalarda sources dizisi JSON olarak geliyor.
         * Jackson ile ikinci bir deneme yapıyoruz.
         */
        runCatching {

            val sourcesBlock =
                extractSourcesArray(
                    html
                )

            if (!sourcesBlock.isNullOrBlank()) {

                val normalized =
                    normalizeJson(
                        sourcesBlock
                    )

                val mapper =
                    ObjectMapper()
                        .registerModule(
                            KotlinModule.Builder().build()
                        )

                mapper.configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false
                )

                val jsonSources: List<TvDiziFile> =
                    mapper.readValue(
                        normalized
                    )

                jsonSources.forEach { source ->

                    if (
                        source.file.isNotBlank()
                    ) {

                        result.putIfAbsent(
                            source.file,
                            source.copy(
                                file = cleanSourceUrl(
                                    source.file
                                ),
                                type =
                                    source.type.ifBlank {
                                        guessSourceType(
                                            source.file
                                        )
                                    }
                            )
                        )
                    }
                }
            }

        }.onFailure {

            Log.d(
                "TVD",
                "JSON source parser skipped: ${it.message}"
            )
        }

        return result.values.toList()
    }

    // ---------------------------------------------------------
    // SOURCE PROCESSOR
    // ---------------------------------------------------------

    private suspend fun processSources(
        sources: List<TvDiziFile>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var found = false

        val uniqueSources =
            sources
                .filter {
                    it.file.isNotBlank()
                }
                .distinctBy {
                    it.file
                }

        for (source in uniqueSources) {

            val url =
                cleanSourceUrl(
                    source.file
                )

            if (
                url.isBlank() ||
                isInvalidSourceUrl(url)
            ) {
                continue
            }

            val sourceType =
                source.type
                    .lowercase(Locale.US)
                    .trim()

            val quality =
                getQualityFromName(
                    source.label
                )

            when {

                isHlsSource(
                    url,
                    sourceType
                ) -> {

                    /*
                     * EN ÖNEMLİ DÜZELTME:
                     *
                     * Önce URL'nin gerçekten HLS playlist döndürüp
                     * döndürmediğini kontrol ediyoruz.
                     *
                     * Böylece:
                     *
                     * master.txt
                     *
                     * HTML döndürürse artık:
                     *
                     * ExtractorLinkType.M3U8
                     *
                     * olarak ExoPlayer'a gönderilmiyor.
                     */
                    val validHls =
                        validateHls(
                            url,
                            referer
                        )

                    if (!validHls) {

                        Log.w(
                            "TVD",
                            "Invalid HLS skipped -> $url"
                        )

                        continue
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name =
                                buildSourceName(
                                    source.label,
                                    "HLS"
                                ),
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {

                            this.referer = referer

                            this.quality =
                                quality

                            this.headers =
                                mapOf(
                                    "User-Agent" to userAgent
                                )
                        }
                    )

                    found = true
                }

                isMp4Source(
                    url,
                    sourceType
                ) -> {

                    callback(
                        newExtractorLink(
                            source = name,
                            name =
                                buildSourceName(
                                    source.label,
                                    "MP4"
                                ),
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {

                            this.referer = referer

                            this.quality =
                                quality

                            this.headers =
                                mapOf(
                                    "User-Agent" to userAgent
                                )
                        }
                    )

                    found = true
                }

                isYoutubeUrl(url) -> {

                    val youtubeUrl =
                        normalizeYoutubeUrl(
                            url
                        )

                    if (youtubeUrl != null) {

                        runCatching {

                            loadExtractor(
                                youtubeUrl,
                                subtitleCallback,
                                callback
                            )

                            found = true

                        }.onFailure {

                            Log.e(
                                "TVD",
                                "YouTube source failed: ${it.message}"
                            )
                        }
                    }
                }

                else -> {

                    /*
                     * Tür bilinmiyorsa URL'den tür tahmini.
                     */
                    val guessedType =
                        guessSourceType(
                            url
                        )

                    when {

                        guessedType.equals(
                            "hls",
                            true
                        ) -> {

                            val valid =
                                validateHls(
                                    url,
                                    referer
                                )

                            if (!valid) {
                                continue
                            }

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name =
                                        buildSourceName(
                                            source.label,
                                            "HLS"
                                        ),
                                    url = url,
                                    type = ExtractorLinkType.M3U8
                                ) {

                                    this.referer =
                                        referer

                                    this.quality =
                                        quality

                                    this.headers =
                                        mapOf(
                                            "User-Agent" to userAgent
                                        )
                                }
                            )

                            found = true
                        }

                        guessedType.equals(
                            "mp4",
                            true
                        ) -> {

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name =
                                        buildSourceName(
                                            source.label,
                                            "MP4"
                                        ),
                                    url = url,
                                    type = ExtractorLinkType.VIDEO
                                ) {

                                    this.referer =
                                        referer

                                    this.quality =
                                        quality

                                    this.headers =
                                        mapOf(
                                            "User-Agent" to userAgent
                                        )
                                }
                            )

                            found = true
                        }

                        else -> {

                            /*
                             * Bilinmeyen kaynaklarda URL'yi körü
                             * körüne M3U8 olarak vermiyoruz.
                             *
                             * Önce Cloudstream extractor sistemine
                             * bırakıyoruz.
                             */
                            runCatching {

                                loadExtractor(
                                    url,
                                    subtitleCallback,
                                    callback
                                )

                                found = true

                            }.onFailure {

                                Log.d(
                                    "TVD",
                                    "Generic extractor failed: ${it.message}"
                                )
                            }
                        }
                    }
                }
            }
        }

        return found
    }

    // ---------------------------------------------------------
    // HLS VALIDATION
    // ---------------------------------------------------------

    private suspend fun validateHls(
        url: String,
        referer: String
    ): Boolean {

        return try {

            val response =
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to
                            "application/vnd.apple.mpegurl," +
                            "application/x-mpegURL," +
                            "application/octet-stream," +
                            "*/*"
                    ),
                    referer = referer
                )

            val body =
                response.text
                    .trimStart(
                        '\uFEFF',
                        ' ',
                        '\t',
                        '\r',
                        '\n'
                    )

            val valid =
                body.startsWith(
                    "#EXTM3U",
                    ignoreCase = false
                )

            Log.d(
                "TVD",
                "HLS validation: $valid -> $url"
            )

            valid

        } catch (e: Exception) {

            Log.w(
                "TVD",
                "HLS validation failed: ${e.message}"
            )

            false
        }
    }

    // ---------------------------------------------------------
    // SOURCE TYPE HELPERS
    // ---------------------------------------------------------

    private fun isHlsSource(
        url: String,
        type: String
    ): Boolean {

        return type.contains("hls") ||
            type.contains("m3u8") ||
            type.contains("mpegurl") ||
            url.contains(".m3u8", true) ||
            url.contains("/hls/", true) ||
            url.contains("master.m3u8", true) ||
            url.contains("playlist.m3u8", true)
    }

    private fun isMp4Source(
        url: String,
        type: String
    ): Boolean {

        return type == "mp4" ||
            type.contains("video/mp4") ||
            url.contains(".mp4", true)
    }

    private fun guessSourceType(
        url: String
    ): String {

        return when {

            isYoutubeUrl(url) ->
                "youtube"

            url.contains(
                ".m3u8",
                true
            ) ||
                url.contains(
                    "/hls/",
                    true
                ) ||
                url.contains(
                    "master.m3u8",
                    true
                ) ->
                "hls"

            url.contains(
                ".mp4",
                true
            ) ->
                "mp4"

            else ->
                ""
        }
    }

    private fun buildSourceName(
        label: String,
        fallback: String
    ): String {

        return if (
            label.isNotBlank()
        ) {
            "TvDiziler $label"
        } else {
            "TvDiziler $fallback"
        }
    }

    // ---------------------------------------------------------
    // YOUTUBE
    // ---------------------------------------------------------

    private fun isYoutubeUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(Locale.US)

        return lower.contains(
            "youtube.com/"
        ) ||
            lower.contains(
                "youtube-nocookie.com/"
            ) ||
            lower.contains(
                "youtu.be/"
            )
    }

    private fun normalizeYoutubeUrl(
        value: String
    ): String? {

        var url =
            value.trim()

        if (url.isBlank()) {
            return null
        }

        /*
         * Sadece ID verilmişse YouTube watch URL oluştur.
         */
        if (
            !url.contains("://") &&
            Regex(
                "^[A-Za-z0-9_-]{6,}$"
            ).matches(url)
        ) {
            return "https://www.youtube.com/watch?v=$url"
        }

        if (!isYoutubeUrl(url)) {
            return null
        }

        /*
         * embed URL'yi olduğu gibi de kullanabiliriz.
         * Cloudstream YouTube extractor'a geçerli URL
         * veriyoruz; ID'yi elle üretmiyoruz.
         */
        return url
    }

    // ---------------------------------------------------------
    // INVALID URL CHECKS
    // ---------------------------------------------------------

    private fun isInvalidPlayerUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(Locale.US)

        return lower.isBlank() ||
            lower.contains(
                "/kapat/"
            ) ||
            lower.endsWith(
                "/kapat"
            ) ||
            lower.contains(
                "/vid/kapat"
            ) ||
            lower.contains(
                "javascript:"
            ) ||
            lower == "#"
    }

    private fun isInvalidSourceUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(Locale.US)

        return lower.isBlank() ||
            lower == "#" ||
            lower.startsWith(
                "javascript:"
            ) ||
            lower.contains(
                "about:blank"
            )
    }

    // ---------------------------------------------------------
    // SOURCE ARRAY EXTRACTION
    // ---------------------------------------------------------

    private fun extractSourcesArray(
        html: String
    ): String? {

        val markers =
            listOf(
                "sources:",
                "sources =",
                "sources = [",
                "sources:["
            )

        var startIndex = -1

        for (marker in markers) {

            val index =
                html.indexOf(
                    marker,
                    ignoreCase = true
                )

            if (index >= 0) {

                startIndex =
                    html.indexOf(
                        '[',
                        index
                    )

                if (startIndex >= 0) {
                    break
                }
            }
        }

        if (startIndex < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var quote = '\u0000'
        var escaped = false

        for (
            index in startIndex until html.length
        ) {

            val char =
                html[index]

            if (inString) {

                if (escaped) {

                    escaped = false

                } else if (char == '\\') {

                    escaped = true

                } else if (char == quote) {

                    inString = false
                }

                continue
            }

            if (
                char == '"' ||
                char == '\''
            ) {

                inString = true
                quote = char
                continue
            }

            when (char) {

                '[' -> {
                    depth++
                }

                ']' -> {

                    depth--

                    if (depth == 0) {

                        return html.substring(
                            startIndex,
                            index + 1
                        )
                    }
                }
            }
        }

        return null
    }

    // ---------------------------------------------------------
    // JAVASCRIPT VALUE
    // ---------------------------------------------------------

    private fun extractJsValue(
        text: String,
        key: String
    ): String? {

        val regex =
            Regex(
                """["']?$key["']?\s*:\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE
            )

        return regex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    // ---------------------------------------------------------
    // JSON NORMALIZATION
    // ---------------------------------------------------------

    private fun normalizeJson(
        value: String
    ): String {

        var result =
            value.trim()

        /*
         * JS objectlerinde tek tırnak kullanılmışsa basit
         * durumları JSON formatına yaklaştır.
         */
        result =
            result.replace(
                Regex(
                    """([{,]\s*)'([^']+)'\s*:"""
                ),
                "$1\"$2\":"
            )

        result =
            result.replace(
                Regex(
                    """:\s*'([^']*)'"""
                ),
                ":\"$1\""
            )

        return result
    }

    // ---------------------------------------------------------
    // URL CLEAN
    // ---------------------------------------------------------

    private fun cleanSourceUrl(
        url: String
    ): String {

        return url
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'')
    }

    // ---------------------------------------------------------
    // EPISODE NUMBER
    // ---------------------------------------------------------

    private fun extractEpisodeNumber(
        text: String?
    ): Int? {

        if (text.isNullOrBlank()) {
            return null
        }

        val patterns =
            listOf(

                Regex(
                    """(?i)(?:bölüm|bolum|episode|ep)\s*[-_.:]?\s*(\d+)"""
                ),

                Regex(
                    """(?i)(\d+)\s*\.\s*(?:bölüm|bolum|episode)"""
                ),

                Regex(
                    """(?i)(?:/|[-_])(?:bolum|episode|ep)[-_]?(\d+)"""
                ),

                Regex(
                    """(?i)[-_](\d+)[-_]?(?:bolum|episode)"""
                )
            )

        for (pattern in patterns) {

            val result =
                pattern.find(text)

            val number =
                result
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            if (number != null) {
                return number
            }
        }

        return null
    }

    // ---------------------------------------------------------
    // SEASON NUMBER
    // ---------------------------------------------------------

    private fun extractSeasonNumber(
        element: Element
    ): Int? {

        val attributes =
            listOf(
                "data-season",
                "data-sezon",
                "data-season-number"
            )

        for (attribute in attributes) {

            val value =
                element.attr(attribute)
                    .toIntOrNull()

            if (value != null) {
                return value
            }
        }

        val text =
            element.text()

        val patterns =
            listOf(
                Regex(
                    """(?i)(?:sezon|season)\s*(\d+)"""
                ),
                Regex(
                    """(?i)(\d+)\.\s*(?:sezon|season)"""
                )
            )

        for (pattern in patterns) {

            val number =
                pattern.find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            if (number != null) {
                return number
            }
        }

        return null
    }
}

// -------------------------------------------------------------
// VIDEO SOURCE MODEL
// -------------------------------------------------------------

data class TvDiziFile(
    val file: String,
    val label: String = "",
    val type: String = ""
)

// -------------------------------------------------------------
// OPTIONAL JWPLAYER EXTRACTOR
// -------------------------------------------------------------

class TvDizilerOynat : JWPlayer() {

    override val name =
        "TvDizilerOynat"

    override val mainUrl =
        "https://tvdiziler.cc/home/player/oynat/"
}
