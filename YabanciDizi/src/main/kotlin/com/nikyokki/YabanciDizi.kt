package com.Blockades

import CryptoJS
import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toRatingInt
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class YabanciDizi : MainAPI() {
    override var mainUrl = "https://yabancidizi.news"
    override var name = "YabanciDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Güvenlik taramasından geçiriliyorsunuz. Lütfen bekleyiniz..")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi/tur/aile-izle" to "Aile",
        "${mainUrl}/dizi/tur/aksiyon-izle-1" to "Aksiyon",
        "${mainUrl}/dizi/tur/bilim-kurgu-izle-1" to "Bilim Kurgu",
        "${mainUrl}/dizi/tur/belgesel" to "Belgesel",
        "${mainUrl}/dizi/tur/dram-izle" to "Dram",
        "${mainUrl}/dizi/tur/fantastik-izle" to "Fantastik",
        "${mainUrl}/dizi/tur/gerilim-izle" to "Gerilim",
        "${mainUrl}/dizi/tur/gizem-izle" to "Gizem",
        "${mainUrl}/dizi/tur/komedi-izle" to "Komedi",
        "${mainUrl}/dizi/tur/korku-izle" to "Korku",
        "${mainUrl}/dizi/tur/macera-izle" to "Macera",
        "${mainUrl}/dizi/tur/romantik-izle-1" to "Romantik",
        "${mainUrl}/dizi/tur/suc" to "Suç",
        "${mainUrl}/dizi/tur/kore-dizileri" to "Kore Dizileri",
        "${mainUrl}/dizi/tur/stand-up" to "Stand Up",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}", interceptor = interceptor).document
        val home = document.select("div.mofy-movbox").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.mofy-movbox-text a")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val score = this.selectFirst("div.mofy-movpoint span")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search?qr=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "${mainUrl}/",
            interceptor = interceptor
        )

        val parsedSafe = response.parsedSafe<JsonResponse>()
        val results = mutableListOf<SearchResponse>()

        if (parsedSafe?.success == 1) {
            parsedSafe.data.result.forEach {
                val title = it.s_name
                val posterUrl = fixUrlNull("$mainUrl/uploads/series/${it.s_image}") ?: ""
                if (it.s_type == "0") {
                    val href = fixUrlNull("$mainUrl/dizi/${it.s_link}") ?: return@forEach
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                } else if (it.s_type == "1") {
                    val href = fixUrlNull("$mainUrl/film/${it.s_link}") ?: return@forEach
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )
        val document = app.get(url, referer = mainUrl, headers = headers, interceptor = interceptor).document

        val title = document.selectFirst("h1.page-title")?.text()?.trim() ?: "Title"
        val poster = fixUrlNull(document.selectFirst("div#series-profile-wrapper img")?.attr("src")) ?: ""
        val year = document.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()
        val description = document.selectFirst("div.series-summary-wrapper p")?.text()?.trim()
        val tags = mutableListOf<String>()
        document.selectFirst("div.ui.list")?.select("a")?.forEach {
            if (!it.attr("href").contains("/oyuncu/")) {
                tags.add(it.text().trim())
            }
        }
        val rating = document.selectFirst("div.color-imdb")?.text()?.trim()
        val duration = document.selectXpath("//div[text()='Süre']//following-sibling::div")
            .text().trim().split(" ").first().toIntOrNull()
        val trailer = document.selectFirst("div.media-trailer")?.attr("data-yt")
        val actors = document.selectFirst("div.global-box")?.select("div.item")?.map {
            Actor(it.selectFirst("h5")!!.text(), fixUrlNull(it.selectFirst("img")!!.attr("src")))
        }
        if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tabular-content").forEach {
                val epSeason = it.parent()?.attr("data-season")?.toIntOrNull()
                var epEpisode = 0
                it.select("div.item").forEach ep@{ episodeElement ->
                    val epHref = fixUrlNull(episodeElement.selectFirst("h6 a")?.attr("href")) ?: return@ep
                    epEpisode++
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "${epSeason}. Sezon ${epEpisode}. Bölüm"
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                if (!trailer.isNullOrEmpty()) addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                addActors(actors)
                if (!trailer.isNullOrEmpty()) addTrailer("https://www.youtube.com/embed/${trailer}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("YBD", "data » $data")
        val document = app.get(data, interceptor = interceptor).document
        val timestampMillis = System.currentTimeMillis()

        val tabs = document.select("div#series-tabs a")
        Log.d("YBD", "Bulunan tab sayısı: ${tabs.size}")

        tabs.forEachIndexed { index, tab ->
            val dataEid = tab.attr("data-eid")
            val dataType = tab.attr("data-type")
            Log.d("YBD", "Tab[$index] dataEid=$dataEid dataType=$dataType")

            if (dataEid.isEmpty()) {
                Log.d("YBD", "Tab[$index] dataEid boş, atlanıyor")
                return@forEachIndexed
            }

            val dilAd = if (dataType == "2") "Dublaj" else "Altyazı"

            val doc = try {
                app.post(
                    "$mainUrl/ajax/service",
                    referer = data,
                    headers = mapOf(
                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Cookie" to "udys=$timestampMillis",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "lang" to dataType,
                        "episode" to dataEid,
                        "type" to "langTab"
                    ),
                    interceptor = interceptor
                ).parsedSafe<Series>()
            } catch (e: Exception) {
                Log.e("YBD", "ajax/service hatası: ${e.message}")
                null
            }

            if (doc?.success != 1 || doc.data.isNullOrEmpty()) {
                Log.d("YBD", "API başarısız veya data boş: success=${doc?.success}")
                return@forEachIndexed
            }

            val doca = Jsoup.parse(doc.data)
            val items = doca.select("div.item")
            Log.d("YBD", "Bulunan kaynak sayısı: ${items.size}")

            items.forEach { item ->
                val name = item.text()
                val dataLink = item.attr("data-link")
                Log.d("YBD", "Kaynak: name='$name' dataLink='$dataLink'")

                if (dataLink.isEmpty()) {
                    Log.d("YBD", "dataLink boş, atlanıyor")
                    return@forEach
                }

                val linkPath = dataLink.replace("/", "_").replace("+", "-")

                when {
                    name.contains("Mac", ignoreCase = true) -> {
                        try {
                            val mac = app.get(
                                "$mainUrl/api/drive/$linkPath",
                                referer = "$mainUrl/",
                                headers = mapOf(
                                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                                    "Cookie" to "udys=$timestampMillis"
                                ),
                                interceptor = interceptor
                            ).document

                            var subFrame = mac.selectFirst("iframe")?.attr("src") ?: ""
                            Log.d("YBD", "Mac subFrame (drive): '$subFrame'")

                            if (subFrame.isEmpty()) {
                                val ts = System.currentTimeMillis() / 1000
                                Log.d("YBD", "Drive boş, drives endpoint deneniyor ts=$ts")
                                val drives = app.get(
                                    "$mainUrl/api/drives/$linkPath?t=$ts",
                                    referer = "$mainUrl/api/drives/$linkPath",
                                    headers = mapOf(
                                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                                        "Cookie" to "udys=$timestampMillis"
                                    ),
                                    interceptor = interceptor
                                ).document
                                subFrame = drives.selectFirst("iframe")?.attr("src") ?: ""
                                Log.d("YBD", "Mac subFrame (drives): '$subFrame'")
                            }

                            if (subFrame.isNotEmpty()) {
                                loadMac(subFrame, callback, dilAd, name)
                            } else {
                                Log.d("YBD", "Mac subFrame tamamen boş")
                            }
                        } catch (e: Exception) {
                            Log.e("YBD", "Mac işleme hatası: ${e.message}")
                        }
                    }

                    name.contains("VidMoly", ignoreCase = true) -> {
                        try {
                            val vdm = app.get(
                                "$mainUrl/api/moly/$linkPath",
                                referer = "$mainUrl/",
                                headers = mapOf(
                                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                                    "Cookie" to "udys=$timestampMillis"
                                ),
                                interceptor = interceptor
                            ).document
                            val subFrame = vdm.selectFirst("iframe")?.attr("src") ?: ""
                            Log.d("YBD", "VidMoly subFrame: '$subFrame'")

                            if (subFrame.isNotEmpty()) {
                                loadExtractor(subFrame, "$mainUrl/", subtitleCallback) { link ->
                                    callback.invoke(
                                        ExtractorLink(
                                            source = "$dilAd - ${link.name}",
                                            name = "$dilAd - ${link.name}",
                                            url = link.url,
                                            referer = link.referer,
                                            quality = link.quality,
                                            headers = link.headers,
                                            extractorData = link.extractorData,
                                            type = link.type
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("YBD", "VidMoly işleme hatası: ${e.message}")
                        }
                    }

                    name.contains("Okru", ignoreCase = true) -> {
                        try {
                            val okr = app.get(
                                "$mainUrl/api/ruplay/$linkPath",
                                referer = "$mainUrl/",
                                headers = mapOf(
                                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                                    "Cookie" to "udys=$timestampMillis"
                                ),
                                interceptor = interceptor
                            ).document
                            val subFrame = okr.selectFirst("iframe")?.attr("src") ?: ""
                            Log.d("YBD", "Okru subFrame: '$subFrame'")

                            if (subFrame.isNotEmpty()) {
                                loadExtractor(subFrame, "$mainUrl/", subtitleCallback) { link ->
                                    callback.invoke(
                                        ExtractorLink(
                                            source = "$dilAd - ${link.name}",
                                            name = "$dilAd - ${link.name}",
                                            url = link.url,
                                            referer = link.referer,
                                            quality = link.quality,
                                            headers = link.headers,
                                            extractorData = link.extractorData,
                                            type = link.type
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("YBD", "Okru işleme hatası: ${e.message}")
                        }
                    }

                    else -> {
                        Log.d("YBD", "Bilinmeyen kaynak tipi: $name")
                    }
                }
            }
        }

        return true
    }

    private suspend fun loadMac(
        subFrame: String,
        callback: (ExtractorLink) -> Unit,
        dilAd: String,
        name: String
    ) {
        Log.d("YBD", "loadMac subFrame -> $subFrame")

        val iDoc = try {
            app.get(
                subFrame,
                referer = "$mainUrl/",
                headers = mapOf(
                    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"
                ),
                interceptor = interceptor
            ).text
        } catch (e: Exception) {
            Log.e("YBD", "loadMac iDoc hatası: ${e.message}")
            return
        }

        val cryptData = Regex("""CryptoJS\.AES\.decrypt\("(.*?)","""").find(iDoc)?.groupValues?.get(1) ?: ""
        val cryptPass = Regex("""","(.*?)"\);""").find(iDoc)?.groupValues?.get(1) ?: ""

        Log.d("YBD", "cryptData='$cryptData'")
        Log.d("YBD", "cryptPass='$cryptPass'")

        if (cryptData.isEmpty() || cryptPass.isEmpty()) {
            Log.d("YBD", "CryptoJS verisi bulunamadı")
            return
        }

        val decryptedData = try {
            CryptoJS.decrypt(cryptPass, cryptData)
        } catch (e: Exception) {
            Log.e("YBD", "Decrypt hatası: ${e.message}")
            return
        }

        val decryptedDoc = Jsoup.parse(decryptedData)
        val vidUrl = Regex("""file:\s*['"](.*?)['"]""").find(decryptedDoc.html())?.groupValues?.get(1) ?: ""
        Log.d("YBD", "vidUrl='$vidUrl'")

        if (vidUrl.isEmpty()) {
            Log.d("YBD", "vidUrl bulunamadı")
            return
        }

        callback.invoke(
            newExtractorLink(
                source = "$dilAd - $name",
                name = "$dilAd - $name",
                url = vidUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                    "Referer" to mainUrl
                )
                this.quality = Qualities.Unknown.value
            }
        )

        // M3U8'den kalite listesini çek
        val m3u8Body = try {
            app.get(
                vidUrl,
                referer = "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0"
                ),
                interceptor = interceptor
            ).text
        } catch (e: Exception) {
            Log.e("YBD", "M3U8 çekme hatası: ${e.message}")
            return
        }

        val streamList = extractStreamInfoWithRegex(m3u8Body)
        Log.d("YBD", "Bulunan kalite sayısı: ${streamList.size}")

        for (sonUrl in streamList) {
            Log.d("YBD", "Kalite: ${sonUrl.resolution} -> ${sonUrl.link}")
            callback.invoke(
                newExtractorLink(
                    source = "$dilAd - $name - ${sonUrl.resolution}",
                    name = "$dilAd - $name - ${sonUrl.resolution}",
                    url = sonUrl.link,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = vidUrl
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) Gecko/20100101 Firefox/135.0",
                        "Referer" to vidUrl
                    )
                    this.quality = getQualityFromName(sonUrl.resolution)
                }
            )
        }
    }

    /**
     * M3U8 içeriğinden #EXT-X-STREAM-INF bloklarını ve ardından gelen URL'leri çıkarır.
     * Regex tabanlı değil, satır bazlı parser kullanır çünkü URL genelde bir sonraki satırdadır.
     */
    private fun extractStreamInfoWithRegex(m3uString: String): List<StreamInfo> {
        val result = mutableListOf<StreamInfo>()
        var currentResolution: String? = null

        for (rawLine in m3uString.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val resMatch = Regex("""RESOLUTION=([^\s,]+)""").find(line)
                    currentResolution = resMatch?.groupValues?.get(1)
                }
                line.startsWith("http") && currentResolution != null -> {
                    result.add(StreamInfo(currentResolution, line))
                    currentResolution = null
                }
            }
        }

        return result
    }
}

data class StreamInfo(val resolution: String, val link: String)
