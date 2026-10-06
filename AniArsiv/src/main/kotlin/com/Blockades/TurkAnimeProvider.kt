package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AniArşiv (Eski Türk Anime TV Modülü) Sağlayıcısı (v6)
 *
 * Kaynak: https://aniarsiv.com
 * Arşiv: 6.000+ Anime, 70.000+ Bölüm, Türkçe Altyazı
 * Oynatıcılar: AniArşiv Özel Sunucu (1080p MP4), Sibnet, GDrive, Vidmoly, Voe, OK.ru, Mail.ru vb.
 */
class TurkAnimeProvider : MainAPI() {

    override var mainUrl = "https://aniarsiv.com"
    override var name = "AniArşiv"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    // -------------------------------------------------------------------------
    // Ana Sayfa
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "trending" to "🔥 Trend Animeler",
        "latest"   to "⚡ Yeni Eklenen Bölümler",
        "popular"  to "📚 Popüler & Güncel Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = mutableListOf<SearchResponse>()

        try {
            when (request.data) {
                "trending" -> {
                    val res = app.get("$mainUrl/api/trending", headers = commonHeaders, timeout = 10).text
                    val jsonArray = JSONArray(res)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                        val title = obj.optString("baslik", slug)
                        val poster = obj.optString("cover_image").takeIf { it.isNotBlank() }
                            ?: obj.optString("banner_image").takeIf { it.isNotBlank() }
                            ?: fixImageUrl(obj.optString("resim"))
                        val score = obj.optDouble("puani", 0.0)

                        list.add(newAnimeSearchResponse(title, slug, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
                "latest" -> {
                    val res = app.get("$mainUrl/api/episodes/latest?limit=24", headers = commonHeaders, timeout = 10).text
                    val jsonArray = JSONArray(res)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val animeSlug = obj.optString("anime_slug").takeIf { it.isNotBlank() } ?: continue
                        val animeTitle = obj.optString("anime_title", animeSlug)
                        val epName = obj.optString("episode_name")
                        val poster = obj.optString("cover_image").takeIf { it.isNotBlank() }
                            ?: fixImageUrl(obj.optString("banner_image"))

                        val displayTitle = if (epName.isNotBlank()) "$animeTitle ($epName)" else animeTitle
                        list.add(newAnimeSearchResponse(displayTitle, animeSlug, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
                "popular" -> {
                    val p = if (page < 1) 1 else page
                    val res = app.get("$mainUrl/api/animes?limit=24&page=$p", headers = commonHeaders, timeout = 10).text
                    val jsonObj = JSONObject(res)
                    val items = jsonObj.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                        val title = obj.optString("baslik", slug)
                        val poster = obj.optString("cover_image").takeIf { it.isNotBlank() }
                            ?: fixImageUrl(obj.optString("resim"))

                        list.add(newAnimeSearchResponse(title, slug, TvType.Anime) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AniArsiv", "getMainPage hatası (${request.data}): ${e.message}")
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = list.distinctBy { it.url },
                isHorizontalImages = request.data == "trending"
            ),
            hasNext = request.data == "popular" && list.isNotEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoint = "$mainUrl/api/animes?q=$q&limit=25"
        val list = mutableListOf<SearchResponse>()

        try {
            val res = app.get(endpoint, headers = commonHeaders, timeout = 10).text
            val jsonObj = JSONObject(res)
            val items = jsonObj.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("baslik", slug)
                val poster = obj.optString("cover_image").takeIf { it.isNotBlank() }
                    ?: fixImageUrl(obj.optString("resim"))

                list.add(newAnimeSearchResponse(title, slug, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
        } catch (e: Exception) {
            Log.e("AniArsiv", "search hatası: ${e.message}")
        }

        return list.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // -------------------------------------------------------------------------
    // Anime Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val slug = extractSlug(url)
        val endpoint = "$mainUrl/api/anime/$slug"

        return try {
            val res = app.get(endpoint, headers = commonHeaders, timeout = 10).text
            val obj = JSONObject(res)

            val title = obj.optString("baslik").takeIf { it.isNotBlank() } ?: slug
            val plot = obj.optString("ozet").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("anilist")?.optString("description")
            val poster = obj.optString("cover_image").takeIf { it.isNotBlank() }
                ?: fixImageUrl(obj.optString("resim"))
            val banner = obj.optString("banner_image").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("anilist")?.optString("bannerImage")
            val year = obj.optInt("yil").takeIf { it > 0 }
            val rating = obj.optDouble("puani", 0.0)
            val category = obj.optString("kategori", "TV")
            val tvType = if (category.contains("Movie", ignoreCase = true) || category.contains("Film", ignoreCase = true)) {
                TvType.AnimeMovie
            } else if (category.contains("OVA", ignoreCase = true)) {
                TvType.OVA
            } else {
                TvType.Anime
            }

            val tagsList = mutableListOf<String>()
            val rawGenres = obj.optString("turler")
            if (rawGenres.isNotBlank()) {
                rawGenres.split(",").forEach { g ->
                    val trimmed = g.trim()
                    if (trimmed.isNotBlank()) tagsList.add(trimmed)
                }
            }

            val bolumlerArr = obj.optJSONArray("bolumler") ?: JSONArray()
            val episodesList = mutableListOf<Episode>()

            for (i in 0 until bolumlerArr.length()) {
                val epObj = bolumlerArr.getJSONObject(i)
                val epSlug = epObj.optString("slug").takeIf { it.isNotBlank() } ?: continue
                val epName = epObj.optString("ad").takeIf { it.isNotBlank() } ?: "Bölüm ${i + 1}"
                val epIndex = epObj.optInt("index", i + 1)

                episodesList.add(newEpisode(epSlug) {
                    this.name = epName
                    this.episode = epIndex
                })
            }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.year = year
                this.tags = tagsList
                addEpisodes(DubStatus.Subbed, episodesList)
            }
        } catch (e: Exception) {
            Log.e("AniArsiv", "load hatası ($slug): ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Video Linkleri & Oynatıcılar
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epSlug = extractSlug(data)
        val endpoint = "$mainUrl/api/bolum/$epSlug"
        var linksFound = false

        try {
            val res = app.get(endpoint, headers = commonHeaders, timeout = 10).text
            val obj = JSONObject(res)

            // 1. ÖNCELİK: AniArşiv Özel Sunucu (Doğrudan Ultra HD MP4 Akışı)
            val cdnVideo = obj.optJSONObject("cdn_video")
            if (cdnVideo != null) {
                val directMp4 = cdnVideo.optString("video_url").takeIf { it.isNotBlank() }
                if (directMp4 != null) {
                    val height = cdnVideo.optInt("height", 1080)
                    val qualityVal = if (height >= 1080) Qualities.P1080.value else Qualities.P720.value
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "AniArşiv Özel Sunucu (${height}p MP4)",
                            url = directMp4,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                "Referer" to "$mainUrl/"
                            )
                            this.quality = qualityVal
                        }
                    )
                    linksFound = true
                }

                val embedUrl = cdnVideo.optString("embed_url").takeIf { it.isNotBlank() }
                if (embedUrl != null && !embedUrl.contains("about:blank")) {
                    loadExtractor(embedUrl, subtitleCallback, callback)
                    linksFound = true
                }
            }

            // 2. ÖNCELİK: Çoklu Fansub ve Alternatif Oynatıcılar (Sibnet, GDrive, Vidmoly, Voe, OK.ru, Mail.ru vb.)
            val linksArr = obj.optJSONArray("links") ?: JSONArray()
            for (i in 0 until linksArr.length()) {
                val linkObj = linksArr.getJSONObject(i)
                val rawUrl = linkObj.optString("url").takeIf { it.isNotBlank() }
                    ?: linkObj.optString("raw_deger").takeIf { it.isNotBlank() }
                    ?: linkObj.optString("deger").takeIf { it.isNotBlank() }
                    ?: continue

                val player = linkObj.optString("player", "Video").trim().uppercase()
                val fansub = linkObj.optString("fansub", "Varsayılan").trim()
                val cleanUrl = rawUrl.replace("\\/", "/").trim()

                // CloudStream yerleşik extractor'larını tetikle
                val handled = loadExtractor(cleanUrl, subtitleCallback, callback)
                if (handled) {
                    linksFound = true
                    continue
                }

                // Extractor bulunamadıysa doğrudan link veya embed kontrolü
                when {
                    cleanUrl.contains(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$fansub [$player]",
                                url = cleanUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("Referer" to "$mainUrl/")
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linksFound = true
                    }
                    cleanUrl.contains(".m3u8") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$fansub [$player - HLS]",
                                url = cleanUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf("Referer" to "$mainUrl/")
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linksFound = true
                    }
                    cleanUrl.contains("sibnet.ru") -> {
                        loadExtractor(cleanUrl, subtitleCallback, callback)
                        linksFound = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AniArsiv", "loadLinks hatası ($epSlug): ${e.message}")
        }

        return linksFound
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val modifiedRequest = originalRequest.newBuilder()
                .removeHeader("If-None-Match")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                .header("Referer", "$mainUrl/")
                .build()
            chain.proceed(modifiedRequest)
        }
    }

    // -------------------------------------------------------------------------
    // Yardımcı Araçlar
    // -------------------------------------------------------------------------

    private fun extractSlug(raw: String): String {
        return raw.trim()
            .removePrefix(mainUrl)
            .removePrefix("https://aniarsiv.com")
            .removePrefix("/anime/")
            .removePrefix("/api/anime/")
            .removePrefix("/bolum/")
            .removePrefix("/api/bolum/")
            .removePrefix("#/izle/")
            .removePrefix("#/anime/")
            .trim('/')
            .split("?")[0]
            .split("#")[0]
    }

    private fun fixImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("/")) "$mainUrl$raw" else raw
    }
}
