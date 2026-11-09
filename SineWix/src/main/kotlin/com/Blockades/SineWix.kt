// ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

class SineWix : MainAPI() {
    override var mainUrl = "https://www.filmmodu.one"
    override var name = "SineWix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film-ara?q=" to "Film Ara"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<SearchResponse>()
        
        try {
            val res = app.get(mainUrl, headers = headers).text
            val regex = Regex("""<div class="col-md-2 col-xs-6 movie">[\s\S]*?<a href="([^"]+)">([^<]+)<\/a>""")
            regex.findAll(res).forEach { match ->
                val url = match.groupValues[1]
                val title = match.groupValues[2].trim()
                home.add(newMovieSearchResponse(title, url, TvType.Movie))
            }
        } catch (e: Exception) {
            Log.e("SineWix", "Ana sayfa hatası: ${e.message}")
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        try {
            val searchUrl = "$mainUrl/film-ara?q=" + URLEncoder.encode(query, "UTF-8")
            val res = app.get(searchUrl, headers = headers).text
            val regex = Regex("""<div class="col-md-2 col-xs-6 movie">[\s\S]*?<a href="([^"]+)">([^<]+)<\/a>""")
            
            regex.findAll(res).forEach { match ->
                val url = match.groupValues[1]
                val title = match.groupValues[2].trim()
                
                val type = if (url.contains("/dizi/")) TvType.TvSeries else TvType.Movie
                
                if (type == TvType.Movie) {
                    results.add(newMovieSearchResponse(title, url, type))
                } else {
                    results.add(newTvSeriesSearchResponse(title, url, type))
                }
            }
        } catch (e: Exception) {
            Log.e("SineWix", "Arama hatası: ${e.message}")
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, headers = headers).text
            
            val titleMatch = Regex("""<h1[^>]*>([^<]+)</h1>""").find(res)
            val title = titleMatch?.groupValues?.get(1)?.trim() ?: "Bilinmeyen Başlık"
            
            val posterMatch = Regex("""<img[^>]+src="([^"]+)"[^>]+class="img-responsive"""">""").find(res)
            val poster = posterMatch?.groupValues?.get(1)

            val type = if (url.contains("/dizi/")) TvType.TvSeries else TvType.Movie
            
            if (type == TvType.Movie) {
                newMovieLoadResponse(title, url, type, url) {
                    this.posterUrl = poster
                    this.plot = "FilmModu üzerinden çekilen içerik."
                }
            } else {
                val episodes = listOf(newEpisode(url) { this.name = "Bölüm 1" })
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = poster
                    this.plot = "FilmModu üzerinden çekilen içerik."
                }
            }
        } catch (e: Exception) {
            Log.e("SineWix", "Detay yükleme hatası: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val pageUrl = data
            val res = app.get(pageUrl, headers = headers).text
            
            val altPages = mutableListOf<Pair<String, String>>()
            val altRegex = Regex("""<a href="([^"]+)" type="button" class="btn [^"]*">([^<]+)<\/a>""")
            
            altRegex.findAll(res).forEach { match ->
                val aUrl = match.groupValues[1]
                val aName = match.groupValues[2].trim()
                if (!aName.contains("Fragman")) {
                    altPages.add(aUrl to aName)
                }
            }
            
            if (altPages.isEmpty()) altPages.add(pageUrl to "Türkçe")

            for ((altUrl, altName) in altPages) {
                val aRes = app.get(altUrl, headers = headers).text
                
                val idMatch = Regex("""var videoId = '(\d+)';""").find(aRes)
                val typeMatch = Regex("""var videoType = '([^']*)';""").find(aRes)
                
                if (idMatch == null) continue
                
                val videoId = idMatch.groupValues[1]
                val videoType = typeMatch?.groupValues?.get(1) ?: ""
                
                val sourceApiUrl = "$mainUrl/get-source?movie_id=$videoId&type=$videoType"
                val sourceRes = app.get(sourceApiUrl, headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")).text
                
                val json = JSONObject(sourceRes)
                val sources = json.optJSONArray("sources")
                
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val srcItem = sources.getJSONObject(i)
                        var srcUrl = srcItem.optString("src")
                        if (srcUrl.isEmpty()) continue
                        
                        if (!srcUrl.contains(".m3u8")) srcUrl += ".m3u8"
                        
                        val label = srcItem.optString("label").ifEmpty { 
                            if (srcItem.has("res")) "${srcItem.optString("res")}p" else "1080p" 
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                source = "SineWix",
                                name = "SineWix - $label [$altName]",
                                url = srcUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = when {
                                    label.contains("1080") -> Qualities.P1080.value
                                    label.contains("720") -> Qualities.P720.value
                                    label.contains("480") -> Qualities.P480.value
                                    else -> Qualities.Unknown.value
                                }
                                this.headers = headers
                            }
                        )
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("SineWix", "Link çıkarma hatası: ${e.message}")
            return false
        }
    }
}
