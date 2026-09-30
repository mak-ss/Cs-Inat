// ! Bu araç @Blockades tarafından yazılmıştır.
package com.Blockades

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.nodes.Document
import java.net.URLEncoder

class CizgiveDizi : MainAPI() {
    override var mainUrl = "https://cizgivedizi.com"
    override var name = "CizgiveDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Cartoon, TvType.Movie, TvType.Anime)

    // Yönetilebilir filtreler
    private val excludedTags = listOf("lgbt")

    // ─────────────────────────────────────────────────────────────
    // poolData JSON cache — ana sayfadan bir kez çekilir
    // ─────────────────────────────────────────────────────────────

    @Volatile private var cachedItems: List<PoolItem>? = null

    private suspend fun getPoolItems(): List<PoolItem> {
        cachedItems?.let { return it }
        val doc = app.get(mainUrl).document
        val items = parsePoolData(doc)
        cachedItems = items
        Log.d("CizgiVeDizi", "poolData parse edildi: ${items.size} öğe")
        return items
    }

    private fun parsePoolData(doc: Document): List<PoolItem> {
        val jsonScript = doc.selectFirst("script#poolData")?.data()
            ?: doc.selectFirst("script#poolData")?.html()
            ?: return emptyList()

        return runCatching {
            val mapper = jacksonObjectMapper()
            mapper.readValue<List<PoolItem>>(jsonScript)
        }.onFailure {
            Log.e("CizgiVeDizi", "poolData parse hatası", it)
        }.getOrDefault(emptyList())
    }

    // ─────────────────────────────────────────────────────────────
    // Ana Sayfa
    // ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "all"      to "Tüm İçerikler",
        "cizgi"    to "Çizgi Dizi",
        "anime"    to "Anime",
        "dizi"     to "Dizi",
        "film"     to "Film"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(listOf())

        val items = getPoolItems()
        val filtered = when (request.data) {
            "cizgi" -> items.filter { it.dataset.type == "cizgi" }
            "anime" -> items.filter { it.dataset.type == "anime" || it.dataset.anime == "1" }
            "dizi"  -> items.filter { it.dataset.type == "dizi" }
            "film"  -> items.filter { it.dataset.type == "film" }
            else    -> items
        }

        val results = filtered.mapNotNull { item ->
            if (item.dataset.hay.contains("lgbt", ignoreCase = true)) return@mapNotNull null

            val title = item.label.ifEmpty { item.dataset.name }
            if (title.isEmpty()) return@mapNotNull null

            val fullUrl = if (item.href.startsWith("http")) item.href else "$mainUrl${item.href}"
            val poster = fixImageFormat(item.poster)
            val isMovie = item.dataset.type == "film"

            if (isMovie) {
                newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, fullUrl, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, results)
    }

    // ─────────────────────────────────────────────────────────────
    // Arama
    // ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q = normalizeString(query.lowercase().trim())
        if (q.isEmpty()) return emptyList()

        val items = getPoolItems()
        return items.mapNotNull { item ->
            if (item.dataset.hay.contains("lgbt", ignoreCase = true)) return@mapNotNull null

            val title = item.label.ifEmpty { item.dataset.name }
            val hay = normalizeString(item.dataset.hay.lowercase())
            if (!hay.contains(q)) return@mapNotNull null

            val fullUrl = if (item.href.startsWith("http")) item.href else "$mainUrl${item.href}"
            val poster = fixImageFormat(item.poster)
            val isMovie = item.dataset.type == "film"

            if (isMovie) {
                newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, fullUrl, TvType.Cartoon) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ─────────────────────────────────────────────────────────────
    // Detay / Load
    // ─────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { app.get(url).document }.getOrNull() ?: return null
        val isMovie = url.contains("/film/")
        return if (!isMovie) loadSeries(doc, url) else loadMovie(doc, url)
    }

    private suspend fun loadSeries(doc: Document, url: String): LoadResponse? = runCatching {
        // Başlık
        val title = doc.selectFirst("div.infoLine h4")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifEmpty { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }

        // Poster
        val rawPoster = doc.selectFirst("picture img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val poster = fixImageFormat(fixRelativeUrl(rawPoster))

        // Konu
        val plot = doc.selectFirst("div.col-12 p")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty() }

        // Etiketler
        val tags = doc.select(".hero > div:nth-child(2) > div:nth-child(3) > p:nth-child(1)")
            .flatMap { it.text().split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Bölümler — HTML'deki a.bolum linkleri
        val episodes = doc.select("div.container a.bolum").mapNotNull { el ->
            val rawName = el.selectFirst(".card-title")?.text()?.trim()
                ?: el.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            // "1. Bölüm - Şu isim" gibi formatlardan numarayı çek
            val num = Regex("^(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)\.\s*Bölüm""").find(rawName)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0

            // Bölüm adı: numaradan sonraki kısım
            val epName = rawName.replace(Regex("^\\d+\\.?\\s*(Bölüm)?\\s*[-:]?\\s*"), "").trim()
                .ifEmpty { rawName }

            val href = fixRelativeUrl(el.attr("href")) ?: return@mapNotNull null
            val seasonN = el.attr("data-sezon").toIntOrNull() ?: 1

            newEpisode(href) {
                this.name    = epName
                this.episode = num
                this.season  = seasonN
            }
        }

        Log.d("CizgiVeDizi", "$url → ${episodes.size} bölüm bulundu")

        newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
        }
    }.onFailure {
        Log.e("CizgiVeDizi", "loadSeries hatası: $url", it)
    }.getOrNull()

    private suspend fun loadMovie(doc: Document, url: String): LoadResponse? = runCatching {
        val rawTitle = doc.selectFirst("h1.fw-light")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
        val title = "$rawTitle (film)"

        val rawPoster = doc.selectFirst("picture img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val poster = fixImageFormat(fixRelativeUrl(rawPoster))

        val plot = doc.selectFirst(".lead")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty() }

        val tags = doc.select(".hero > div:nth-child(2) > div:nth-child(3) > p:nth-child(1)")
            .flatMap { it.text().split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }.onFailure {
        Log.e("CizgiVeDizi", "loadMovie hatası: $url", it)
    }.getOrNull()

    // ─────────────────────────────────────────────────────────────
    // Link yükleme
    // ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = runCatching { app.get(data).document }.getOrNull() ?: return false

        val playPage = doc.selectFirst("a[href*='/play']")?.attr("href")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return false

        val playUrl = fixRelativeUrl(playPage) ?: return false

        return loadExtractor(playUrl, subtitleCallback, callback)
    }

    // ─────────────────────────────────────────────────────────────
    // Yardımcılar
    // ─────────────────────────────────────────────────────────────

    /** Göreli URL'i tam URL'e çevirir, HTML çöpünü reddeder. */
    private fun fixRelativeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        if (t.contains('"') || t.contains('<') || t.contains('>') || t.contains(' ')) return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "$mainUrl$t"
            else -> "$mainUrl/$t"
        }
    }

    /** Cloudinary fetch formatına çevirir. Geçersizse null. */
    private fun fixImageFormat(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith("http")) return null
        if (url.contains('"') || url.contains('<') || url.contains('>') || url.contains(' ')) return null
        return try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            "https://res.cloudinary.com/di0j4jsa8/image/fetch/f_auto/$encoded"
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeString(input: String) = input
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace('-', ' ').replace('_', ' ').replace('.', ' ')
}

// ─────────────────────────────────────────────────────────────
// poolData JSON modelleri
// ─────────────────────────────────────────────────────────────

data class PoolItem(
    val dataset: PoolDataset = PoolDataset(),
    val href: String = "",
    val poster: String = "",
    val label: String = "",
    val html: String = ""
)

data class PoolDataset(
    @JsonProperty("type")     val type: String = "",
    @JsonProperty("pin")      val pin: String = "",
    @JsonProperty("anime")    val anime: String = "0",
    @JsonProperty("animl")    val animl: String = "0",
    @JsonProperty("filmkind") val filmkind: String = "",
    @JsonProperty("hay")      val hay: String = "",
    @JsonProperty("hayEn")    val hayEn: String = "",
    @JsonProperty("name")     val name: String = "",
    @JsonProperty("id")       val id: String = "",
    @JsonProperty("order")    val order: String = "0",
    @JsonProperty("kanal")    val kanal: String = "",
    @JsonProperty("kanalraw") val kanalraw: String = "",
    @JsonProperty("kanaltext")val kanaltext: String = "",
    @JsonProperty("kanalicon")val kanalicon: String = "",
    @JsonProperty("kanalslayt")val kanalslayt: String = "",
    @JsonProperty("genres")   val genres: String = "",
    @JsonProperty("genresraw")val genresraw: String = ""
)
