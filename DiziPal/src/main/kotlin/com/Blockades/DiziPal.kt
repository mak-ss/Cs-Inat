package com.Blockades

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Dizipal : MainAPI() {
    override var mainUrl = "https://dizipal1432.com"
    override var name = "Dizipal"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    companion object {
        private const val TAG = "DiziPalLog"
    }

    // 1. ANA SAYFA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage çağrıldı. Sayfa: $page, URL: $mainUrl")
        return runCatching {
            val response = app.get(mainUrl)
            Log.d(TAG, "getMainPage HTTP Yanıt Kodu: ${response.code}")
            
            val document = response.document
            val items = document.select("a[href*='/film/'], a[href*='/dizi/']")
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toSearchResult() }
            
            Log.d(TAG, "getMainPage bulunan içerik sayısı: ${items.size}")
            val homeLists = if (items.isEmpty()) emptyList() else listOf(HomePageList("Öne Çıkanlar", items))
            newHomePageResponse(homeLists)
        }.getOrElse { e ->
            Log.e(TAG, "getMainPage hatası!", e)
            newHomePageResponse(emptyList())
        }
    }

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search çağrıldı. Sorgu: $query")
        return runCatching {
            val searchUrl = "$mainUrl/arama?q=${URLEncoder.encode(query, "UTF-8")}"
            val response = app.get(searchUrl)
            Log.d(TAG, "search HTTP Yanıt Kodu: ${response.code}")

            val results = response.document.select("a[href*='/film/'], a[href*='/dizi/']")
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toSearchResult() }

            Log.d(TAG, "search sonuç sayısı: ${results.size}")
            results
        }.getOrElse { e ->
            Log.e(TAG, "search hatası! Sorgu: $query", e)
            emptyList()
        }
    }

    // 3. DETAY VE BÖLÜM LİSTESİ
    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load çağrıldı. URL: $url")
        val response = app.get(url)
        val document = response.document
        val ldCombined = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

        val title = document.selectFirst("h1, .entry-title, .title")?.text()?.trim() ?: "Bilinmeyen Başlık"
        val rawPoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst("img")?.attr("src")
        val poster = fixUrlNull(rawPoster)
        
        val descriptionFromLd = Regex("\"description\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])+)\"")
            .findAll(ldCombined).map { match ->
                match.groupValues[1].replace("\\\\/", "/").replace("\\u0027", "'").replace("\\u0026", "&").trim()
            }.firstOrNull { it.length > 40 }
            
        val description = descriptionFromLd
            ?: document.selectFirst("p.xf19fb0, .xf19fb0, .description p, .overview p")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()

        val year = Regex("\"datePublished\"\\s*:\\s*\"?((?:19|20)\\d{2})\"?").find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()
        val imdbScore = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?").find(ldCombined)?.groupValues?.get(1)?.toDoubleOrNull()
        val durationMinutes = Regex("\"duration\"\\s*:\\s*\"PT(\\d+)M\"", RegexOption.IGNORE_CASE).find(ldCombined)?.groupValues?.get(1)?.toIntOrNull()

        val trailerUrl = document.select("a[href]").firstOrNull {
            it.text().contains("Fragman", ignoreCase = true) || it.attr("href").contains("youtube.com|youtu.be".toRegex(RegexOption.IGNORE_CASE))
        }?.attr("href")?.replace("youtube.com/embed/", "youtube.com/watch?v=")

        val episodeElements = document.select("a[href*='/bolum/']")
        val isSeries = url.contains("/dizi/")
        
        Log.d(TAG, "load parsed -> Başlık: $title, Tür: ${if (isSeries) "Dizi" else "Film"}, Bölüm Sayısı: ${episodeElements.size}")

        return if (isSeries) {
            val episodes = episodeElements.mapIndexed { index: Int, element: Element ->
                val epUrl = fixUrl(element.attr("href"))
                val rawText = element.text()

                val seasonNum = Regex("-(\\d+)-sezon", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                val epNum = Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                newEpisode(epUrl) {
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = durationMinutes
                trailerUrl?.let { this.trailers = mutableListOf(TrailerData(it, referer = url, raw = false)) }
            }
        }
    }

    // 4. VİDEO KAYNAKLARI (LOG DETAYLANDIRILDI)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks başlatıldı. İstenen Target Data: $data")
        var foundLinks = false

        val response = runCatching { 
            app.get(data, headers = mapOf("User-Agent" to USER_AGENT)) 
        }.onFailure { e ->
            Log.e(TAG, "loadLinks target URL çekilirken ağ hatası alındı: $data", e)
        }.getOrNull() ?: return false

        Log.d(TAG, "Target Sayfa Yüklendi. HTTP Status: ${response.code}")
        val rawHtml = response.text
        val iframeLinks = mutableSetOf<String>()

        // 1. BASE64 Config Taraması
        Regex("""eyJ[a-zA-Z0-9_=-]+""").findAll(rawHtml).forEach { match ->
            runCatching {
                val decoded = String(Base64.decode(match.value, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("imagestoo") || decoded.contains("v\"")) {
                    val json = JSONObject(decoded)
                    listOf("v", "file", "url").forEach { key ->
                        json.optString(key).takeIf { it.startsWith("http") }?.let { 
                            iframeLinks.add(it)
                            Log.d(TAG, "Base64 içinden iframe linki çözümlendi: $it")
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Base64 çözme esnasında hata/atlanıldı.", it) }
        }

        // 2. DOM Iframe Taraması
        response.document.select("iframe, [data-frame], [data-video], [data-src]").forEach {
            val src = it.attr("data-frame").ifEmpty { it.attr("data-video") }.ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") }
            if (src.isNotBlank() && !src.startsWith("#")) {
                fixUrlNull(src)?.let { cleanedSrc -> 
                    iframeLinks.add(cleanedSrc)
                    Log.d(TAG, "DOM üzerinde iframe/video tagi bulundu: $cleanedSrc")
                }
            }
        }

        Log.d(TAG, "İşlenecek toplam iframe sayısı: ${iframeLinks.size}")

        for (iframeUrl in iframeLinks.distinct()) {
            val cleanIframe = iframeUrl.replace("&amp;", "&").trim()
            if (!cleanIframe.startsWith("http")) continue

            Log.d(TAG, "Iframe işleniyor -> $cleanIframe")

            // --- Imagestoo API İşleme ---
            if (cleanIframe.contains("imagestoo.com")) {
                val hash = cleanIframe.substringAfter("video/").substringBefore("?").trim()
                Log.d(TAG, "Imagestoo tespit edildi. Extracted Hash: $hash")
                
                if (hash.isNotBlank()) {
                    val normalizedIframe = "https://imagestoo.com/video/$hash"
                    val allCookies = mutableMapOf<String, String>()

                    val iframeResp = runCatching {
                        app.get(
                            url = normalizedIframe,
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                        )
                    }.onFailure { Log.e(TAG, "Imagestoo iframe isteği başarısız!", it) }.getOrNull()

                    iframeResp?.cookies?.let { allCookies.putAll(it) }

                    val apiResponseText = runCatching {
                        app.post(
                            url = "https://imagestoo.com/player/index.php?data=$hash&do=getVideo",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to "https://imagestoo.com",
                                "Referer" to normalizedIframe,
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            data = mapOf("hash" to hash, "r" to "$mainUrl/"),
                            cookies = allCookies
                        ).text
                    }.onFailure { Log.e(TAG, "Imagestoo POST (getVideo) isteği başarısız!", it) }.getOrNull()

                    if (apiResponseText != null) {
                        Log.d(TAG, "Imagestoo API Yanıtı: $apiResponseText")
                        runCatching {
                            val json = JSONObject(apiResponseText)
                            val securedLink = json.optString("securedLink").takeIf { it.isNotBlank() }?.replace("\\/", "/")
                            val videoSource = json.optString("videoSource").takeIf { it.isNotBlank() }?.replace("\\/", "/")
                            val targetUrl = securedLink ?: videoSource

                            if (targetUrl != null) {
                                val finalPlaybackUrl = if (targetUrl.contains(".m3u8")) targetUrl else "$targetUrl#.m3u8"
                                Log.d(TAG, "Imagestoo Başarılı! Oynatma Bağlantısı: $finalPlaybackUrl")

                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "Imagestoo VIP",
                                        url = finalPlaybackUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = normalizedIframe
                                        this.headers = mapOf(
                                            "Origin" to "https://imagestoo.com",
                                            "Referer" to normalizedIframe,
                                            "User-Agent" to USER_AGENT,
                                            "Cookie" to allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                                        )
                                    }
                                )
                                foundLinks = true
                            } else {
                                Log.w(TAG, "Imagestoo JSON yanıtında 'securedLink' veya 'videoSource' bulunamadı.")
                            }
                        }.onFailure { Log.e(TAG, "Imagestoo JSON parse hatası!", it) }
                    }
                }
                continue 
            }

            // --- Standart M3U8/MP4 Doğrudan Bağlantılar ---
            if (cleanIframe.contains(".m3u8") || cleanIframe.contains(".mp4")) {
                Log.d(TAG, "Doğrudan Medya Bağlantısı Bulundu: $cleanIframe")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Dizipal Kaynak",
                        url = cleanIframe,
                        type = if (cleanIframe.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = mainUrl }
                )
                foundLinks = true
                continue
            }

            // --- JS Unpack & Alternatif Kaynaklar ---
            val embedResReq = runCatching {
                app.get(url = cleanIframe, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/"))
            }.onFailure { Log.e(TAG, "Alternatif Embed isteği başarısız: $cleanIframe", it) }.getOrNull() ?: continue

            val unpacked = unpackJs(embedResReq.text) ?: embedResReq.text
            val extractedVideo = Regex("""(?i)(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)

            if (extractedVideo != null) {
                val finalVideoUrl = extractedVideo.replace("\\/", "/")
                Log.d(TAG, "Unpacked/Regex üzerinden alternatif video bulundu: $finalVideoUrl")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Dizipal Alternatif",
                        url = finalVideoUrl,
                        type = if (finalVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = cleanIframe }
                )
                foundLinks = true
            } else {
                Log.d(TAG, "Otomatik Extractor yükleyicisine yönlendiriliyor -> $cleanIframe")
                if (loadExtractor(cleanIframe, subtitleCallback, callback)) {
                    foundLinks = true
                    Log.d(TAG, "loadExtractor başarılı bir şekilde kaynak buldu.")
                }
            }
        }

        Log.d(TAG, "loadLinks tamamlandı. Sonuç (Link Bulundu mu?): $foundLinks")
        return foundLinks
    }

    private fun unpackJs(packed: String): String? {
        val pattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(packed) ?: return null
        return runCatching {
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val dict = match.groupValues[4].split("|")
            val wordMap = (0 until (match.groupValues[3].toIntOrNull() ?: 0)).associate { i -> 
                i.toString(radix) to (dict.getOrNull(i).takeIf { !it.isNullOrEmpty() } ?: i.toString(radix))
            }
            Regex("""\b\w+\b""").replace(match.groupValues[1]) { m -> wordMap[m.value] ?: m.value }
        }.getOrNull()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrl(linkElem.attr("href"))
        val title = this.selectFirst(".title, h2, h3, .name")?.text()?.trim()?.removeSuffix(" izle") ?: linkElem.text().trim()
        if (title.isEmpty()) return null
        
        val rawImg = this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src")
        val posterUrl = fixUrlNull(rawImg)

        return if (href.contains("/dizi/")) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
}
