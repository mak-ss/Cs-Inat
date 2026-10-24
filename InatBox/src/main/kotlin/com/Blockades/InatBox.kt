package com.ulgencs3.inatbox

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InatBox : MainAPI() {
    override var name = "InatBox"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 120L

    private val tag = "UlgenInat"

    private var contentUrl = "https://dizilabmedia.click/CDN/001/002/dizilab"
    private var cfUrl = "https://dizilabmedia.click/CDN/001/002/dizilab/v2/cf.php"
    private var ctUrl = "https://dizilabmedia.click/CDN/001/002/dizilab/v2/ct.php"

    // Canlı Yedek & Hibrit Motor (SelçukSports + RecTV + CanlıTV M3U)
    private var rectvUrl = "https://m.prectv72.lol"
    private var selcukUrl = "https://www.selcuksportshdfe44b96fcb.xyz"
    private var selcukPlayerUrl = "https://main.uxsyplayer6859599e6c.click"
    private var selcukStreamBase = "https://dga1op10s1u3lea.82250d06d39d38.click/live/"
    private var canliTvM3uUrl = "https://raw.githubusercontent.com/feroxx/test/refs/heads/main/Kanallar/canlitv.m3u"
    private val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val swKey = "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"
    private val rectvHmacKey = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"

    private val masterCertKey = "a4osa8x1yl4w3vrk"
    private val hmacSecretKey = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"

    private var timeOffset: Long = 0L
    private var isConfigInitialized = false
    private val urlToSearchResponse = ConcurrentHashMap<String, SearchResponse>()
    private val dynamicCategoriesMap = ConcurrentHashMap<String, String>()
    private var m3uCache: String? = null

    private val secureRandom = SecureRandom()
    private val alphaNumericChars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val hexChars = "0123456789abcdef"

    override val mainPage = mainPageOf(
        "https://sprboxs.bar/CDN/001/SPR/v2/spor_v3.php"            to "Spor ve Kanallar",
        "https://sprboxs.bar/CDN/001/SPR/v2/ccc/a/index.php"        to "Kanallar Liste 1 (TOD)",
        "https://sprspr.help/CDN/SSP/bir-p-no-cron.php"             to "Kanallar Liste 2 (S Sport+)",
        "v2/tv/sinema.php"                                          to "Sinema Kanalları",
        "v2/tv/belgesel.php"                                        to "Belgesel Kanalları",
        "v2/tv/ulusal.php"                                          to "Ulusal Kanallar",
        "v2/tv/haber.php"                                           to "Haber Kanalları",
        "v2/tv/cocuk.php"                                           to "Çocuk Kanalları",
        "v2/tv/dini.php"                                            to "Dini Kanallar",
        "v2/ex/index.php"                                           to "EXXEN",
        "v2/ga/index.php"                                           to "Gain",
        "v2/nf/index.php"                                           to "Netflix",
        "v2/dsny/index.php"                                         to "Disney+",
        "v2/amz/index.php"                                          to "Amazon Prime",
        "v2/hb/index.php"                                           to "HBO Max",
        "v2/tbi/index.php"                                          to "Tabii",
        "v2/film/mubi.php"                                          to "Mubi",
        "v2/yabanci-dizi/index.php"                                 to "Yabancı Diziler",
        "v2/yerli-dizi/index.php"                                   to "Yerli Diziler",
        "v2/film/yerli-filmler.php"                                 to "Yerli Filmler",
        "https://4k.filmizleeeee.cfd/4k/01/public/catalog-exo.php"  to "4K Film İzle"
    )

    private fun generateRandomKey(length: Int = 16): String {
        return (1..length)
            .map { alphaNumericChars[secureRandom.nextInt(alphaNumericChars.length)] }
            .joinToString("")
    }

    private fun generateRandomHex(length: Int = 16): String {
        return (1..length)
            .map { hexChars[secureRandom.nextInt(hexChars.length)] }
            .joinToString("")
    }

    private suspend fun ensureConfig() {
        if (isConfigInitialized) return
        isConfigInitialized = true

        // 1. Kendi GitLab/Codeberg sunucularımızdan güncel domainleri al
        val backupUrls = listOf(
            "https://gitlab.com/ulgenzade/ulgencs3/-/raw/master/domains.json",
            "https://codeberg.org/Ulgenzade/ulgencs3/raw/branch/master/domains.json"
        )
        for (serverUrl in backupUrls) {
            try {
                val configStr = app.get(serverUrl, timeout = 5).text
                val cfg = JSONObject(configStr)
                cfg.optString("inatbox").takeIf { it.isNotBlank() }?.let {
                    contentUrl = it.trimEnd('/')
                    cfUrl = "$contentUrl/v2/cf.php"
                    ctUrl = "$contentUrl/v2/ct.php"
                }
                cfg.optString("rectv").takeIf { it.isNotBlank() }?.let { rectvUrl = it.trimEnd('/') }
                cfg.optString("selcuksports").takeIf { it.isNotBlank() }?.let { selcukUrl = it.trimEnd('/') }
                cfg.optString("selcuksports_player").takeIf { it.isNotBlank() }?.let { selcukPlayerUrl = it.trimEnd('/') }
                cfg.optString("selcuksports_stream_base").takeIf { it.isNotBlank() }?.let {
                    selcukStreamBase = if (it.endsWith("/")) it else "$it/"
                }
                cfg.optString("canlitv").takeIf { it.isNotBlank() }?.let { canliTvM3uUrl = it }
                break
            } catch (_: Exception) { }
        }

        // 2. Birincil InatBox SDK bootstrap sunucuları (ironsdk.net / speedrestapi.com)
        for (sdkUrl in listOf("https://ironsdk.net/api/sdk/", "https://speedrestapi.com/rest/api/")) {
            try {
                val sdkJsonStr = makeInatRequest(sdkUrl)
                if (!sdkJsonStr.isNullOrBlank()) {
                    val sdkObj = JSONObject(sdkJsonStr)
                    sdkObj.optString("DC1").takeIf { it.isNotBlank() }?.let { cfUrl = it }
                    sdkObj.optString("DC2").takeIf { it.isNotBlank() }?.let { ctUrl = it }
                    val base = cfUrl.substringBefore("/v2/cf.php").substringBefore("/v2/ct.php")
                    if (base.isNotBlank() && base.startsWith("http")) {
                        contentUrl = base.trimEnd('/')
                        break
                    }
                }
            } catch (_: Exception) { }
        }

        // 3. Dinamik SSL sertifikası (cencbit) kontrolü
        try {
            val certResp = app.get(
                "https://raw.githubusercontent.com/cencbit/ssl/main/certificate.pem",
                headers = mapOf("User-Agent" to "speedrestapi"),
                timeout = 5
            ).text
            val cleanCert = certResp
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .trim()
            val configJson = decryptCertificateConfig(cleanCert, masterCertKey)
            if (configJson != null) {
                configJson.optString("DC1").takeIf { it.isNotBlank() }?.let { cfUrl = it }
                configJson.optString("DC2").takeIf { it.isNotBlank() }?.let { ctUrl = it }
                val base = cfUrl.substringBefore("/v2/cf.php")
                if (base.isNotBlank() && base.startsWith("http")) {
                    contentUrl = base.trimEnd('/')
                }
            }
        } catch (_: Exception) { }

        // 4. Eğer ctUrl erişilebilirse v16 dinamik kategori URL haritasını hazırla
        try {
            val catJsonStr = makeInatRequest(ctUrl)
            if (!catJsonStr.isNullOrBlank()) {
                val arr = JSONArray(catJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val cName = obj.optString("catName").trim()
                    val cUrl = obj.optString("catUrl").trim()
                    if (cName.isNotBlank() && cUrl.isNotBlank()) {
                        dynamicCategoriesMap[cName.lowercase()] = cUrl
                    }
                }
            }
        } catch (_: Exception) { }
    }

    // ---- RecTV & SelçukSports Hibrit Yardımcıları ----
    private suspend fun getRectvNonce(): String? {
        return try {
            val resp = app.get("$rectvUrl/api/attest/nonce", headers = mapOf("User-Agent" to "googleusercontent"), timeout = 5)
            JSONObject(resp.text).optString("nonce").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getRectvSignedHeaders(path: String): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = sha256("")
        val message = "GET\n$path\n$ts\n$nonce\n$bodyHash"
        val signature = hmacSha256(rectvHmacKey, message)
        val headers = mutableMapOf(
            "User-Agent" to "googleusercontent",
            "Referer" to "https://twitter.com/",
            "X-Timestamp" to ts,
            "X-Nonce" to nonce,
            "X-Signature" to signature,
            "X-App-Version" to "141",
            "X-Client-Id" to "rectv-android"
        )
        getRectvNonce()?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private suspend fun fetchRectvJson(path: String): String? {
        return try {
            val cleanPath = "/" + path.trimStart('/')
            val headers = getRectvSignedHeaders(cleanPath)
            val resp = app.get("$rectvUrl$cleanPath", headers = headers, timeout = 8)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildSelcukSportsItems(): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val channels = listOf(
            Triple("beIN Sports 1 HD (Canlı Maç)", "selcukbeinsports1", "https://i.imgur.com/Vtk2cGI.png"),
            Triple("beIN Sports 2 HD", "selcukbeinsports2", "https://i.imgur.com/vUJZSvs.png"),
            Triple("beIN Sports 3 HD", "selcukbeinsports3", "https://i.imgur.com/UYSMao3.png"),
            Triple("beIN Sports 4 HD", "selcukbeinsports4", "https://i.imgur.com/vwAgJNi.png"),
            Triple("beIN Sports 5 HD", "selcukbeinsports5", "https://i.imgur.com/2Rha5aY.png"),
            Triple("beIN Sports Max 1 HD", "selcukbeinsportsmax1", "https://i.imgur.com/RLrMBlm.png"),
            Triple("beIN Sports Max 2 HD", "selcukbeinsportsmax2", "https://i.imgur.com/RLrMBlm.png"),
            Triple("beIN Sports Haber HD", "selcukbeinsportshaber", "https://i.imgur.com/QWo0x7S.jpg"),
            Triple("Exxen Spor 1 HD", "selcukexxen1", "https://i.imgur.com/d9P2t5r.png"),
            Triple("Exxen Spor 2 HD", "selcukexxen2", "https://i.imgur.com/d9P2t5r.png"),
            Triple("Exxen Spor 3 HD", "selcukexxen3", "https://i.imgur.com/d9P2t5r.png"),
            Triple("Exxen Spor 4 HD", "selcukexxen4", "https://i.imgur.com/d9P2t5r.png"),
            Triple("Tabii Spor 1 HD", "selcuktabiispor1", "https://i.imgur.com/6tv0zxh.png"),
            Triple("Tabii Spor 2 HD", "selcuktabiispor2", "https://i.imgur.com/6tv0zxh.png"),
            Triple("Tabii Spor 3 HD", "selcuktabiispor3", "https://i.imgur.com/6tv0zxh.png"),
            Triple("Tabii Spor 4 HD", "selcuktabiispor4", "https://i.imgur.com/6tv0zxh.png"),
            Triple("Tabii Spor 5 HD", "selcuktabiispor5", "https://i.imgur.com/6tv0zxh.png"),
            Triple("Tabii Spor 6 HD", "selcuktabiispor6", "https://i.imgur.com/6tv0zxh.png"),
            Triple("S Sport 1 HD", "selcukssport", "https://upload.wikimedia.org/wikipedia/commons/a/a9/S_Sport_logo1.png"),
            Triple("S Sport 2 HD", "selcukssport2", "https://i.imgur.com/2jIItAy.png"),
            Triple("Tivibu Spor 1 HD", "selcuktivibuspor1", "https://i.imgur.com/qvrKQY3.png"),
            Triple("Tivibu Spor 2 HD", "selcuktivibuspor2", "https://i.imgur.com/fZMSjNE.png"),
            Triple("Tivibu Spor 3 HD", "selcuktivibuspor3", "https://i.imgur.com/qvrKQY3.png"),
            Triple("Tivibu Spor 4 HD", "selcuktivibuspor4", "https://i.imgur.com/qvrKQY3.png"),
            Triple("Spor Smart 1 HD", "selcuksmartspor", "https://i.imgur.com/blu6v6P.png"),
            Triple("Spor Smart 2 HD", "selcuksmartspor2", "https://i.imgur.com/blu6v6P.png"),
            Triple("TRT Spor HD", "selcuktrtspor", "https://i.imgur.com/6tv0zxh.png"),
            Triple("A Spor HD", "selcukaspor", "https://i.imgur.com/ZhkZzLf.png"),
            Triple("HT Spor HD", "selcukhtspor", "https://ciner.daioncdn.net/ht-spor/ht-spor.png"),
            Triple("TRT 1 HD (Canlı Maç)", "selcuktrt1", "https://cdn-i.pr.trt.com.tr/trttv/w750/h750/q100/11290409.png"),
            Triple("TV 8.5 HD", "selcuktv85", "https://upload.wikimedia.org/wikipedia/tr/thumb/8/87/TV8_bucuk_logo.png/512px-TV8_bucuk_logo.png"),
            Triple("ATV HD", "selcukatv", "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c8/ATV_logo.svg/512px-ATV_logo.svg.png")
        )
        for ((title, sId, logo) in channels) {
            val streamUrl = "${selcukStreamBase}${sId}/playlist.m3u8"
            val itemJson = JSONObject().apply {
                put("chName", title)
                put("chUrl", streamUrl)
                put("chImg", logo)
                put("chType", "live_url")
                put("chHeaders", JSONArray().put(JSONObject().apply {
                    put("Referer", "$selcukPlayerUrl/")
                    put("Origin", selcukPlayerUrl)
                    put("User-Agent", browserUa)
                }).toString())
            }
            list.add(
                newLiveSearchResponse(title, itemJson.toString(), TvType.Live) {
                    this.posterUrl = logo
                }
            )
        }
        return list
    }

    private suspend fun parseRecTvLiveCategory(catId: Int): List<SearchResponse> {
        val jsonStr = fetchRectvJson("/api/channel/by/filtres/$catId/0/0/$swKey/") ?: return emptyList()
        val list = mutableListOf<SearchResponse>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.optString("title")
                val image = obj.optString("image")
                val sources = obj.optJSONArray("sources") ?: continue
                var streamUrl = ""
                for (j in 0 until sources.length()) {
                    val u = sources.getJSONObject(j).optString("url")
                    if (u.isNotBlank() && u != "null") {
                        streamUrl = u
                        break
                    }
                }
                if (streamUrl.isBlank()) continue
                val itemJson = JSONObject().apply {
                    put("chName", title)
                    put("chUrl", streamUrl)
                    put("chImg", image)
                    put("chType", "live_url")
                    put("chHeaders", JSONArray().put(JSONObject().apply {
                        put("Referer", "https://twitter.com/")
                        put("User-Agent", "googleusercontent")
                    }).toString())
                }
                list.add(
                    newLiveSearchResponse(title, itemJson.toString(), TvType.Live) {
                        this.posterUrl = image
                    }
                )
            }
        } catch (_: Exception) { }
        return list
    }

    private suspend fun parseM3uByKeywords(keywords: List<String>): List<SearchResponse> {
        val rawM3u = m3uCache ?: try {
            app.get(canliTvM3uUrl, timeout = 8).text.also { m3uCache = it }
        } catch (_: Exception) {
            ""
        }
        if (rawM3u.isBlank()) return emptyList()

        val list = mutableListOf<SearchResponse>()
        val lines = rawM3u.lines()
        var currentTitle = ""
        var currentLogo = ""
        var currentGroup = ""

        for (line in lines) {
            val l = line.trim()
            if (l.startsWith("#EXTINF:")) {
                currentTitle = l.substringAfterLast(",").trim()
                currentLogo = Regex("""tvg-logo="([^"]+)"""").find(l)?.groupValues?.get(1) ?: ""
                currentGroup = Regex("""group-title="([^"]+)"""").find(l)?.groupValues?.get(1) ?: ""
            } else if (l.startsWith("http") && currentTitle.isNotBlank()) {
                val combined = "$currentTitle $currentGroup".lowercase()
                if (keywords.isEmpty() || keywords.any { combined.contains(it) }) {
                    val itemJson = JSONObject().apply {
                        put("chName", currentTitle)
                        put("chUrl", l)
                        put("chImg", currentLogo.ifBlank { "https://gitlab.com/ulgenzade/ulgencs3/-/raw/master/assets/ulgen.ico" })
                        put("chType", "live_url")
                    }
                    list.add(
                        newLiveSearchResponse(currentTitle, itemJson.toString(), TvType.Live) {
                            this.posterUrl = currentLogo.ifBlank { "https://gitlab.com/ulgenzade/ulgencs3/-/raw/master/assets/ulgen.ico" }
                        }
                    )
                }
                currentTitle = ""
            }
        }
        return list
    }

    private suspend fun fetchRecTvVodAsInatItems(endpointPath: String, isSeries: Boolean): List<SearchResponse> {
        val jsonStr = fetchRectvJson(endpointPath) ?: return emptyList()
        val list = mutableListOf<SearchResponse>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optInt("id")
                val title = obj.optString("title")
                val poster = obj.optString("image")
                val desc = obj.optString("description")
                if (title.isBlank()) continue

                if (isSeries || obj.optString("type") == "serie") {
                    val itemJson = JSONObject().apply {
                        put("diziType", "rectv_serie")
                        put("rectvId", id)
                        put("diziName", title)
                        put("diziImg", poster)
                        put("diziDetay", desc)
                    }
                    list.add(
                        newTvSeriesSearchResponse(title, itemJson.toString()) {
                            this.posterUrl = poster
                        }
                    )
                } else {
                    val sources = obj.optJSONArray("sources") ?: JSONArray()
                    val itemJson = JSONObject().apply {
                        put("diziType", "rectv_movie")
                        put("rectvId", id)
                        put("diziName", title)
                        put("diziImg", poster)
                        put("diziDetay", desc)
                        put("sources", sources)
                    }
                    list.add(
                        newMovieSearchResponse(title, itemJson.toString()) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (_: Exception) { }
        return list
    }

    private suspend fun getFallbackCategoryItems(dataPath: String, catName: String): List<SearchResponse> {
        val clean = dataPath.trimStart('/')
        val norm = clean.removePrefix("v2/").trimStart('/')
        return when {
            norm == "tv/list1.php" || norm.contains("spor_v3.php") -> {
                val selcuk = buildSelcukSportsItems()
                val rectv0 = parseRecTvLiveCategory(0)
                (selcuk + rectv0).distinctBy { it.name }
            }
            norm == "tv/cable.php" || norm.contains("ccc/a/index.php") -> {
                val rectv0 = parseRecTvLiveCategory(0)
                val selcuk = buildSelcukSportsItems()
                (rectv0 + selcuk).distinctBy { it.name }
            }
            norm == "tv/list2.php" || norm.contains("bir-p-no-cron.php") -> {
                val rectv1 = parseRecTvLiveCategory(1)
                val rectv3 = parseRecTvLiveCategory(3)
                (rectv1 + rectv3 + buildSelcukSportsItems()).distinctBy { it.name }
            }
            norm == "tv/sinema.php" -> {
                val m3u = parseM3uByKeywords(listOf("sinema", "film", "movie", "dizi", "fx", "tlc", "dmax", "beyaz"))
                val vod = fetchRecTvVodAsInatItems("/api/movie/by/filtres/0/created/0/$swKey/", false)
                (m3u + vod.take(15)).distinctBy { it.name }
            }
            norm == "tv/belgesel.php" -> {
                val rectv2 = parseRecTvLiveCategory(2)
                val m3u = parseM3uByKeywords(listOf("belgesel", "discovery", "nat geo", "history", "trt belgesel", "yaban", "tarih"))
                (rectv2 + m3u).distinctBy { it.name }
            }
            norm == "tv/ulusal.php" -> {
                val rectv3 = parseRecTvLiveCategory(3)
                val m3u = parseM3uByKeywords(listOf("ulusal", "trt 1", "atv", "kanald", "kanal d", "show", "star", "tv8", "now", "kanal 7", "teve2"))
                (rectv3 + m3u).distinctBy { it.name }
            }
            norm == "tv/haber.php" -> {
                parseM3uByKeywords(listOf("haber", "cnn", "ntv", "habertürk", "haberturk", "sözcü", "halk", "trt haber", "a haber", "tv100", "ulke", "ülke", "tgrt", "bloomberg", "ekol"))
            }
            norm == "tv/cocuk.php" -> {
                val m3u = parseM3uByKeywords(listOf("çocuk", "cocuk", "cartoon", "minika", "trt çocuk", "disney", "nick", "baby", "zarok"))
                val anim = fetchRecTvVodAsInatItems("/api/movie/by/filtres/13/created/0/$swKey/", false)
                (m3u + anim.take(12)).distinctBy { it.name }
            }
            norm == "tv/dini.php" -> {
                val m3u = parseM3uByKeywords(listOf("dini", "diyanet", "semerkant", "semerkand", "lalegül", "kabe", "mekke", "medine", "kudüs", "hilal", "dost", "aksu"))
                val rectv3 = parseRecTvLiveCategory(3).filter { it.name.contains("SEMERKAND", true) || it.name.contains("TRT", true) }
                (m3u + rectv3).distinctBy { it.name }
            }
            norm == "ex/index.php" -> {
                val yerli = fetchRecTvVodAsInatItems("/api/serie/by/filtres/23/created/0/$swKey/", true)
                val komedi = fetchRecTvVodAsInatItems("/api/serie/by/filtres/3/created/0/$swKey/", true)
                (yerli + komedi).distinctBy { it.name }
            }
            norm == "ga/index.php" -> {
                val yerli = fetchRecTvVodAsInatItems("/api/serie/by/filtres/23/created/1/$swKey/", true)
                val suc = fetchRecTvVodAsInatItems("/api/serie/by/filtres/22/created/0/$swKey/", true)
                (yerli + suc).distinctBy { it.name }
            }
            norm == "nf/index.php" -> {
                val nfSeries = fetchRecTvVodAsInatItems("/api/serie/by/filtres/57/created/0/$swKey/", true)
                val nfMovies = fetchRecTvVodAsInatItems("/api/movie/by/filtres/57/created/0/$swKey/", false)
                (nfSeries + nfMovies).distinctBy { it.name }
            }
            norm == "dsny/index.php" -> {
                val dsSeries = fetchRecTvVodAsInatItems("/api/serie/by/filtres/67/created/0/$swKey/", true)
                val dsMovies = fetchRecTvVodAsInatItems("/api/movie/by/filtres/67/created/0/$swKey/", false)
                (dsSeries + dsMovies).distinctBy { it.name }
            }
            norm == "amz/index.php" -> {
                val amzSeries = fetchRecTvVodAsInatItems("/api/serie/by/filtres/75/created/0/$swKey/", true)
                val amzMovies = fetchRecTvVodAsInatItems("/api/movie/by/filtres/75/created/0/$swKey/", false)
                (amzSeries + amzMovies).distinctBy { it.name }
            }
            norm == "hb/index.php" -> {
                val hboSeries = fetchRecTvVodAsInatItems("/api/serie/by/filtres/62/created/0/$swKey/", true)
                val hboMovies = fetchRecTvVodAsInatItems("/api/movie/by/filtres/62/created/0/$swKey/", false)
                (hboSeries + hboMovies).distinctBy { it.name }
            }
            norm == "tbi/index.php" -> {
                val tabiiSpor = buildSelcukSportsItems().filter { it.name.contains("Tabii", true) || it.name.contains("TRT", true) }
                val yerli = fetchRecTvVodAsInatItems("/api/serie/by/filtres/23/created/0/$swKey/", true)
                (tabiiSpor + yerli).distinctBy { it.name }
            }
            norm == "film/mubi.php" -> {
                val odullu = fetchRecTvVodAsInatItems("/api/movie/by/filtres/20/created/0/$swKey/", false)
                val dram = fetchRecTvVodAsInatItems("/api/movie/by/filtres/2/created/0/$swKey/", false)
                (odullu + dram).distinctBy { it.name }
            }
            norm == "yabanci-dizi/index.php" -> {
                val s0 = fetchRecTvVodAsInatItems("/api/serie/by/filtres/0/created/0/$swKey/", true)
                val s1 = fetchRecTvVodAsInatItems("/api/serie/by/filtres/0/created/1/$swKey/", true)
                (s0 + s1).distinctBy { it.name }
            }
            norm == "yerli-dizi/index.php" -> {
                fetchRecTvVodAsInatItems("/api/serie/by/filtres/23/created/0/$swKey/", true)
            }
            norm == "film/yerli-filmler.php" -> {
                fetchRecTvVodAsInatItems("/api/movie/by/filtres/23/created/0/$swKey/", false)
            }
            norm == "film/4k-film-exo.php" || norm.contains("catalog-exo.php") -> {
                val f4k = fetchRecTvVodAsInatItems("/api/movie/by/filtres/42/created/0/$swKey/", false)
                val fNew = fetchRecTvVodAsInatItems("/api/movie/by/filtres/0/created/0/$swKey/", false)
                (f4k + fNew).distinctBy { it.name }
            }
            else -> fetchRecTvVodAsInatItems("/api/movie/by/filtres/0/created/0/$swKey/", false)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()

        // 1. Önce v16 dinamik kategori URL haritası veya doğrudan InatBox v2 / sprboxs / 4K isteği dene
        val dynamicUrl = dynamicCategoriesMap[request.name.lowercase()]
        var jsonResponse = if (dynamicUrl != null) makeInatRequest(dynamicUrl) else null
        if (jsonResponse.isNullOrBlank()) {
            val targetUrl = if (request.data.startsWith("http")) {
                request.data
            } else {
                "$contentUrl/" + request.data.trimStart('/')
            }
            jsonResponse = makeInatRequest(targetUrl)
            if (jsonResponse.isNullOrBlank() && !request.data.startsWith("http")) {
                jsonResponse = makeInatRequest("https://diziboxen.help/CDN/001/002/dizibox/" + request.data.trimStart('/'))
            }
        }

        var searchResults = if (!jsonResponse.isNullOrBlank()) {
            getSearchResponseList(jsonResponse)
        } else {
            emptyList()
        }

        // 2. Eğer sunucu Cloudflare 1006 (VPN) / 404 döndürdüyse, canlı yedek motorumuzdan kategoriyi eksiksiz doldur!
        if (searchResults.isEmpty()) {
            searchResults = getFallbackCategoryItems(request.data, request.name)
        }

        for (item in searchResults) {
            urlToSearchResponse[item.url] = item
        }

        return newHomePageResponse(request.name, searchResults, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureConfig()
        val matchingResults = mutableListOf<SearchResponse>()
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        // 1. Önbellekteki öğeleri tara
        val regex = try {
            Regex(q, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        // 2. Canlı spor kanallarını tara
        for (item in buildSelcukSportsItems()) {
            if (regex.containsMatchIn(item.name)) {
                matchingResults.add(item)
            }
        }

        // 3. RecTV API üzerinden canlı arama yap
        try {
            val encoded = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            val searchJson = fetchRectvJson("/api/search/$encoded/$swKey/")
            if (!searchJson.isNullOrBlank()) {
                val obj = JSONObject(searchJson)
                val posters = obj.optJSONArray("posters") ?: JSONArray()
                for (i in 0 until posters.length()) {
                    val p = posters.getJSONObject(i)
                    val id = p.optInt("id")
                    val title = p.optString("title")
                    val poster = p.optString("image")
                    val desc = p.optString("description")
                    val pType = p.optString("type")
                    if (pType == "serie") {
                        val itemJson = JSONObject().apply {
                            put("diziType", "rectv_serie")
                            put("rectvId", id)
                            put("diziName", title)
                            put("diziImg", poster)
                            put("diziDetay", desc)
                        }
                        matchingResults.add(newTvSeriesSearchResponse(title, itemJson.toString()) { this.posterUrl = poster })
                    } else {
                        val itemJson = JSONObject().apply {
                            put("diziType", "rectv_movie")
                            put("rectvId", id)
                            put("diziName", title)
                            put("diziImg", poster)
                            put("diziDetay", desc)
                            put("sources", p.optJSONArray("sources") ?: JSONArray())
                        }
                        matchingResults.add(newMovieSearchResponse(title, itemJson.toString()) { this.posterUrl = poster })
                    }
                }
            }
        } catch (_: Exception) { }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureConfig()
        val raw = if (url.trim().startsWith("{")) url.trim() else url.substringAfterLast("/")
        val item = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject(url)
        }

        if (!inatContentAllowed(item)) {
            return null
        }

        if (item.has("diziType")) {
            return when (item.getString("diziType")) {
                "rectv_serie" -> parseRecTvSeriesLoad(item)
                "rectv_movie" -> parseRecTvMovieLoad(item)
                "dizi", "dizi_mode" -> parseTvSeriesResponse(item)
                "film", "film_mode" -> parseMovieResponse(item)
                else -> null
            }
        } else if (item.has("chName") && item.has("chUrl")) {
            val chType = item.optString("chType", "live_url")
            return when (chType) {
                "live_url", "cable_sh" -> parseLiveStreamLoadResponse(item)
                "tekli_regex_lb_sh_3" -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
        } else {
            return null
        }
    }

    private suspend fun parseRecTvSeriesLoad(item: JSONObject): LoadResponse? {
        val id = item.optInt("rectvId")
        val name = item.optString("diziName")
        val posterUrl = item.optString("diziImg")
        val plot = item.optString("diziDetay")

        val seasonsJson = fetchRectvJson("/api/season/by/serie/$id/$swKey/") ?: return null
        val seasonsArr = JSONArray(seasonsJson)
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        for (i in 0 until seasonsArr.length()) {
            val sObj = seasonsArr.getJSONObject(i)
            val sTitle = sObj.optString("title", "${i + 1}. Sezon")
            val sNum = Regex("\\d+").find(sTitle)?.value?.toIntOrNull() ?: (i + 1)
            seasonDataList.add(SeasonData(sNum, sTitle))

            val epArr = sObj.optJSONArray("episodes") ?: continue
            for (j in 0 until epArr.length()) {
                val epObj = epArr.getJSONObject(j)
                val epTitle = epObj.optString("title", "${j + 1}. Bölüm")
                val epNum = Regex("\\d+").find(epTitle)?.value?.toIntOrNull() ?: (j + 1)
                val srcArr = epObj.optJSONArray("sources") ?: continue
                val chArray = JSONArray()
                for (k in 0 until srcArr.length()) {
                    val s = srcArr.getJSONObject(k)
                    val sUrl = s.optString("url")
                    if (sUrl.isNotBlank() && sUrl != "null") {
                        chArray.put(JSONObject().apply {
                            put("chName", "$name - $sTitle $epTitle (${s.optString("title", "HD")})")
                            put("chUrl", sUrl)
                            put("chImg", posterUrl)
                            put("chType", "url")
                            put("chHeaders", JSONArray().put(JSONObject().apply {
                                put("Referer", "https://twitter.com/")
                                put("User-Agent", "googleusercontent")
                            }).toString())
                        })
                    }
                }
                if (chArray.length() > 0) {
                    episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                        newEpisode(chArray.toString()) {
                            this.name = epTitle
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        }

        return newAnimeLoadResponse(name, item.toString(), TvType.TvSeries, false) {
            this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
            this.posterUrl = posterUrl
            this.plot = plot
            this.seasonNames = seasonDataList
        }
    }

    private suspend fun parseRecTvMovieLoad(item: JSONObject): LoadResponse? {
        val name = item.optString("diziName")
        val posterUrl = item.optString("diziImg")
        val plot = item.optString("diziDetay")
        val srcArr = item.optJSONArray("sources") ?: JSONArray()
        val chArray = JSONArray()
        for (i in 0 until srcArr.length()) {
            val s = srcArr.getJSONObject(i)
            val sUrl = s.optString("url")
            if (sUrl.isNotBlank() && sUrl != "null") {
                chArray.put(JSONObject().apply {
                    put("chName", "$name (${s.optString("title", "1080p HD")})")
                    put("chUrl", sUrl)
                    put("chImg", posterUrl)
                    put("chType", "url")
                    put("chHeaders", JSONArray().put(JSONObject().apply {
                        put("Referer", "https://twitter.com/")
                        put("User-Agent", "googleusercontent")
                    }).toString())
                })
            }
        }
        return newMovieLoadResponse(name, item.toString(), TvType.Movie, chArray.toString()) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureConfig()
        return try {
            val trimmed = data.trim()
            if (trimmed.startsWith("[")) {
                val chContentJsonArray = JSONArray(trimmed)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonObject = JSONObject(trimmed)
                val chContent = parseToChContent(chContentJsonObject)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "loadLinks hatası: ${e::class.simpleName} - ${e.message}")
            false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()
            val isSelcuk = url.contains(".click") || url.contains(".cfd") || url.contains("touraddalf") || url.contains("uxsyplayer")
            val linkRef = extractorLink.headers["Referer"]?.takeIf { it.isNotBlank() }
                ?: extractorLink.referer.takeIf { it.isNotBlank() }
            val referer = when {
                isSelcuk -> "$selcukPlayerUrl/"
                !linkRef.isNullOrBlank() -> linkRef
                else -> "https://twitter.com/"
            }
            val ua = when {
                isSelcuk -> browserUa
                !extractorLink.headers["User-Agent"].isNullOrBlank() -> extractorLink.headers["User-Agent"]!!
                else -> "googleusercontent"
            }
            val builder = originalRequest.newBuilder()
                .removeHeader("If-None-Match")
                .header("User-Agent", ua)
                .header("Referer", referer)
            if (isSelcuk) {
                builder.header("Origin", selcukPlayerUrl)
            }
            chain.proceed(builder.build())
        }
    }

    private suspend fun parseTvSeriesResponse(
        item: JSONObject,
        tvType: TvType = TvType.TvSeries
    ): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.optString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                val seasonData = SeasonData(season = (i + 1), name = seasonName)
                seasonDataList.add(seasonData)

                val seasonUrl = seasonItem.getString("diziUrl")
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    continue
                }

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val rawEpName = episodeItem.getString("chName").trim()
                        val cleanEpTitle = rawEpName
                            .replace(Regex("""^\s*\d+\.\s*Sezon\s*""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""^\s*\d+\.\s*Bölüm\s*[-–:]*\s*""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""^Bölüm\s*\d+\s*[-–:]*\s*""", RegexOption.IGNORE_CASE), "")
                            .trim()
                        val finalName = cleanEpTitle.takeIf { it.isNotBlank() && !it.equals("Bölüm", ignoreCase = true) && it != "${j + 1}" }
                        val episodePoster = episodeItem.optString("chImg")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = finalName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (_: JSONException) {
                        continue
                    }
                }
            }

            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.optString("diziImg")

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.optString("diziImg")
                val plot = item.optString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                return newMovieLoadResponse(
                    name = name,
                    url = item.toString(),
                    type = TvType.Movie,
                    dataUrl = jsonArray.toString()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                val posterUrl = item.optString("chImg")
                return newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)
            newLiveStreamLoadResponse(chContent.chName, item.toString(), item.toString()) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        return try {
            val chContent = parseToChContent(item)
            newLiveStreamLoadResponse(chContent.chName, item.toString(), item.toString()) {
                this.posterUrl = chContent.chImg
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type: String = if (item.has("diziType")) {
            item.getString("diziType")
        } else if (item.has("chType")) {
            item.getString("chType")
        } else {
            ""
        }

        return when (type) {
            "link", "web" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = item.optString("chUrl").vkSourceFix(),
            chImg = item.optString("chImg"),
            chHeaders = item.optString("chHeaders"),
            chReg = item.optString("chReg"),
            chType = item.optString("chType")
        )
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val chType = chContent.chType
        val contentToProcess: ChContent

        if (chType == "tekli_regex_lb_sh_3") {
            val name = chContent.chName
            val url = chContent.chUrl
            val posterUrl = chContent.chImg
            val headers = chContent.chHeaders
            val reg = chContent.chReg
            val type = chContent.chType

            val jsonResponse = runCatching { makeInatRequest(url) }.getOrNull()
                ?: return
            val firstItem = JSONObject(jsonResponse)
            firstItem.put("chHeaders", headers)
            firstItem.put("chReg", reg)
            firstItem.put("chName", name)
            firstItem.put("chImg", posterUrl)
            firstItem.put("chType", type)
            contentToProcess = parseToChContent(firstItem)
        } else {
            contentToProcess = chContent
        }

        var sourceUrl = contentToProcess.chUrl
        if (sourceUrl.contains("/live/selcuk")) {
            sourceUrl = sourceUrl.replace(Regex("^https?://[^/]+/live/"), selcukStreamBase)
        }
        val headers: MutableMap<String, String> = mutableMapOf()
        try {
            val chHeaders = contentToProcess.chHeaders
            val chReg = contentToProcess.chReg
            if (chHeaders.isNotBlank() && chHeaders != "null") {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (entry in jsonHeaders.keys()) {
                    headers[entry] = jsonHeaders[entry].toString()
                }
            }
            if (chReg.isNotBlank() && chReg != "null") {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                val cookie = jsonReg.optString("playSH2")
                if (cookie.isNotBlank()) {
                    headers["Cookie"] = cookie
                }
            }
        } catch (_: Exception) {
        }

        val extractorFound = loadExtractor(sourceUrl, subtitleCallback, callback)
        if (!extractorFound) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = contentToProcess.chName,
                    url = sourceUrl,
                    type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if (sourceUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                ) {
                    this.headers = headers
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }

    private suspend fun makeInatRequest(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val path = uri.rawPath.takeIf { !it.isNullOrBlank() } ?: "/"

        val sessionKey = generateRandomKey(16)
        val requestBody = "1=$sessionKey&0=$sessionKey"
        val bodyHash = sha256(requestBody)

        suspend fun execute(offset: Long): NiceResponse? {
            val nowSec = (System.currentTimeMillis() / 1000) + offset
            val nonce = generateRandomHex(16)
            val signString = "POST\n$path\n$nowSec\n$nonce\n$bodyHash"
            val signature = hmacSha256(hmacSecretKey, signString)

            val hostHeader = uri.host?.takeIf { it.isNotBlank() } ?: "speedrestapi.com"
            val headers = mapOf(
                "User-Agent" to "speedrestapi",
                "X-Requested-With" to "com.bp.box",
                "Referer" to "https://speedrestapi.com/",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Cache-Control" to "no-cache",
                "Host" to hostHeader,
                "X-Ts" to nowSec.toString(),
                "X-Nc" to nonce,
                "X-Sg" to signature
            )

            return try {
                app.post(
                    url = url,
                    headers = headers,
                    requestBody = requestBody.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()),
                    timeout = 6
                )
            } catch (e: Exception) {
                null
            }
        }

        var response = execute(timeOffset) ?: return null

        val serverTimeStr = response.headers["X-St"] ?: response.headers["x-st"]
        if (serverTimeStr != null && (response.code == 403 || !response.isSuccessful)) {
            val serverTime = serverTimeStr.toLongOrNull()
            if (serverTime != null) {
                val now = System.currentTimeMillis() / 1000
                timeOffset = serverTime - now
                response = execute(timeOffset) ?: return null
            }
        }

        return if (response.isSuccessful) {
            val encryptedResponse = response.body.string().trim()
            decryptInatResponse(encryptedResponse, sessionKey)
        } else {
            null
        }
    }

    private fun decryptAesCbc(encryptedText: String, keyStr: String): String? {
        return try {
            val parts = encryptedText.trim().split(":")
            val ct = Base64.decode(parts[0].trim(), Base64.DEFAULT)
            val iv = try {
                if (parts.size > 1 && parts[1].trim().isNotBlank()) {
                    val decoded = Base64.decode(parts[1].trim(), Base64.DEFAULT)
                    if (decoded.size == 16) decoded else keyStr.toByteArray(Charsets.ISO_8859_1)
                } else {
                    keyStr.toByteArray(Charsets.ISO_8859_1)
                }
            } catch (_: Exception) {
                keyStr.toByteArray(Charsets.ISO_8859_1)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.ISO_8859_1), "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val pt = cipher.doFinal(ct)
            String(pt, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHmacSuffix(raw: String): String {
        val t = raw.trim()
        if (t.length > 64) {
            val tail = t.substring(t.length - 64)
            if (tail.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return t.substring(0, t.length - 64).trim()
            }
        }
        return t
    }

    private fun decryptInatResponse(encryptedText: String, sessionKey: String): String? {
        val keysToTry = listOf(sessionKey, "ywevqtjrurkwtqgz", "a4osa8x1yl4w3vrk")
        for (key in keysToTry) {
            val pass1 = decryptAesCbc(encryptedText, key) ?: continue
            val clean1 = stripHmacSuffix(pass1)
            if (clean1.startsWith("[") || clean1.startsWith("{")) return clean1

            val pass2 = decryptAesCbc(pass1, key) ?: continue
            val clean2 = stripHmacSuffix(pass2)
            if (clean2.startsWith("[") || clean2.startsWith("{")) return clean2
        }
        return null
    }

    private fun decryptCertificateConfig(rawCert: String, keyStr: String): JSONObject? {
        return try {
            val pass1 = decryptAesCbc(rawCert, keyStr) ?: return null
            val pass2 = decryptAesCbc(pass1, keyStr) ?: return null
            val jsonStr = if (pass2.length > 64) pass2.substring(0, pass2.length - 64) else pass2
            JSONObject(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                if (!inatContentAllowed(item)) continue

                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.optString("diziImg")

                    val searchResponse = when (type) {
                        "dizi", "dizi_mode" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                        "film", "film_mode" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                        else -> null
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    val name = item.getString("chName")
                    val posterUrl = item.optString("chImg")
                    val chType = item.optString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "cable_sh", "tekli_regex_lb_sh_3" -> newLiveSearchResponse(
                            name,
                            item.toString(),
                            TvType.Live
                        ) {
                            this.posterUrl = posterUrl
                        }
                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "JSON arama listesi parse hatası: ${e.message}")
        }
        return searchResults
    }
}
