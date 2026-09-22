package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl              = "https://dizibal.org"
    override var name                 = "DiziBal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes"  to "Animeler"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    private suspend fun safeGet(url: String, referer: String = mainUrl): Document? {
        return try {
            val res = app.get(url, headers = browserHeaders, referer = referer)
            if (isCloudflareChallenge(res.text)) {
                Log.e(name, "safeGet: Cloudflare challenge algılandı -> $url")
                null
            } else {
                res.document
            }
        } catch (e: Exception) {
            Log.e(name, "safeGet hatası: ${e.message} ->$url")
            null
        }
    }

    private fun isCloudflareChallenge(body: String): Boolean {
        return body.contains("challenges.cloudflare.com") || 
               body.contains("_cf_chl_opt") || 
               body.contains("Just a moment") || 
               body.contains("Attention Required")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}?page=$page"
        return try {
            val document = safeGet(url) ?: return newHomePageResponse(emptyList())

            val items: List<SearchResponse> = document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
                .take(60)

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/ara?q=$encodedQuery"

        return try {
            val document = safeGet(url) ?: return emptyList()

            document
                .select("a[href*='/series/'], a[href*='/movie/'], a[href*='/anime/']")
                .mapNotNull { element -> element.toCardSearchResponse() }
                .distinctBy { searchResponse -> searchResponse.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = safeGet(url) ?: return null
            val body = document.html()

            val type = when {
                url.contains("/movie/")  || url.contains("/film/")   -> TvType.Movie
                url.contains("/series/") || url.contains("/dizi/")   -> TvType.TvSeries
                url.contains("/anime/")  -> TvType.Anime
                else -> return null
            }

            val rawTitle = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val title = rawTitle.substringBefore("—").substringBefore(" - ").substringBefore(" izle").trim()

            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img[src*='/storage/']")?.attr("src")

            val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("p.whitespace-pre-line")?.text()?.trim()

            val year = Regex(""""datePublished"\s*:\s*"(\d{4})""")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()

            val score = Regex("""IMDB Puanı[^0-9]*([0-9]+[.,][0-9]+)""")
                .find(body)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()

            val tags: List<String> = document.select("a[href*='/tur/']")
                .map { element -> element.text().trim() }
                .filter { tag -> tag.isNotBlank() }
                .distinct()

            val trailerUrl: String? = document.selectFirst("iframe[src*=youtube]")?.attr("src")

            whenEklediğin C# / Kotlin `DiziBal.kt` eklenti kodunu ve `season.txt` HTML içeriğini inceledim. Video oynatılamamasının ve link çekilememesinin ana sebepleri şunlardır:

---

### 1. Temel Sorun: `loadLinks` Metodu Yanlış HTML Elementlerini/Özniteliklerini Seçiyor

Kotlin kodunda oyuncu kimliği (playerId) şu şekilde aranıyor:
```kotlin
val playerId = document.selectFirst("[data-pv]")?.attr("data-pv")
