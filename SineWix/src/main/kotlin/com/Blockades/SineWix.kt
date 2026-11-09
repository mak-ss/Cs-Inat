// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class SineWix : MainAPI() {
    override var mainUrl = "https://ydfvfdizipanel.ru/public/api"
    override var name = "SineWix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/genres/topteen/all" to "Top 10 Listesi",
        "${mainUrl}/genres/latestmovies/all" to "Son Eklenen Filmler",
        "${mainUrl}/genres/latestseries/all" to "Son Eklenen Diziler",
        "${mainUrl}/genres/latestanimes/all" to "Son Eklenen Animeler",
    )

    private val key = "9iQNC5HQwPlaFuJDkhncJ5XTJ8feGXOJatAA"
    private val headers = mapOf(
        "signature" to "308202c3308201aba0030201020204075cec01300d06092a864886f70d01010b050030123110300e0603550403130753696e65776978301e1" +
                "70d3231303932313233333334395a170d3436303931353233333334395a30123110300e0603550403130753696e6577697830820122300d06092a864" +
                "886f70d01010105000382010f003082010a0282010100b0a2a1bc5c3f16f19c3b2456cfd0a6128ced9f5e2e2c4cca1a100e17b07b86256258f372e76" +
                "a95a17e9e4a1c048e364835723a95e8ef6d5bdfb5694b50277c65a64f7b012fdf164e5dc93629561f6ca29b7dc82ebb3d6f3c8e8fc6795847fe331ad" +
                "4a13ed6c059a83804c43d3747526d769580f3a4153752eb22dac66dd15f1582caa43305dc49f55ac7b1b89013e654d2ca8c94c30956659674cc67325" +
                "6c04208f09118bae14cdd72d78f9ee2aece958084a8c2e315deff45726d4fc1f18ec39569ff1abe4f36a8d01090e5f68c07c28763513b88208bcac1a" +
                "6e1941f6fd8bfdd52f832098ddb2154c8f565bc5d58c7106a19e03787e75c7f34997000e3bcf30203010001a321301f301d0603551d0e04160414b54" +
                "5fc18e74a791d9402b53940ae38b96e9e209c300d06092a864886f70d01010b05000382010100a8a64d9e7c8b5db102af15d3caf94ff8d3e9be9008b" +
                "b0021117ca2f0762e68583354b126a041bb1fb6e6308e421e4b5a71f779cde63e5d2fc5976bff966c3c4034e852c077d8e74458fbae2ec1db74b1f40" +
                "82e188bf8ef7c42a44e3fbfb693bb00ee2a727096b42360ddce1bdcd3536f50c8693bcc62a7b7204bcefe2ecf1f7c820bcd63e1d7a6acc8bf6163086" +
                "915fc5f607cf51bc7a8635f98bb4c65a8f24b7b5a82c7b06868f565cb0d6ac4775c4aac777536ddd1a565f990fd8cbe539185fa7aab610b7855a687a" +
                "00f4e55536d72873444552c50fd10727dbf298a9be6ed6ae62148dd1de365f3729915dd31975e28a472d752ac14db3db548405cc31e1e",
        "hash256" to "f4d4bc98a3fc4600e7f2c2bab7533f1f03d8a70ff03c256bb11dc57050536bd0",
        "user-agent" to "EasyPlex (Android 13; SM-A546E; samsung; tr)"
    )

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private inline fun <reified T> safeReadValue(text: String): T? {
        return try {
            mapper.readValue<T>(text)
        } catch (e: Exception) {
            Log.e("SineWix", "JSON Hatası: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}/${key}?page=$page"
        Log.d("SineWixDebug", "İstek: $url")
        
        try {
            val responseText = app.get(url, headers = headers).text
            val rootNode = mapper.readTree(responseText)

            // Bazen API hata döndürürse kontrol et
            if (rootNode.isMissingNode || rootNode.isNull) {
                return newHomePageResponse(request.name, emptyList())
            }

            // API "data" veya "results" anahtarı kullanıyor olabilir, ikisini de kontrol et
            val dataNode = when {
                rootNode.has("data") -> rootNode.get("data")
                rootNode.has("results") -> rootNode.get("results")
                rootNode.isArray -> rootNode // Eğer direkt dizi dönüyorsa
                else -> null
            }

            val homeList = mutableListOf<SearchResponse>()
            
            if (dataNode != null && dataNode.isArray) {
                for (item in dataNode) {
                    val searchRes = item.toSearchResponse()
                    if (searchRes != null) {
                        homeList.add(searchRes)
                    }
                }
            } else if (dataNode != null && dataNode.isObject) {
                // Bazen "Top 10" verisi obje içinde "movies", "series" gibi alt başlıklarla gelebilir
                val fields = dataNode.fields()
                while (fields.hasNext()) {
                    val field = fields.next()
                    if (field.value.isArray) {
                        for (item in field.value) {
                            val searchRes = item.toSearchResponse()
                            if (searchRes != null) homeList.add(searchRes)
                        }
                    }
                }
            }
            
            Log.d("SineWixDebug", "Çekilen içerik sayısı: ${homeList.size}")
            return newHomePageResponse(request.name, homeList)

        } catch (e: Exception) {
            Log.e("SineWixDebug", "Ana sayfa yüklenirken hata: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    // JsonNode üzerinden dinamik olarak veri okuma (Hatalara karşı en dayanıklı yöntem)
    private fun JsonNode.toSearchResponse(): SearchResponse? {
        try {
            val id = this.findValue("id")?.asText()
            val type = this.findValue("type")?.asText() ?: "movie"
            val title = this.findValue("title")?.asText() 
                     ?: this.findValue("name")?.asText() 
                     ?: return null // Başlık yoksa atla
            
            val posterUrl = this.findValue("posterPath")?.asText() 
                         ?: this.findValue("poster_path")?.asText()
            val vote = this.findValue("vote")?.asDouble() 
                    ?: this.findValue("vote_average")?.asDouble()

            if (id == null) return null

            return when (type) {
                "movie" -> {
                    val href = "${mainUrl}/media/detail/${id}/${key}"
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(vote)
                    }
                }
                "serie", "series" -> {
                    val href = "${mainUrl}/series/show/${id}/${key}"
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(vote)
                    }
                }
                else -> { // Anime vb.
                    val href = "${mainUrl}/animes/show/${id}/${key}"
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(vote)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    // JsonNode içinde istenen key'i büyük/küçük harf duyarsız arayan yardımcı fonksiyon
    private fun JsonNode.findValue(keyName: String): JsonNode? {
        val fields = this.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            if (entry.key.equals(keyName, ignoreCase = true)) {
                return entry.value
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val text = app.get("${mainUrl}/search/${query}/${key}", headers = headers).text
            val rootNode = mapper.readTree(text)
            val resultsNode = rootNode.get("search") ?: rootNode.get("data") ?: rootNode
            
            if (resultsNode.isArray) {
                resultsNode.mapNotNull { it.toSearchResponse() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/media/detail/")) {
            movieDetail(url)
        } else {
            serieDetail(url)
        }
    }

    private suspend fun movieDetail(url: String): LoadResponse? {
        return try {
            val text = app.get(url, headers = headers).text
            val result = safeReadValue<SineMovie>(text) ?: return null
            
            val originalName = result.originalName ?: ""
            val name = result.title ?: ""
            val title = if (originalName.isNotBlank() && originalName != name) "$originalName - $name" else name
            val videoLink = result.videos?.getOrNull(0)?.link ?: return null

            newMovieLoadResponse(
                title,
                videoLink,
                TvType.Movie,
                videoLink
            ) {
                this.posterUrl = result.backdropPath ?: result.posterPath
                this.plot = result.overview
                this.year = result.releaseDate?.split("-")?.first()?.toIntOrNull()
                this.tags = result.genres?.map { it.name ?: "" }
                this.score = Score.from10(result.vote)
                this.duration = result.runtime?.toInt()
                addActors(result.cast?.map { Actor(it.name ?: "", it.profilePath) })
                if (result.trailer != null) {
                    addTrailer("https://www.youtube.com/embed/${result.trailer}")
                }
            }
        } catch (e: Exception) {
            Log.e("SineWix", "Film detay hatası: ${e.message}")
            null
        }
    }

    private suspend fun serieDetail(url: String): LoadResponse? {
        return try {
            val text = app.get(url, headers = headers).text
            val result = safeReadValue<SineSerie>(text) ?: return null
            
            val originalName = result.originalName ?: ""
            val name = result.name ?: ""
            val title = if (originalName.isNotBlank() && originalName != name) "$originalName - $name" else name

            val episodes = mutableListOf<Episode>()
            result.seasons?.forEach { season ->
                val seasonNumber = season.seasonNumber
                season.episodes?.forEach { ep ->
                    val videoLink = ep.videos?.getOrNull(0)?.link
                    if (!videoLink.isNullOrEmpty()) {
                        episodes.add(newEpisode(videoLink) {
                            this.name = ep.name
                            this.season = seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath
                        })
                    }
                }
            }

            val type = if (url.contains("/series/show/")) TvType.TvSeries else TvType.Anime
            
            return newTvSeriesLoadResponse(title.trim(), url, type, episodes) {
                this.posterUrl = result.backdropPath ?: result.posterPath
                this.year = result.releaseDate?.split("-")?.first()?.toIntOrNull()
                this.plot = result.overview
                this.tags = result.genres?.map { it ?: "" }
                this.score = Score.from10(result.vote)
                addActors(result.cast?.map { Actor(it.name ?: "", it.profilePath) })
                if (result.trailer != null) {
                    addTrailer("https://www.youtube.com/embed/${result.trailer}")
                }
            }
        } catch (e: Exception) {
            Log.e("SineWix", "Dizi detay hatası: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SWX", "data » $data")
        if (data.contains("snwaxdop")) {
            callback.invoke(
                newExtractorLink(
                    source = "SineWix",
                    name = "SineWix",
                    url = data,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
            loadExtractor(data, "${mainUrl}/", subtitleCallback, callback)
        }
        return true
    }
}
