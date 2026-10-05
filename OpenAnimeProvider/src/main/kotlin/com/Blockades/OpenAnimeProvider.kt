package com.Blockades

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAnime Sağlayıcısı
 *
 * Site: https://openani.me
 * Yapı: SvelteKit SPA — /__data.json endpoint'leri ve NDJSON stream chunk'ları üzerinden veri çekilir
 * Video: CDN üzerinden doğrudan MP4 (4K 2160p / 1080p / 720p / 480p)
 * Fansub: Akatsuki, Adonis, NetRip (4K), PuzzleSubs, SoutenSubs, Türkçe Dublaj, vb.
 *
 * Dosya formatı: {epNum}-{fansub_id}-{res}p.mp4  (normal fansublar)
 *                {epNum}-0-{res}p.mp4             (NetRip / 4K standalone)
 */
class OpenAnimeProvider : MainAPI() {

    override var mainUrl = "https://openani.me"
    override var name = "OpenAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // CDN host — ana sayfadan dinamik alınır
    private var cdnHost = "https://de2---vn-t9g4tsan-5qcl.yeshi.eu.org"
    private var isInitialized = false

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, */*",
        "Referer" to "$mainUrl/"
    )

    // -------------------------------------------------------------------------
    // Init — CDN host güncelleme (mainUrl sabit, GitHub uyumlu)
    // -------------------------------------------------------------------------

    private suspend fun ensureInit() {
        if (isInitialized) return
        isInitialized = true

        // Domain güncelleme kaldırıldı — mainUrl sabit kalır.
        // CDN host'u ana sayfadan dinamik olarak alınır.
        try {
            val pair = fetchRawData("/") ?: return
            val (raw, root) = pair
            val cdnIdx = root.optInt("random_cdn_host", -1)
            if (cdnIdx >= 0) {
                val cdnRaw = raw.getOrNull(cdnIdx)
                if (cdnRaw is String && cdnRaw.startsWith("http")) {
                    cdnHost = cdnRaw.trimEnd('/')
                }
            }
        } catch (_: Exception) { }
    }

    // -------------------------------------------------------------------------
    // SvelteKit __data.json yardımcıları
    // -------------------------------------------------------------------------

    private suspend fun getLines(path: String, queryParams: String = ""): List<String>? {
        return try {
            val sep = if (queryParams.isNotEmpty()) "?" else ""
            val url = "$mainUrl${path}__data.json$sep$queryParams"
            val text = app.get(url, headers = baseHeaders).text
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /**
     * __data.json'dan (node 0) raw listeyi ve root map'i döndürür.
     * raw[0] = root map (JSONObject), raw[1..] = değerler
     */
    private suspend fun fetchRawData(path: String, queryParams: String = ""): Pair<List<Any?>, JSONObject>? {
        return try {
            val lines = getLines(path, queryParams) ?: return null
            val firstLine = lines.firstOrNull() ?: return null
            val json = JSONObject(firstLine)
            val nodes = json.optJSONArray("nodes") ?: return null
            val node = nodes.optJSONObject(0) ?: return null
            val rawArr = node.optJSONArray("data") ?: return null
            val raw = mutableListOf<Any?>()
            for (i in 0 until rawArr.length()) raw.add(rawArr.get(i))
            val rootMap = raw.getOrNull(0) as? JSONObject ?: return null
            Pair(raw, rootMap)
        } catch (_: Exception) { null }
    }

    /**
     * SvelteKit stream chunk satırından bölüm listesini ve sayfalama durumunu çözer.
     * /episodes/latest/{page}/ ve /episodes/populars/{page}/ için kullanılır.
     */
    private suspend fun fetchChunkEpisodes(path: String): Pair<List<Map<String, Any?>>, Boolean>? {
        return try {
            val lines = getLines(path) ?: return null
            for (line in lines) {
                if (!line.contains("\"type\":\"chunk\"")) continue
                val obj = try { JSONObject(line) } catch (_: Exception) { null } ?: continue
                val rawArr = obj.optJSONArray("data") ?: continue
                val raw = mutableListOf<Any?>()
                for (i in 0 until rawArr.length()) raw.add(rawArr.get(i))

                val root = resolveVal(0, raw, mutableSetOf()).asMap() ?: continue
                val episodesList = root["episodes"].asList() ?: emptyList<Any?>()
                val page = root["page"].asInt() ?: 1
                val totalPages = root["totalPages"].asInt() ?: 1
                val hasNext = page < totalPages

                val items = mutableListOf<Map<String, Any?>>()
                for (epAny in episodesList) {
                    val ep = epAny.asMap() ?: continue
                    items.add(ep)
                }
                return Pair(items, hasNext)
            }
            null
        } catch (_: Exception) { null }
    }

    /**
     * SvelteKit stream chunk satırından 4K anime listesini ve sayfalama durumunu çözer.
     * /4k-releases/{page}/ için kullanılır (6.000+ anime, 50+ sayfa!).
     */
    private suspend fun fetchChunkAnimes(path: String): Pair<List<Map<String, Any?>>, Boolean>? {
        return try {
            val lines = getLines(path) ?: return null
            for (line in lines) {
                if (!line.contains("\"type\":\"chunk\"")) continue
                val obj = try { JSONObject(line) } catch (_: Exception) { null } ?: continue
                val rawArr = obj.optJSONArray("data") ?: continue
                val raw = mutableListOf<Any?>()
                for (i in 0 until rawArr.length()) raw.add(rawArr.get(i))

                val root = resolveVal(0, raw, mutableSetOf()).asMap() ?: continue
                val animesList = root["animes"].asList() ?: emptyList<Any?>()
                val page = root["page"].asInt() ?: 1
                val totalPages = root["totalPages"].asInt() ?: 1
                val hasNext = page < totalPages

                val items = mutableListOf<Map<String, Any?>>()
                for (aAny in animesList) {
                    val a = aAny.asMap() ?: continue
                    items.add(a)
                }
                return Pair(items, hasNext)
            }
            null
        } catch (_: Exception) { null }
    }

    /**
     * __data.json'dan belirli bir node'u (varsayılan: node 1) okur ve
     * root map içindeki değerleri çözümlenmiş Map olarak döndürür.
     */
    private suspend fun resolveNode(path: String, nodeIndex: Int = 1, queryParams: String = ""): Map<String, Any?>? {
        return try {
            val lines = getLines(path, queryParams) ?: return null
            val firstLine = lines.firstOrNull() ?: return null
            val json = JSONObject(firstLine)
            val nodes = json.optJSONArray("nodes") ?: return null
            val node = nodes.optJSONObject(nodeIndex) ?: return null
            val rawArr = node.optJSONArray("data") ?: return null

            val raw = mutableListOf<Any?>()
            for (i in 0 until rawArr.length()) raw.add(rawArr.get(i))

            val rootMap = raw.getOrNull(0) as? JSONObject ?: return null
            val result = mutableMapOf<String, Any?>()
            val keys = rootMap.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = rootMap.get(k)
                result[k] = if (v is Int) resolveVal(v, raw, mutableSetOf()) else v
            }
            result
        } catch (_: Exception) { null }
    }

    private fun resolveVal(idx: Int, raw: List<Any?>, pathSet: MutableSet<Int>, depth: Int = 0): Any? {
        if (depth > 8 || idx < 0 || idx >= raw.size || !pathSet.add(idx)) return null
        return when (val v = raw[idx]) {
            is JSONObject -> {
                val m = mutableMapOf<String, Any?>()
                val ks = v.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val vv = v.get(k)
                    m[k] = if (vv is Int) resolveVal(vv, raw, pathSet.toMutableSet(), depth + 1) else vv
                }
                m
            }
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until v.length()) {
                    val item = v.get(i)
                    list.add(if (item is Int) resolveVal(item, raw, pathSet.toMutableSet(), depth + 1) else item)
                }
                list
            }
            else -> v
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap() = this as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun Any?.asList() = this as? List<Any?>
    private fun Any?.asStr() = this?.toString()?.takeIf { it != "null" }
    private fun Any?.asInt() = when (this) {
        is Int    -> this
        is Long   -> toInt()
        is Double -> toInt()
        is String -> toIntOrNull()
        else      -> null
    }

    // -------------------------------------------------------------------------
    // Ana Sayfa — Anizium standartlarında zengin kategoriler ve sayfalamalı 4K
    // -------------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "4k"                  to "4K Ultra HD Animeler",
        "recent"              to "Son Eklenen Bölümler",
        "popular_episodes"    to "Popüler Bölümler",
        "popular"             to "Popüler Animeler",
        "featured"            to "Öne Çıkan Animeler",
        "cat:action"          to "Aksiyon",
        "cat:adventure"       to "Macera",
        "cat:fantasy"         to "Fantastik",
        "cat:romance"         to "Romantizm",
        "cat:comedy"          to "Komedi",
        "cat:sci-fi"          to "Bilim Kurgu & Doğaüstü",
        "cat:drama"           to "Dram",
        "cat:isekai"          to "Isekai",
        "cat:school"          to "Okul",
        "cat:shounen"         to "Shounen",
        "cat:seinen"          to "Seinen",
        "cat:slice_of_life"   to "Yaşamdan Kesitler",
        "cat:supernatural"    to "Doğaüstü & Büyü",
        "cat:psychological"   to "Psikolojik & Gerilim",
        "cat:mystery"         to "Gizem & Dedektif",
        "cat:dubbed"          to "Türkçe Dublaj"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureInit()
        val items = mutableListOf<SearchResponse>()

        // 1. 4K Ultra HD Animeler (Gerçek 4K Sayfalama - 6.000+ anime!)
        if (request.data == "4k") {
            val chunkResult = fetchChunkAnimes("/4k-releases/$page/")
            if (chunkResult != null) {
                val (animeList, hasNext) = chunkResult
                for (anime in animeList) {
                    val slug = anime["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                    val title = (anime["turkish"] ?: anime["romaji"] ?: anime["english"]).asStr()
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val poster = anime["pictures"].asMap()?.get("avatar").asStr()
                        ?: anime["pictures"].asMap()?.get("banner").asStr()

                    items.add(newAnimeSearchResponse("$title [4K UHD]", "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = fixUrlNull(poster)
                    })
                }
                return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
            }
        }

        // 2. Son Eklenen veya Popüler Bölümler (Chunk API üzerinden gerçek sayfalama)
        if (request.data == "recent" || request.data == "popular_episodes") {
            val endpoint = if (request.data == "recent") {
                "/episodes/latest/$page/"
            } else {
                "/episodes/populars/$page/"
            }

            val chunkResult = fetchChunkEpisodes(endpoint)
            if (chunkResult != null) {
                val (epList, hasNext) = chunkResult
                for (ep in epList) {
                    val slug = ep["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                    val title = (ep["turkish"] ?: ep["romaji"] ?: ep["english"]).asStr()
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val poster = ep["pictures"].asMap()?.get("avatar").asStr()
                        ?: ep["pictures"].asMap()?.get("banner").asStr()
                    val epNum = ep["episode"].asInt()

                    items.add(newAnimeSearchResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = fixUrlNull(poster)
                        if (epNum != null) {
                            addDubStatus(DubStatus.Subbed, epNum)
                        }
                    })
                }
                return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
            }
        }

        // 3. Kategori / Tür Filtreleri (Anizium tarzı zengin kategori sistemi)
        if (request.data.startsWith("cat:")) {
            val catKey = request.data.removePrefix("cat:")
            val exploreParam = when (catKey) {
                "action"        -> "action"
                "adventure"     -> "adventure"
                "fantasy"       -> "fantasy"
                "romance"       -> "romance"
                "comedy"        -> "comedy"
                "sci-fi"        -> "sci-fi+-+fantasy"
                "drama"         -> "drama"
                "isekai"        -> "isekai"
                "school"        -> "school"
                "shounen"       -> "shounen"
                "seinen"        -> "seinen"
                "slice_of_life" -> "slice+of+life"
                "supernatural"  -> "supernatural"
                "psychological" -> "psychological"
                "mystery"       -> "mystery"
                "dubbed"        -> "dubbed"
                else            -> catKey
            }

            try {
                val pair = fetchRawData("/explore/", "categories=$exploreParam&page=$page")
                if (pair != null) {
                    val (raw, rootMap) = pair
                    val listIdx = rootMap.optInt("animes", -1).takeIf { it >= 0 }
                    if (listIdx != null) {
                        val animeList = resolveVal(listIdx, raw, mutableSetOf())
                        if (animeList is List<*>) {
                            for (animeAny in animeList) {
                                val anime = animeAny.asMap() ?: continue
                                val slug = anime["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                                val title = (anime["turkish"] ?: anime["romaji"] ?: anime["english"]).asStr()
                                    ?.takeIf { it.isNotBlank() } ?: continue
                                val poster = anime["pictures"].asMap()?.get("avatar").asStr()
                                    ?: anime["pictures"].asMap()?.get("banner").asStr()
                                val is4K = anime["is4K"] == true
                                val displayTitle = if (is4K) "$title [4K UHD]" else title

                                items.add(newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug", TvType.Anime) {
                                    this.posterUrl = fixUrlNull(poster)
                                })
                            }
                        }
                    }
                }
            } catch (_: Exception) { }

            return newHomePageResponse(HomePageList(request.name, items), hasNext = items.size >= 15)
        }

        // 4. Popüler veya Öne Çıkan Animeler
        try {
            val (raw, rootMap) = fetchRawData("/") ?: return newHomePageResponse(
                HomePageList(request.name, items), hasNext = false
            )

            val actualKey = if (request.data == "popular") "popularAnimes" else "animes"
            val listIdx = rootMap.optInt(actualKey, -1).takeIf { it >= 0 } ?: return newHomePageResponse(
                HomePageList(request.name, items), hasNext = false
            )

            val animeList = resolveVal(listIdx, raw, mutableSetOf())
            if (animeList is List<*>) {
                for (animeAny in animeList) {
                    val anime = animeAny.asMap() ?: continue
                    val slug = anime["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                    val title = (anime["turkish"] ?: anime["romaji"] ?: anime["english"]).asStr()
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val poster = anime["pictures"].asMap()?.get("avatar").asStr()
                    val is4K = anime["is4K"] == true
                    val displayTitle = if (is4K) "$title [4K UHD]" else title

                    items.add(newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = fixUrlNull(poster)
                    })
                }
            }
        } catch (_: Exception) { }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = false)
    }

    // -------------------------------------------------------------------------
    // Arama
    // -------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        ensureInit()
        val trimmed = query.trim().lowercase()
        if (trimmed.isBlank()) return emptyList()

        val items = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        try {
            val (raw, rootMap) = fetchRawData("/") ?: return emptyList()

            for (key in listOf("popularAnimes", "animes")) {
                val listIdx = rootMap.optInt(key, -1).takeIf { it >= 0 } ?: continue
                val animeList = resolveVal(listIdx, raw, mutableSetOf())
                if (animeList is List<*>) {
                    for (animeAny in animeList) {
                        val anime = animeAny.asMap() ?: continue
                        val slug = anime["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                        if (!seen.add(slug)) continue

                        val turkish = anime["turkish"].asStr() ?: ""
                        val romaji  = anime["romaji"].asStr()  ?: ""
                        val english = anime["english"].asStr() ?: ""
                        val title = turkish.takeIf { it.isNotBlank() }
                            ?: romaji.takeIf { it.isNotBlank() }
                            ?: english
                        if (title.isBlank()) continue

                        val match = turkish.lowercase().contains(trimmed) ||
                                romaji.lowercase().contains(trimmed) ||
                                english.lowercase().contains(trimmed) ||
                                slug.lowercase().contains(trimmed)
                        if (!match) continue

                        val poster = anime["pictures"].asMap()?.get("avatar").asStr()
                        val is4K = anime["is4K"] == true
                        val displayTitle = if (is4K) "$title [4K UHD]" else title

                        items.add(newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug", TvType.Anime) {
                            this.posterUrl = fixUrlNull(poster)
                        })
                    }
                }
            }
        } catch (_: Exception) { }

        // 4K Arşivinde Arama (En popüler 75+ 4K anime: Attack on Titan, 86, Avatar, vb.)
        try {
            for (p in 1..3) {
                val chunk = fetchChunkAnimes("/4k-releases/$p/") ?: break
                val (animeList, _) = chunk
                for (anime in animeList) {
                    val slug = anime["slug"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                    if (!seen.add(slug)) continue

                    val turkish = anime["turkish"].asStr() ?: ""
                    val romaji  = anime["romaji"].asStr()  ?: ""
                    val english = anime["english"].asStr() ?: ""
                    val title = turkish.takeIf { it.isNotBlank() }
                        ?: romaji.takeIf { it.isNotBlank() }
                        ?: english
                    if (title.isBlank()) continue

                    val match = turkish.lowercase().contains(trimmed) ||
                            romaji.lowercase().contains(trimmed) ||
                            english.lowercase().contains(trimmed) ||
                            slug.lowercase().contains(trimmed)
                    if (!match) continue

                    val poster = anime["pictures"].asMap()?.get("avatar").asStr()
                        ?: anime["pictures"].asMap()?.get("banner").asStr()

                    items.add(newAnimeSearchResponse("$title [4K UHD]", "$mainUrl/anime/$slug", TvType.Anime) {
                        this.posterUrl = fixUrlNull(poster)
                    })
                }
            }
        } catch (_: Exception) { }

        return items
    }

    // -------------------------------------------------------------------------
    // Detay & Bölümler
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        ensureInit()
        val slug = url.substringAfterLast("/").substringBefore("?")

        val epNode = resolveNode("/anime/$slug/1/1/")
        val rr = epNode?.get("requestResponse").asMap()
        val animeMeta = rr?.get("animeMeta").asMap()
        val seasons = rr?.get("seasons").asList()

        val rawTitle = (animeMeta?.get("turkish") ?: animeMeta?.get("romaji") ?: animeMeta?.get("english"))
            .asStr()?.takeIf { it.isNotBlank() } ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }
        val is4K = animeMeta?.get("is4K") == true
        val title = if (is4K) "$rawTitle [4K UHD]" else rawTitle

        val poster      = animeMeta?.get("pictures").asMap()?.get("avatar").asStr()
        val banner      = animeMeta?.get("pictures").asMap()?.get("banner").asStr()
        val description = animeMeta?.get("summary").asStr()

        val episodes = mutableListOf<Episode>()

        if (seasons != null) {
            for (seasonAny in seasons) {
                val season = seasonAny.asMap() ?: continue
                val sNum   = (season["season_number"] as? Number)?.toInt() ?: continue
                if (sNum == 0) continue
                val epCount = (season["episode_count"] as? Number)?.toInt() ?: continue
                val sName   = season["name"].asStr()

                for (epNum in 1..epCount) {
                    episodes.add(newEpisode("$mainUrl/anime/$slug/$sNum/$epNum") {
                        this.season  = sNum
                        this.episode = epNum
                        if (epCount == 1 && sName != null) this.name = sName
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode("$mainUrl/anime/$slug/1/1") {
                this.season  = 1
                this.episode = 1
            })
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl           = fixUrlNull(poster)
            this.backgroundPosterUrl = fixUrlNull(banner)
            this.plot                = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // -------------------------------------------------------------------------
    // Video Bağlantıları — Çoklu Fansub ve Çoklu Çözünürlük (4K UHD, 1080p, 720p, 480p)
    //
    // Dosya formatı (CDN'den test edildi):
    //   Normal fansublar : {cdnLink}/{slug}/{epNum}/{epNum}-{fansub_id}-{res}p.mp4
    //   4K / NetRip      : {cdnLink}/{slug}/{epNum}/{epNum}-0-{res}p.mp4
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureInit()

        // URL format: https://openani.me/anime/{slug}/{season}/{episode}
        val afterAnime = data.removePrefix("$mainUrl/anime/")
        val parts = afterAnime.split("/")
        if (parts.size < 3) return false

        val slug    = parts[0]
        val season  = parts[1]
        val episode = parts[2].substringBefore("?")
        val epNum   = episode.toIntOrNull() ?: 1

        val epNode  = resolveNode("/anime/$slug/$season/$episode/") ?: return false
        val rr      = epNode["requestResponse"].asMap() ?: return false
        val cdnLink = epNode["CDN_LINK"].asStr()?.trimEnd('/') ?: "$cdnHost/animes"

        val fansubs    = rr["fansubs"].asList() ?: emptyList<Any?>()
        val defaultEp  = rr["episodeData"].asMap()

        val foundLinks = mutableSetOf<String>()

        // Birincil CDN + yedek aynalar
        val cdnHosts = listOf(
            cdnLink,
            "https://do7---ha-k8y3jyfa-8gcx.zyapbot.eu.org/animes",
            "https://de2---vn-t9g4tsan-5qcl.yeshi.eu.org/animes"
        ).distinct()

        val videoHeaders = mapOf(
            "Referer"    to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // -----------------------------------------------------------------
        // 0. Altyazı Notu:
        // OpenAnime altyazı API'si kapalı oturum anahtarı ve özel şifreleme
        // gerektirdiğinden doğrudan ExoPlayer'a 401 fırlatan API linkleri verilmez.
        // -----------------------------------------------------------------

        // -----------------------------------------------------------------
        // 1. Varsayılan fansub — episodeData.files listesinden direkt al
        //    Bu her zaman garantili gelir; filename gerçek CDN yolunu içerir.
        // -----------------------------------------------------------------
        val defaultFsId: String?
        if (defaultEp != null) {
            val defFs     = defaultEp["fansub"].asMap()
            val defFsName = defFs?.get("name").asStr() ?: "Varsayılan"
            defaultFsId   = defFs?.get("id").asStr()
            val defIs4K   = defFs?.get("is4K") == true
            val isDub     = defFsName.contains("dub", ignoreCase = true)
            val subTag    = if (isDub) " | TR Dublaj" else if (defIs4K || defFsName.contains("4k", ignoreCase = true) || defFsName.contains("netrip", ignoreCase = true)) " | Raw / Altyazısız" else " | TR Gömülü Altyazılı"
            val files     = defaultEp["files"].asList() ?: emptyList<Any?>()

            for (fileAny in files) {
                val f        = fileAny.asMap() ?: continue
                val fileName = f["file"].asStr()?.takeIf { it.isNotBlank() } ?: continue
                val res      = (f["resolution"] as? Number)?.toInt() ?: 720
                val videoUrl = "$cdnLink/$slug/$epNum/$fileName"

                if (foundLinks.add(videoUrl)) {
                    val resLabel = if (res >= 2160) "4K UHD" else "${res}p"
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name [$defFsName - $resLabel$subTag]",
                            url    = videoUrl,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQuality(res)
                            this.headers = videoHeaders
                        }
                    )
                }
            }
        } else {
            defaultFsId = null
        }

        // -----------------------------------------------------------------
        // 2. 4K Fansublar (is4K=true) — NetRip vb. (Raw / Altyazısız)
        //    Format: {epNum}-0-{res}p.mp4  (CDN'den test edildi, ID yerine "0")
        // -----------------------------------------------------------------
        val fourKFansub = fansubs.mapNotNull { it.asMap() }.firstOrNull {
            it["is4K"] == true ||
            it["name"].asStr()?.contains("4k",     ignoreCase = true) == true ||
            it["name"].asStr()?.contains("netrip", ignoreCase = true) == true
        }

        val fourKFsId = fourKFansub?.get("id").asStr()
        if (fourKFansub != null && fourKFsId != defaultFsId) {
            val fkName = fourKFansub["name"].asStr() ?: "4K Ultra HD"
            for (res in listOf(2160, 1080)) {
                val url4k = probeFile("$slug/$epNum/$epNum-0-${res}p.mp4", cdnHosts) ?: continue
                if (foundLinks.add(url4k)) {
                    val resLabel = if (res >= 2160) "4K UHD" else "${res}p"
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name [$fkName - $resLabel | Raw / Altyazısız]",
                            url    = url4k,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQuality(res)
                            this.headers = videoHeaders
                        }
                    )
                }
            }
        }

        // -----------------------------------------------------------------
        // 3. Diğer fansublar (Adonis, PuzzleSubs, Akatsuki vb. - TR Gömülü Altyazılı)
        //    Format: {epNum}-{fansub_id}-{res}p.mp4
        //    Her fansub için 1080p/720p/480p CDN'de kontrol edilir.
        // -----------------------------------------------------------------
        for (fansubAny in fansubs) {
            val fsMap  = fansubAny.asMap() ?: continue
            val fsId   = fsMap["id"].asStr()  ?: continue
            val fsName = fsMap["name"].asStr() ?: "Grup"

            // Zaten eklenmiş fansubları atla
            if (fsId == defaultFsId || fsId == fourKFsId) continue

            val isDub  = fsName.contains("dub", ignoreCase = true)
            val is4kFs = fsMap["is4K"] == true || fsName.contains("4k", ignoreCase = true) || fsName.contains("netrip", ignoreCase = true)
            val subTag = if (isDub) " | TR Dublaj" else if (is4kFs) " | Raw / Altyazısız" else " | TR Gömülü Altyazılı"

            for (res in listOf(1080, 720, 480)) {
                val videoUrl = probeFile("$slug/$epNum/$epNum-$fsId-${res}p.mp4", cdnHosts) ?: continue
                if (foundLinks.add(videoUrl)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name [$fsName - ${res}p$subTag]",
                            url    = videoUrl,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQuality(res)
                            this.headers = videoHeaders
                        }
                    )
                }
            }
        }

        return foundLinks.isNotEmpty()
    }

    // CDN'de dosya varlığını kontrol et (HEAD/Range request)
    private suspend fun probeFile(relativePath: String, cdnHosts: List<String>): String? {
        for (host in cdnHosts) {
            val url = "$host/$relativePath"
            if (checkFileExists(url)) return url
        }
        return null
    }

    private suspend fun checkFileExists(url: String): Boolean {
        return try {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer"    to "$mainUrl/",
                    "Range"      to "bytes=0-0"
                ),
                timeout = 4
            )
            resp.code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun getQuality(res: Int): Int {
        return when (res) {
            2160 ->
