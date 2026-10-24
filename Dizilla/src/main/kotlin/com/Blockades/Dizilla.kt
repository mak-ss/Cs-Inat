// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

package com.Blockades

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val privateAESKey = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

    // Ortak ObjectMapper (tüm isteklerde tekrar tekrar oluşturmamak için)
    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).also {
            it.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("verifying")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val supportedSyncNames = setOf(
        SyncIdName.Simkl
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler" to "Yeni Eklenen Bölümler",
        "${mainUrl}/arsiv" to "Yeni Eklenen Diziler",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=15&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Aile",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=9&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Aksiyon",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=5&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Bilim Kurgu",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=2&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Dram",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=12&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Fantastik",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=18&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Gerilim",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=4&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Komedi",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=8&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Korku",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=24&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Macera",
        "${mainUrl}/api/bg/findSeries?releaseYearStart=1900&releaseYearEnd=2024&imdbPointMin=5&imdbPointMax=10&categoryIdsComma=7&countryIdsComma=&orderType=date_desc&languageId=-1&currentPage=1&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma=" to "Romantik",
    )

    // =====================================================================
    // MAIN PAGE
    // =====================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            Log.d("DizillaDebug", "getMainPage: ${request.data}, page: $page")

            when {
                request.data.contains("/arsiv") -> getArsivPage(page, request)
                request.data.contains("api/bg/findSeries") -> getFindSeriesPage(page, request)
                else -> getHtmlPage(request)
            }
        } catch (e: Exception) {
            Log.e("DizillaDebug", "getMainPage exception: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun getArsivPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("${request.data}?page=$page", interceptor = interceptor)
        val document = response.document

        val script = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return newHomePageResponse(request.name, emptyList())

        val secureData = mapper.readTree(script)
            .path("props").path("pageProps").path("secureData").asText()

        if (secureData.isEmpty()) return newHomePageResponse(request.name, emptyList())

        val decodedData = decryptDizillaResponse(secureData)
            ?: return newHomePageResponse(request.name, emptyList())

        val json = mapper.readTree(decodedData)
        val resultArray = json.path("listItems")
        if (!resultArray.isArray) return newHomePageResponse(request.name, emptyList())

        val home = resultArray.mapNotNull { item ->
            val title = item.path("original_title").asText()
                .ifEmpty { item.path("used_title").asText() }
            val slug = item.path("used_slug").asText()
            val poster = fixPosterUrl(
                item.path("poster_url").asText()
                    .ifEmpty { item.path("face_url").asText() }
            )
            if (title.isEmpty() || slug.isEmpty()) return@mapNotNull null

            newTvSeriesSearchResponse(title, fixUrl("/$slug"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getFindSeriesPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = request.data
        Log.d("DizillaDebug", "API URL: $apiUrl")

        val response = app.post(
            apiUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.5",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to mainUrl,
                "Referer" to mainUrl
            ),
            data = mapOf("page" to page.toString())
        )

        val responseBody = response.body.string()

        val jsonResponse = mapper.readTree(responseBody)
        if (!(jsonResponse.path("success").asBoolean())) {
            return newHomePageResponse(request.name, emptyList())
        }

        val encryptedData = jsonResponse.path("response").asText()
        if (encryptedData.isEmpty()) return newHomePageResponse(request.name, emptyList())

        val decodedData = decryptDizillaResponse(encryptedData)
            ?: return newHomePageResponse(request.name, emptyList())

        // "result" array'ini manuel çıkar (JSON bazen bozuk olabilir)
        val resultArrayJson = extractArray(decodedData, "result")
            ?: return newHomePageResponse(request.name, emptyList())

        val resultArray = mapper.readTree(resultArrayJson)

        val home = resultArray.mapNotNull { item ->
            val title = item.path("original_title").asText()
                .ifEmpty { item.path("culture_title").asText() }
                .ifEmpty { item.path("title").asText() }
            val poster = item.path("poster_url").asText()
                .ifEmpty { item.path("square_url").asText() }
                .ifEmpty { item.path("face_url").asText() }
                .ifEmpty { item.path("back_url").asText() }
            val slug = item.path("used_slug").asText()
                .ifEmpty {
                    Regex(""""serie_site_id":0,.*?"used_slug":"(.*?)"""")
                        .find(item.toString())?.groupValues?.get(1) ?: ""
                }

            if (title.isEmpty() || slug.isEmpty()) return@mapNotNull null

            newTvSeriesSearchResponse(title, fixUrl("/$slug"), TvType.TvSeries) {
                this.posterUrl = fixPosterUrl(poster)
            }
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getHtmlPage(request: MainPageRequest): HomePageResponse {
        val document = Jsoup.parse(app.get(request.data, interceptor = interceptor).body.string())
        val home = if (request.data.contains("api")) {
            document.select("span.watchlistitem-").mapNotNull { it.diziler() }
        } else {
            document.select("div.col-span-3 a").mapNotNull { it.sonBolumler() }
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val title = this.selectFirst("span.font-normal")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name = this.selectFirst("h2")?.text() ?: return null
        val epName = this.selectFirst("div.opacity-80")?.text()
            ?.replace(". Sezon ", "x")
            ?.replace(". Bölüm", "") ?: return null

        val title = "$name - $epName"
        val epDoc = fixUrlNull(this.attr("href"))?.let { Jsoup.parse(app.get(it).body.string()) }
            ?: return null

        val href = fixUrlNull(epDoc.selectFirst("div.poster a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(epDoc.selectFirst("div.poster img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // =====================================================================
    // SEARCH
    // =====================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "${mainUrl}/api/bg/searchContent?searchterm=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to "application/json, text/plain, */*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "${mainUrl}/"
            )
        )

        val searchResult: SearchResult = mapper.readValue(searchReq.text)
        val encryptedBlob = searchResult.response
            ?: throw ErrorLoadingException("API response field is null")

        val decryptedJson = decryptDizillaResponse(encryptedBlob)
            ?: throw ErrorLoadingException("Decryption failed")

        // Bazen "essage":"..." şeklinde baştaki 'm' eksik gelebilir
        val fixedJson = if (!decryptedJson.startsWith("{") && decryptedJson.contains("\"essage\"")) {
            "{m\"$decryptedJson"
        } else {
            decryptedJson
        }

        val contentJson: SearchData = mapper.readValue(fixedJson)
        if (contentJson.state != true) return emptyList()

        return contentJson.result?.mapNotNull { item ->
            val name = item.title ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val posterLink = fixPosterUrl(item.poster) ?: ""
            newTvSeriesSearchResponse(name, fixUrl("/$slug"), TvType.TvSeries) {
                this.posterUrl = posterLink
            }
        } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // =====================================================================
    // LOAD (detay sayfası) — YENİLENDİ: Next.js + secureData + JSON
    // =====================================================================
    override suspend fun load(url: String): LoadResponse? {
        return try {
            Log.d("DizillaDebug", "load(): $url")

            val document = app.get(url, interceptor = interceptor).document
            val script = document.selectFirst("script#__NEXT_DATA__")?.data()
                ?: run {
                    Log.e("DizillaDebug", "NEXT_DATA bulunamadı")
                    return null
                }

            val rootNode = mapper.readTree(script)
            val secureData = rootNode
                .path("props").path("pageProps").path("secureData").asText()

            if (secureData.isEmpty()) {
                Log.e("DizillaDebug", "secureData boş")
                return null
            }

            val decodedData = decryptDizillaResponse(secureData)
            if (decodedData.isNullOrEmpty()) {
                Log.e("DizillaDebug", "Decrypt başarısız")
                return null
            }

            Log.d("DizillaDebug", "Decoded örnek: ${decodedData.take(300)}")

            val seriesJson = mapper.readTree(decodedData)

            // Bazen veri { serie: {...} } veya { data: {...} } şeklinde sarmalanır
            val s: JsonNode = when {
                seriesJson.has("used_slug") || seriesJson.has("title") -> seriesJson
                seriesJson.has("serie") && seriesJson.path("serie").isObject -> seriesJson.path("serie")
                seriesJson.has("data") && seriesJson.path("data").isObject -> seriesJson.path("data")
                seriesJson.has("series") && seriesJson.path("series").isObject -> seriesJson.path("series")
                else -> seriesJson
            }

            Log.d("DizillaDebug", "JSON keys: ${s.fieldNames().asSequence().toList()}")

            // --- Temel bilgiler ---
            val title = s.path("used_title").asText()
                .ifEmpty { s.path("title").asText() }
                .ifEmpty { s.path("original_title").asText() }
                .ifEmpty { s.path("name").asText() }

            val year = s.path("release_year").asIntOrNull()
                ?: s.path("releaseYear").asIntOrNull()
                ?: s.path("year").asIntOrNull()

            val poster = fixPosterUrl(
                s.path("poster_url").asText()
                    .ifEmpty { s.path("poster").asText() }
                    .ifEmpty { s.path("face_url").asText() }
                    .ifEmpty { s.path("back_url").asText() }
            )

            val plot = s.path("description").asText()
                .ifEmpty { s.path("overview").asText() }
                .ifEmpty { s.path("short_description").asText() }
                .ifEmpty { s.path("plot").asText() }

            val duration = s.path("episode_duration").asIntOrNull()
                ?: s.path("runtime").asIntOrNull()
                ?: s.path("duration").asIntOrNull()

            // --- Türler ---
            val tags = mutableListOf<String>()
            val categoriesArray = s.path("categories")
            if (categoriesArray.isArray) {
                categoriesArray.forEach { cat ->
                    val catName = cat.path("name").asText()
                        .ifEmpty { cat.path("title").asText() }
                    if (catName.isNotEmpty()) tags.add(catName)
                }
            }
            if (tags.isEmpty()) {
                val catStr = s.path("categoriesComma").asText()
                    .ifEmpty { s.path("genres").asText() }
                if (catStr.isNotEmpty()) {
                    tags.addAll(catStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }

            // --- Oyuncular ---
            val actors = mutableListOf<Actor>()
            val castArray = s.path("cast")
            if (castArray.isArray) {
                castArray.forEach { person ->
                    val name = person.path("name").asText()
                    val role = person.path("role").asText()
                        .ifEmpty { person.path("character").asText() }
                    if (name.isNotEmpty()) {
                        actors.add(Actor(name, role.ifEmpty { null }))
                    }
                }
            }

            // --- Bölümler ---
            val episodesList = mutableListOf<Episode>()
            collectEpisodes(s, episodesList)

            Log.d("DizillaDebug", "Title=$title | Poster=$poster | Episodes=${episodesList.size}")

            if (title.isEmpty()) {
                Log.e("DizillaDebug", "Title boş, return null")
                return null
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                addActors(actors)
            }
        } catch (e: Exception) {
            Log.e("DizillaDebug", "load() hatası: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Bölümleri farklı olası JSON şemalarından toplar:
     *  - series.seasons[] -> episodes[]
     *  - series.seasons[] -> season_episodes[]
     *  - series.episodes[] (season her episode içinde)
     */
    private fun collectEpisodes(s: JsonNode, out: MutableList<Episode>) {
        // 1) seasons[] içinde episodes / season_episodes
        val seasonsArray = s.path("seasons")
        if (seasonsArray.isArray && seasonsArray.size() > 0) {
            seasonsArray.forEach { season ->
                val seasonNum = season.path("season_number").asIntOrNull()
                    ?: season.path("season").asIntOrNull()
                    ?: season.path("number").asIntOrNull()

                val epsNode = when {
                    season.path("episodes").isArray -> season.path("episodes")
                    season.path("season_episodes").isArray -> season.path("season_episodes")
                    else -> null
                } ?: return@forEach

                epsNode.forEach { ep ->
                    val epNum = ep.path("episode_number").asIntOrNull()
                        ?: ep.path("episode").asIntOrNull()
                        ?: ep.path("number").asIntOrNull()
                    val epTitle = ep.path("title").asText()
                        .ifEmpty { ep.path("name").asText() }
                        .ifEmpty { if (epNum != null) "$epNum. Bölüm" else "" }
                    val epSlug = ep.path("used_slug").asText()
                        .ifEmpty { ep.path("slug").asText() }
                        .ifEmpty { ep.path("url").asText() }

                    if (epSlug.isNotEmpty() && seasonNum != null && epNum != null) {
                        out.add(newEpisode(fixUrl("/$epSlug")) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        }

        // 2) Direkt episodes[] (season her episode içinde)
        if (out.isEmpty()) {
            val epsArray = s.path("episodes")
            if (epsArray.isArray) {
                epsArray.forEach { ep ->
                    val seasonNum = ep.path("season_number").asIntOrNull()
                        ?: ep.path("season").asIntOrNull()
                    val epNum = ep.path("episode_number").asIntOrNull()
                        ?: ep.path("episode").asIntOrNull()
                        ?: ep.path("number").asIntOrNull()
                    val epTitle = ep.path("title").asText()
                        .ifEmpty { ep.path("name").asText() }
                        .ifEmpty { if (epNum != null) "$epNum. Bölüm" else "" }
                    val epSlug = ep.path("used_slug").asText()
                        .ifEmpty { ep.path("slug").asText() }
                        .ifEmpty { ep.path("url").asText() }

                    if (epSlug.isNotEmpty() && seasonNum != null && epNum != null) {
                        out.add(newEpisode(fixUrl("/$epSlug")) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
        }

        // Sıralama (sezon, bölüm)
        out.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // =====================================================================
    // LOAD LINKS
    // =====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        val script = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return false

        return try {
            val rootNode = mapper.readTree(script)
            val secureData = rootNode.path("props").path("pageProps").path("secureData").asText()
            if (secureData.isEmpty()) return false

            val decodedData = decryptDizillaResponse(secureData)
            if (decodedData.isNullOrEmpty()) {
                Log.e("DizillaDebug", "loadLinks: decoded boş")
                return false
            }

            var linkFound = false
            val contentRegex = Regex(""""source_content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            contentRegex.findAll(decodedData).forEach { match ->
                val rawHtml = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                    .replace("\\\\", "\\")

                if (rawHtml.contains("iframe", ignoreCase = true)) {
                    var iframeUrl = Jsoup.parse(rawHtml).select("iframe").attr("src")
                    if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                    val finalUrl = fixUrlNull(iframeUrl)
                    if (!finalUrl.isNullOrEmpty()) {
                        Log.d("DizillaDebug", "Link: $finalUrl")
                        loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
                        linkFound = true
                    }
                }
            }

            if (!linkFound) {
                Log.e("DizillaDebug", "loadLinks: link bulunamadı. Ham: ${decodedData.take(500)}")
            }
            linkFound
        } catch (e: Exception) {
            Log.e("DizillaDebug", "loadLinks hata: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    // =====================================================================
    // YARDIMCI
    // =====================================================================

    /**
     * Ham metin içinden "key":[ ... ] bloğunu tırnak/kaçış karakterlerini
     * gözeterek güvenli şekilde çıkarır.
     */
    private fun extractArray(decoded: String, key: String): String? {
        val marker = "\"$key\":["
        val start = decoded.indexOf(marker)
        if (start == -1) return null

        var bracket = 0
        var inString = false
        var end = -1

        for (i in (start + marker.length - 1) until decoded.length) {
            val c = decoded[i]
            if (c == '"' && (i == 0 || decoded[i - 1] != '\\')) inString = !inString
            if (!inString) {
                when (c) {
                    '[' -> bracket++
                    ']' -> {
                        bracket--
                        if (bracket == 0) { end = i; break }
                    }
                }
            }
        }
        if (end == -1) return null
        return decoded.substring(start + marker.length - 1, end + 1)
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return url
        return if (url.contains("cdn.ampproject.org")) {
            val regex = Regex("""cdn\.ampproject\.org/[^/]+/s/(.+)$""")
            val match = regex.find(url)
            if (match != null) "https://${match.groupValues[1]}" else url
        } else {
            url
        }
    }

    private fun decryptDizillaResponse(response: String): String? {
        try {
            val fullData = Base64.decode(response, Base64.DEFAULT)
            val ivSpec  = IvParameterSpec(ByteArray(16))
            val keySpec = SecretKeySpec(privateAESKey.toByteArray(Charsets.UTF_8), "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decryptedBytes = cipher.doFinal(fullData)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
