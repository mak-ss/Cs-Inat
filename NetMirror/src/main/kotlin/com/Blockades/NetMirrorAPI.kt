package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.FormBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.UUID

data class NetMirrorPlaylistResponse(
    @JsonProperty("sources") val sources: List<NetMirrorSource>?,
    @JsonProperty("tracks") val tracks: List<NetMirrorTrack>?
)

data class NetMirrorSource(
    @JsonProperty("file") val file: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type") val type: String?
)

data class NetMirrorTrack(
    @JsonProperty("file") val file: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("language") val language: String?
)

class NetMirrorAPI : MainAPI() {
    override var mainUrl = "https://net52.cc"
    override var name = "NetMirror"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }

    @Volatile private var cookieValue: String = ""
    @Volatile private var cookieTime: Long = 0L
    @Volatile private var dataHash: String = ""
    @Volatile private var dataTime: String = ""
    private val cookieTtl = 10 * 60 * 1000L

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun isCookieValid(): Boolean =
        cookieValue.isNotEmpty() && (System.currentTimeMillis() - cookieTime) < cookieTtl

    private suspend fun bypass(): String {
        if (isCookieValid()) return cookieValue

        println("NetMirror DEBUG: bypass() - yeni cookie aliniyor")

        try {
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/verify2",
                "User-Agent" to ua
            )

            val body = FormBody.Builder()
                .add("g-recaptcha-response", UUID.randomUUID().toString())
                .build()

            val response = app.post(
                "$mainUrl/verify.php",
                requestBody = body,
                headers = headers,
                interceptor = cloudflareKiller,
                timeout = 30L
            )

            println("NetMirror DEBUG: bypass() status=${response.code}")

            val setCookie = response.headers["Set-Cookie"] ?: ""
            val match = Regex("t_hash_t=([^;]+)").find(setCookie)
            cookieValue = match?.groupValues?.get(1) ?: ""
            cookieTime = System.currentTimeMillis()

            println("NetMirror DEBUG: cookie='$cookieValue'")
            return cookieValue
        } catch (e: Exception) {
            println("NetMirror DEBUG: bypass HATA: ${e.message}")
            return ""
        }
    }

    /** HTML'deki body[data-hash] ve body[data-time] değerlerini çek */
    private fun parseBodyAttrs(doc: Document) {
        doc.body()?.let { body ->
            val h = body.attr("data-hash")
            val t = body.attr("data-time")
            if (h.isNotBlank()) dataHash = h
            if (t.isNotBlank()) dataTime = t
            println("NetMirror DEBUG: data-hash=$dataHash data-time=$dataTime")
        }
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "Cookie" to "t_hash_t=$cookieValue; hd=on; ott=nf",
        "Referer" to "$mainUrl/home",
        "User-Agent" to ua
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        println("NetMirror DEBUG: getMainPage")

        bypass()
        if (cookieValue.isEmpty()) {
            println("NetMirror DEBUG: cookie alinamadi")
            return null
        }

        val response = app.get(
            "$mainUrl/home",
            headers = authHeaders(),
            interceptor = cloudflareKiller,
            timeout = 30L
        )
        println("NetMirror DEBUG: status=${response.code}")

        val document = response.document
        parseBodyAttrs(document)

        val homePageLists = mutableListOf<HomePageList>()

        // HTML'de her kart: <div class="slider-item ..." data-post="ID">
        //   <a aria-label="Title">
        //     <img data-src="https://imgcdn.kim/poster/341/ID.jpg">
        document.select("div.lolomoRow").forEach { row ->
            val rowTitle = row.selectFirst("h2 .row-header-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "NetMirror"

            val items = row.select("div.slider-item[data-post]")
            if (items.isEmpty()) return@forEach

            val list = items.mapNotNull { el -> buildSearchResponse(el) }
            if (list.isNotEmpty()) {
                homePageLists.add(HomePageList(rowTitle, list))
            }
        }

        println("NetMirror DEBUG: toplam kategori=${homePageLists.size}")

        return newHomePageResponse(homePageLists)
    }

    /** HTML'e göre poster URL'lerini doğru şekilde çıkar */
    private fun buildSearchResponse(el: Element): SearchResponse? {
        val id = el.attr("data-post").takeIf { it.isNotBlank() } ?: return null

        // Başlık: a[aria-label] veya fallback-text
        val title = el.selectFirst("a[aria-label]")?.attr("aria-label")?.takeIf { it.isNotBlank() }
            ?: el.selectFirst("img.boxart-image")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: el.selectFirst("p.fallback-text")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        // Poster: data-src veya src
        val posterUrl = el.selectFirst("img.boxart-image")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() }
        }

        return newMovieSearchResponse(title, id, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        bypass()
        if (cookieValue.isEmpty()) return null

        val t = dataTime.ifBlank { (System.currentTimeMillis() / 1000).toString() }
        val response = app.get(
            "$mainUrl/search.php?s=${query}&t=$t",
            headers = authHeaders(),
            interceptor = cloudflareKiller,
            timeout = 30L
        )

        val text = response.text
        parseBodyAttrs(response.document)

        return try {
            val root = tryParseJson<Map<String, Any>>(text)
            val arr = root?.get("searchResult") as? List<*>
            arr?.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"]?.toString() ?: return@mapNotNull null
                val title = m["t"]?.toString() ?: id
                newMovieSearchResponse(title, id, TvType.Movie) {
                    this.posterUrl = "https://imgcdn.kim/poster/341/$id.jpg"
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
                }
            }
        } catch (e: Exception) {
            println("NetMirror DEBUG: search parse HATA: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url
        return newMovieLoadResponse("NetMirror", id, TvType.Movie, id) {
            this.posterUrl = "https://imgcdn.kim/poster/341/$id.jpg"
            this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data
        bypass()
        if (cookieValue.isEmpty()) {
            println("NetMirror DEBUG: loadLinks - cookie yok")
            return false
        }

        // HTML'deki player akışı:
        // 1. POST /play.php {id: VideoID} → { h: "hash" }
        // 2. iframe src: https://net52.cc/play.php?id=ID&<h>
        val playHeaders = authHeaders() + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val playBody = FormBody.Builder()
            .add("id", id)
            .build()

        val playResp = try {
            app.post(
                "$mainUrl/play.php",
                requestBody = playBody,
                headers = playHeaders,
                interceptor = cloudflareKiller,
                timeout = 30L
            )
        } catch (e: Exception) {
            println("NetMirror DEBUG: play.php HATA: ${e.message}")
            return false
        }

        val playJson = playResp.text
        println("NetMirror DEBUG: play.php yanit: $playJson")

        val h = tryParseJson<Map<String, Any>>(playJson)?.get("h")?.toString()
            ?: Regex("\"h\"\\s*:\\s*\"([^\"]+)\"").find(playJson)?.groupValues?.get(1)

        if (h.isNullOrBlank()) {
            println("NetMirror DEBUG: h bulunamadi")
            return false
        }

        // Gerçek video sayfası
        val playerUrl = "$mainUrl/play.php?id=$id&$h"
        println("NetMirror DEBUG: playerUrl=$playerUrl")

        val playerResp = app.get(
            playerUrl,
            headers = authHeaders() + mapOf("Referer" to "$mainUrl/"),
            interceptor = cloudflareKiller,
            timeout = 30L
        )

        val playerHtml = playerResp.text

        // 1) Direkt playlist JSON geldiyse parse et
        if (parsePlaylist(playerHtml, subtitleCallback, callback)) {
            return true
        }

        // 2) HTML içinden m3u8 linklerini yakala
        val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        val m3u8List = m3u8Regex.findAll(playerHtml).map { it.value }.distinct().toList()
        if (m3u8List.isNotEmpty()) {
            m3u8List.forEach { url ->
                callback.invoke(
                    newExtractorLink(this.name, "NetMirror", url, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        println("NetMirror DEBUG: link bulunamadi")
        return false
    }

    /** Suspend — çünkü içinde newExtractorLink ve newSubtitleFile çağrılıyor */
    private suspend fun parsePlaylist(
        text: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playlist = tryParseJson<NetMirrorPlaylistResponse>(text) ?: return false
        if (playlist.sources.isNullOrEmpty()) return false

        playlist.sources.forEach { source ->
            val file = source.file ?: return@forEach
            val videoUrl = when {
                file.startsWith("//") -> "https:$file"
                file.startsWith("/")  -> "$mainUrl$file"
                else -> file
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    source.label ?: "NetMirror",
                    videoUrl,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(source.label)
                }
            )
        }

        playlist.tracks?.forEach { track ->
            if (track.kind == "captions" && track.file != null) {
                val subUrl = when {
                    track.file.startsWith("//") -> "https:${track.file}"
                    track.file.startsWith("/")  -> "$mainUrl${track.file}"
                    else -> track.file
                }
                subtitleCallback.invoke(
                    newSubtitleFile(track.language ?: "en", subUrl)
                )
            }
        }
        return true
    }
}
