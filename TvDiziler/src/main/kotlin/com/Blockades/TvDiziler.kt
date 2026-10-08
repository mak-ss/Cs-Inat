package com.Blockades

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
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
import org.jsoup.nodes.Element

class TvDiziler : MainAPI() {

    override var mainUrl = "https://tvdiziler.tv"
    override var name = "TvDiziler"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:157.0) Gecko/20100101 Firefox/157.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.5",
        "Cache-Control" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Son Bölümler",
        "$mainUrl/dizi/tur/aile" to "Aile",
        "$mainUrl/dizi/tur/aksiyon" to "Aksiyon",
        "$mainUrl/dizi/tur/aksiyon-macera" to "Aksiyon-Macera",
        "$mainUrl/dizi/tur/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "$mainUrl/dizi/tur/fantastik" to "Fantastik",
        "$mainUrl/dizi/tur/gerilim" to "Gerilim",
        "$mainUrl/dizi/tur/gizem" to "Gizem",
        "$mainUrl/dizi/tur/komedi" to "Komedi",
        "$mainUrl/dizi/tur/korku" to "Korku",
        "$mainUrl/dizi/tur/macera" to "Macera",
        "$mainUrl/dizi/tur/pembe-dizi" to "Pembe Dizi",
        "$mainUrl/dizi/tur/romantik" to "Romantik",
        "$mainUrl/dizi/tur/savas" to "Savaş",
        "$mainUrl/dizi/tur/savas-politik" to "Savaş & Politik",
        "$mainUrl/dizi/tur/suc" to "Suç",
        "$mainUrl/dizi/tur/talk" to "Talk",
        "$mainUrl/dizi/tur/tarih" to "Tarih",
        "$mainUrl/dizi/tur/yarisma" to "Yarışma"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.name == "Son Bölümler") {
            request.data
        } else {
            "${request.data}/$page"
        }

        val document = app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document.body()

        val home = if (request.name == "Son Bölümler") {
            document.select("div.poster-xs").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.poster-long").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = selectFirst("div.poster-long-subject h2")
            ?.text()?.replace(" izle", "")?.trim() ?: return null

        val href = fixUrlNull(selectFirst("div.poster-long-subject a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.poster-long-image img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("div.poster-long-image img")?.attr("src"))
        val score = selectFirst("span.rating")?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val title = selectFirst("div.poster-xs-subject p")
            ?.text()?.replace(" izle", "")?.trim() ?: return null

        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.poster-xs-image img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("div.poster-xs-image img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toPostSearchResult(): SearchResponse? {
        val title = selectFirst("h3.truncate")
            ?.text()?.trim()?.replace(" izle", "") ?: return null

        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))

        return if (href.contains("dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "$mainUrl/search?qr=${query.trim()}",
            headers = defaultHeaders + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        val searchResult: SearchResult = mapper.readValue(searchReq.toString())

        if (searchResult.success != 1) {
            throw ErrorLoadingException("TvDiziler arama sonucu geçersiz.")
        }

        val document = Jsoup.parse(searchResult.data.orEmpty())
        return document.select("ul li").mapNotNull { item ->
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            if (!href.contains("dizi/") && !href.contains("film/")) return@mapNotNull null
            item.toPostSearchResult()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders, referer = "$mainUrl/").document

        if (!url.contains("/dizi/")) {
            val title = document.selectFirst("div.page-title h1")
                ?.text()?.replace(" izle", "")?.trim() ?: return null

            val episode = document.selectFirst("div.page-title h1 span")
                ?.text()?.filter { it.isDigit() }?.toIntOrNull()

            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val description = document.selectFirst("meta[name=og:description]")?.attr("content")

            val episodes = listOf(
                newEpisode(url) {
                    name = title
                    season = 1
                    this.episode = episode ?: 1
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
            }
        }

        val title = document.selectFirst("div.page-title p")
            ?.text()?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("div.series-profile-image img")?.attr("src"))

        val year = document.selectFirst("h1 span")?.text()
            ?.substringAfter("(", "")
            ?.substringBefore(")", "")
            ?.toIntOrNull()

        val rating = document.selectXpath("//span[text()='IMDb Puanı']//following-sibling::p")
            .text().trim()

        val duration = document.selectXpath("//span[text()='Süre']//following-sibling::p")
            .text().trim().split(" ").firstOrNull()?.toIntOrNull()

        val description = document.selectFirst("div.series-profile-summary p")?.text()?.trim()

        val tags = document.selectFirst("div.series-profile-type")
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }

        val trailer = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")

        val actors = mutableListOf<Actor>()
        document.select("div.series-profile-cast li").forEach { actor ->
            val img = fixUrlNull(actor.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(actor.selectFirst("img")?.attr("src"))
            val actorName = actor.selectFirst("h5.truncate")?.text()?.trim()
            if (!actorName.isNullOrBlank()) {
                actors.add(Actor(actorName, img))
            }
        }

        if (!url.contains("/dizi/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                if (!trailer.isNullOrBlank()) {
                    addTrailer("https://www.youtube.com/embed/$trailer")
                }
            }
        }

        /*
         * Bölümleri DOM sırasına güvenmeden gerçek sezon/bölüm numarasından
         * sıralıyoruz. Böylece site yeni bölümü üste koysa bile Cloudstream
         * tarafında 1,2,3... şeklinde görünür.
         */
        val episodes = mutableListOf<EpisodeWithOrder>()

        document.select("div.series-profile-episode-list").forEachIndexed { seasonIndex, seasonElement ->
            val season = extractSeasonNumber(seasonElement) ?: (seasonIndex + 1)

            seasonElement.select("li").forEach { episodeElement ->
                val link = episodeElement.selectFirst("h6.truncate a")
                    ?: episodeElement.selectFirst("a") ?: return@forEach

                val href = fixUrlNull(link.attr("href")) ?: return@forEach
                val name = link.text().trim().ifBlank { "Bölüm" }

                val episodeNumber = extractEpisodeNumber(name)
                    ?: extractEpisodeNumber(href)
                    ?: (episodes.count { it.season == season } + 1)

                episodes.add(
                    EpisodeWithOrder(
                        season = season,
                        episode = episodeNumber,
                        name = name,
                        url = href
                    )
                )
            }
        }

        val orderedEpisodes = episodes
            .distinctBy { it.url }
            .sortedWith(compareBy<EpisodeWithOrder> { it.season }.thenBy { it.episode })
            .map { item ->
                newEpisode(item.url) {
                    name = item.name
                    season = item.season
                    episode = item.episode
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, orderedEpisodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
            if (!trailer.isNullOrBlank()) {
                addTrailer("https://www.youtube.com/embed/$trailer")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d("TVD", "loadLinks -> $data")

        val episodeDocument = try {
            app.get(
                data,
                headers = defaultHeaders,
                referer = "$mainUrl/"
            ).document
        } catch (e: Exception) {
            Log.e("TVD", "Bölüm sayfası alınamadı: ${e.message}", e)
            return false
        }

        /*
         * Sadece `li.series-alter-active` seçmek hatalıydı.
         * Site birden fazla player/part sunabiliyor. Tüm data-hhs
         * değerlerini topluyoruz ve tekrarları kaldırıyoruz.
         */
        val players = episodeDocument
            .select("[data-hhs]")
            .mapNotNull { element ->
                val raw = element.attr("data-hhs").trim()
                if (raw.isBlank()) null else fixUrlNull(raw)
            }
            .distinct()

        if (players.isEmpty()) {
            Log.w("TVD", "data-hhs player bulunamadı: $data")
            return false
        }

        Log.d("TVD", "Bulunan player sayısı: ${players.size}")

        var found = false

        for (playerUrl in players) {
            try {
                if (isYoutube(playerUrl)) {
                    val youtubeId = extractYoutubeId(playerUrl)

                    if (!youtubeId.isNullOrBlank()) {
                        loadExtractor(
                            "https://www.youtube.com/watch?v=$youtubeId",
                            subtitleCallback,
                            callback
                        )
                        found = true
                    }
                    continue
                }

                val playerDocument = app.get(
                    playerUrl,
                    headers = defaultHeaders + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                    referer = data
                ).document

                /*
                 * Player sayfasındaki bütün script'leri tarıyoruz.
                 * Önceki kod sadece `sources:` geçen ilk script'i alıyordu.
                 */
                val scripts = playerDocument.select("script").map { it.data() + "\n" + it.html() }

                val sources = scripts
                    .flatMap { parseSources(it) }
                    .filter { it.file.startsWith("http://") || it.file.startsWith("https://") }
                    .distinctBy { it.file }
                    .sortedByDescending { qualityValue(it.label) }

                Log.d("TVD", "Player=$playerUrl kaynak sayısı=${sources.size}")

                for (source in sources) {
                    val type = source.type.lowercase()
                    val linkType = when {
                        type.contains("hls") || source.file.contains(".m3u8", ignoreCase = true) ->
                            ExtractorLinkType.M3U8

                        type.contains("dash") || source.file.contains(".mpd", ignoreCase = true) ->
                            ExtractorLinkType.DASH

                        else -> ExtractorLinkType.VIDEO
                    }

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "TvDiziler ${source.label.ifBlank { "Video" }}",
                            url = source.file,
                            type = linkType
                        ) {
                            referer = playerUrl
                            quality = qualityValue(source.label).takeIf { it > 0 }
                                ?: Qualities.Unknown.value
                            headers = mapOf(
                                "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                "Referer" to playerUrl
                            )
                        }
                    )

                    found = true
                }

                /*
                 * Bazı player sürümlerinde `sources` yerine JWPlayer
                 * config kullanılıyor. Kaynak bulunamadıysa JWPlayer
                 * extractor'ını da deniyoruz.
                 */
                if (sources.isEmpty()) {
                    try {
                        loadExtractor(
                            playerUrl,
                            subtitleCallback,
                            callback
                        )
                        found = true
                    } catch (e: Exception) {
                        Log.w("TVD", "JWPlayer fallback başarısız: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("TVD", "Player çözümlenemedi: $playerUrl -> ${e.message}", e)
            }
        }

        return found
    }

    /**
     * JS içindeki sources listesini Jackson'a körlemesine vermek yerine
     * her source nesnesini ayrı çıkarıyoruz. Böylece:
     *
     * sources: [{file:"...",label:"1080p",type:"hls"}, ...]
     *
     * ve tek tırnaklı JS:
     *
     * sources: [{file:'...',label:'720p',type:'mp4'}, ...]
     *
     * formatlarının ikisi de çalışır.
     */
    private fun parseSources(script: String): List<TvDiziFile> {
        val result = mutableListOf<TvDiziFile>()

        val sourceArrayRegex = Regex(
            """(?is)\bsources\s*:\s*\[(.*?)]"""
        )

        val arrays = sourceArrayRegex.findAll(script).map { it.groupValues[1] }

        for (arrayContent in arrays) {
            val objectRegex = Regex("""(?is)\{(.*?)\}""")

            for (obj in objectRegex.findAll(arrayContent)) {
                val body = obj.groupValues[1]

                val file = extractJsProperty(body, "file") ?: continue
                val label = extractJsProperty(body, "label") ?: ""
                val type = extractJsProperty(body, "type") ?: ""

                result.add(
                    TvDiziFile(
                        file = cleanJsUrl(file),
                        label = label.trim(),
                        type = type.trim()
                    )
                )
            }
        }

        return result
    }

    private fun extractJsProperty(body: String, key: String): String? {
        val regex = Regex(
            """(?is)(?:["']?$key["']?)\s*:\s*["']([^"']+)["']"""
        )
        return regex.find(body)?.groupValues?.getOrNull(1)
    }

    private fun cleanJsUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun isYoutube(url: String): Boolean {
        return url.contains("youtube.com", true) ||
                url.contains("youtu.be", true)
    }

    private fun extractYoutubeId(url: String): String? {
        return when {
            url.contains("/embed/") ->
                url.substringAfter("/embed/").substringBefore("?").substringBefore("&")

            url.contains("youtu.be/") ->
                url.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")

            url.contains("watch?v=") ->
                url.substringAfter("watch?v=").substringBefore("&")

            else -> null
        }
    }

    private fun extractEpisodeNumber(value: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\bBölüm\s*[:.]?\s*(\d+)"""),
            Regex("""(?i)\bepisode\s*[:.]?\s*(\d+)"""),
            Regex("""(?i)[-/](\d+)(?:[-/.]|$)""")
        )

        for (pattern in patterns) {
            pattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }

        return null
    }

    private fun extractSeasonNumber(element: Element): Int? {
        val candidates = listOf(
            element.attr("data-season"),
            element.attr("data-sezon"),
            element.selectFirst("[data-season]")?.attr("data-season").orEmpty(),
            element.selectFirst("[data-sezon]")?.attr("data-sezon").orEmpty(),
            element.selectFirst("h4, h5, h6, .season-title, .series-profile-season")?.text().orEmpty()
        )

        for (candidate in candidates) {
            Regex("""(?i)(?:sezon|season)\s*[:.]?\s*(\d+)""")
                .find(candidate)
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return it }
        }

        return null
    }

    private fun qualityValue(label: String): Int {
        val text = label.lowercase()

        Regex("""(\d{3,4})\s*p""").find(text)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.let { return it }

        return when {
            "2160" in text || "4k" in text -> 2160
            "1440" in text -> 1440
            "1080" in text || "fullhd" in text || "full hd" in text -> 1080
            "720" in text || "hd" in text -> 720
            "576" in text -> 576
            "480" in text -> 480
            "360" in text -> 360
            else -> Qualities.Unknown.value
        }
    }

    private data class EpisodeWithOrder(
        val season: Int,
        val episode: Int,
        val name: String,
        val url: String
    )
}

data class TvDiziFile(
    @JsonProperty("file") val file: String,
    @JsonProperty("label") val label: String,
    @JsonProperty("type") val type: String
)

class TvDizilerOynat : JWPlayer() {
    override val name = "TvDizilerOynat"
    override val mainUrl = "https://tvdiziler.tv/home/player/oynat/"
}
