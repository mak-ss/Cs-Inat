package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.FormBody
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
    override var mainUrl = "https://net52.cc"   // ⬅️ isteklerle aynı domain
    override var name = "NetMirror"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }

    // Cookie cache + süre kontrolü
    @Volatile private var cookieValue: String = ""
    @Volatile private var cookieTime: Long = 0L
    private val cookieTtl = 10 * 60 * 1000L // 10 dk

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun isCookieValid(): Boolean =
        cookieValue.isNotEmpty() && (System.currentTimeMillis() - cookieTime) < cookieTtl

    private suspend fun bypass(): String {
        if (isCookieValid()) return cookieValue

        println("NetMirror DEBUG: bypass() - Yeni cookie aliniyor...")

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
                timeout = 30L   // ⬅️ explicit timeout
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

    private fun authHeaders(): Map<String, String> = mapOf(
        "Cookie" to "t_hash_t=$cookieValue; hd=on; ott=nf",
        "Referer" to "$mainUrl/home",
        "User-Agent" to ua
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        println("NetMirror DEBUG: getMainPage")

        bypass()
        if (cookieValue.isEmpty()) {
            println("NetMirror DEBUG: cookie alinamadi, cikiliyor")
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
        val items = document.select("div.slider-item")
        println("NetMirror DEBUG: slider-item=${items.size}")

        val homePageLists = mutableListOf<HomePageList>()

        if (items.isNotEmpty()) {
            val movies = items.mapNotNull { element ->
                val id = element.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = element.selectFirst("a[aria-label]")?.attr("aria-label")
                    ?: return@mapNotNull null
                val posterUrl = element.selectFirst("img")?.attr("src")
                    ?: element.selectFirst("img")?.attr("data-src")

                newMovieSearchResponse(title, id, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
                }
            }
            if (movies.isNotEmpty()) {
                homePageLists.add(HomePageList("NetMirror", movies))
            }
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse>? = null

    override suspend fun load(url: String): LoadResponse? {
        val id = url
        return newMovieLoadResponse("NetMirror Movie", id, TvType.Movie, id) {
            this.posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
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

        val playlistUrl = "$mainUrl/playlist.php?id=$id"
        println("NetMirror DEBUG: playlist=$playlistUrl")

        // ⬇️ Header + cookie + timeout eklendi
        val response = app.get(
            playlistUrl,
            headers = authHeaders(),
            interceptor = cloudflareKiller,
            timeout = 30L
        ).text

        val playlist = tryParseJson<NetMirrorPlaylistResponse>(response)
        if (playlist == null) {
            println("NetMirror DEBUG: playlist parse edilemedi: $response")
            return false
        }

        playlist.sources?.forEach { source ->
            val file = source.file ?: return@forEach
            val videoUrl = if (file.startsWith("/")) "$mainUrl$file"
                           else if (file.startsWith("//")) "https:$file"
                           else file

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = source.label ?: "NetMirror",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = getQualityFromName(source.label)
                    this.referer = "$mainUrl/"
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
