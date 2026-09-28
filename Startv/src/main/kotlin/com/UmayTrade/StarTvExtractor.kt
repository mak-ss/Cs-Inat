package com.UmayTrade

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document

class StarTvExtractor {

    private val extractorName = "Star TV"
    private val mainUrl = "https://www.startv.com.tr"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Star TV'nin SABİT uygulama ID'si (startvcanli.txt'den alındı)
    private val starTvAppId = "a20ac41e-bdc3-4aa1-934d-26b484480ac9"

    // Daion CDN base URLs
    private val daionInitUrl = "https://dogus.daioncdn.net/options/init"
    private val daionBaseUrl = "https://dogus.daioncdn.net/startv"

    // Regex'ler
    private val daionM3u8Regex = Regex("""(https?://dogus[a-z-]*\.daioncdn\.net/startv/[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val mp4Regex = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")
    private val sidRegex = Regex("""["']?sid["']?\s*[:=]\s*["']?([a-z0-9]+)["']?""")

    // DİZİ için: sayfadaki videoId ve referenceId'yi yakalayan regex'ler
    // Örnek: "videoId":"1033298","referenceId":"6a97c234a46e38b3a3925061"
    private val videoIdRegex = Regex(""""videoId"\s*:\s*"(\d+)"""")
    private val referenceIdRegex = Regex(""""referenceId"\s*:\s*"([a-f0-9]+)"""")

    suspend fun getUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (url.contains("canli-yayin")) {
                extractLiveStream(url, callback)
            } else {
                // DİZİ BÖLÜMLERİ İÇİN YENİ YÖNTEM
                extractEpisodeStream(url, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    // ============================================================
    // CANLI YAYIN (DEĞİŞTİRİLMEDİ - AYNEN KORUNDU)
    // ============================================================
    private suspend fun extractLiveStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            var sid = fetchSidFromPage(url)
            if (sid.isNullOrBlank()) {
                sid = fetchSidFromInit()
            }

            val qualityUrls = mutableListOf<Pair<Int, String>>()

            if (!sid.isNullOrBlank()) {
                qualityUrls.add(720 to "$daionBaseUrl/startv_720p.m3u8?&sid=$sid&app=$starTvAppId&ce=3")
                qualityUrls.add(480 to "$daionBaseUrl/startv_480p.m3u8?&sid=$sid&app=$starTvAppId&ce=3")
            }

            if (qualityUrls.isEmpty()) {
                qualityUrls.add(720 to "$daionBaseUrl/startv_720p.m3u8?&app=$starTvAppId&ce=3")
                qualityUrls.add(480 to "$daionBaseUrl/startv_480p.m3u8?&app=$starTvAppId&ce=3")
            }

            var success = false
            for ((quality, streamUrl) in qualityUrls) {
                callback(
                    newExtractorLink(
                        source = extractorName,
                        name = "Star TV Canlı $quality" + "p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to mainUrl,
                            "Referer" to mainUrl
                        )
                        this.quality = quality
                    }
                )
                success = true
            }

            success
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchSidFromPage(pageUrl: String): String? {
        return try {
            val doc = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent)).document
            val html = doc.html()

            sidRegex.find(html)?.groupValues?.get(1)?.let { return it }

            val iframeSrc = doc.select(
                "iframe[src*=player], iframe[src*=canli], iframe[src*=live], iframe[src*=daion]"
            ).firstOrNull()?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to pageUrl
                )).document
                sidRegex.find(iframeDoc.html())?.groupValues?.get(1)?.let { return it }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSidFromInit(): String? {
        return try {
            val response = app.get(
                daionInitUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text

            if (response.trim().startsWith("{")) {
                runCatching {
                    val json = JSONObject(response)
                    json.optString("sid").takeIf { it.isNotBlank() }?.let { return it }
                    json.optString("sessionId").takeIf { it.isNotBlank() }?.let { return it }
                    json.optJSONObject("data")?.optString("sid")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }

            sidRegex.find(response)?.groupValues?.get(1)?.let { return it }

            if (response.length in 8..32 && response.matches(Regex("[a-z0-9]+"))) {
                return response.trim()
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    // DİZİ BÖLÜMÜ (YENİ YÖNTEM - SMIL/M3U8 ÜZERİNDEN)
    // ============================================================
    /**
     * Star TV dizi bölümleri şu şekilde çalışır:
     * 1. Bölüm sayfasında "videoId" ve "referenceId" değerleri JSON içinde gömülüdür.
     * 2. Bu ID'ler ile DYG Digital player API'sine istek atılır.
     * 3. API, bir SMIL manifesto linki (m3u8 benzeri) döner.
     * 4. Bu SMIL linki, .ts segmentlerine işaret eder (tuzlukagvelist.txt'de görüldüğü gibi).
     *
     * Örnek bölüm sayfası: https://www.startv.com.tr/dizi/tuzlu-kahve/bolumler/1-bolum
     * Örnek videoId: 1033298
     * Örnek referenceId: 6a97c234a46e38b3a3925061
     * Örnek çıktı: https://startv-p3.mncdn.com/smil:tuzlu_kahve_s1b01_dd_smil.smil/playlist.m3u8
     */
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val pageHtml = doc.html()

            // 1. Sayfadan videoId ve referenceId'yi çek
            val videoId = videoIdRegex.find(pageHtml)?.groupValues?.get(1)
            val referenceId = referenceIdRegex.find(pageHtml)?.groupValues?.get(1)

            var streamUrl: String? = null

            // 2. Eğer ID'ler bulunduysa DYG Digital player API'sine istek at
            if (!videoId.isNullOrBlank() && !referenceId.isNullOrBlank()) {
                streamUrl = fetchStreamFromDygApi(videoId, referenceId, url)
            }

            // 3. API başarısız olursa, doğrudan sayfada/frame'de SMIL veya m3u8 ara (fallback)
            if (streamUrl.isNullOrBlank()) {
                streamUrl = findStreamUrl(doc)
            }

            // 4. Iframe içinde de ara (bazı bölümler iframe ile yüklenebilir)
            if (streamUrl.isNullOrBlank()) {
                val iframeSrc = doc.select(
                    "iframe[src*=player], iframe[src*=video], iframe[src*=embed], iframe[src*=dyg]"
                ).firstOrNull()?.attr("src")

                if (!iframeSrc.isNullOrBlank()) {
                    val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )).document

                    // Iframe içinden videoId/referenceId çıkarmayı dene
                    val iframeHtml = iframeDoc.html()
                    val iframeVideoId = videoIdRegex.find(iframeHtml)?.groupValues?.get(1)
                    val iframeRefId = referenceIdRegex.find(iframeHtml)?.groupValues?.get(1)

                    if (!iframeVideoId.isNullOrBlank() && !iframeRefId.isNullOrBlank()) {
                        streamUrl = fetchStreamFromDygApi(iframeVideoId, iframeRefId, url)
                    }

                    if (streamUrl.isNullOrBlank()) {
                        streamUrl = findStreamUrl(iframeDoc)
                    }
                }
            }

            if (streamUrl.isNullOrBlank()) return false

            // Sayfa URL'sinin stream olarak gönderilmesini engelle
            if (streamUrl.contains("startv.com.tr/dizi/") ||
                streamUrl.contains("startv.com.tr/canli-yayin")) {
                return false
            }

            callback(
                newExtractorLink(
                    source = extractorName,
                    name = "Star TV",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to mainUrl,
                        "Referer" to url
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DYG Digital player API'sinden video stream linkini alır.
     * Bu API, Star TV'nin kendi altyapısıdır ve videoId + referenceId ile çalışır.
     *
     * Olası endpoint'ler:
     * - https://www.startv.com.tr/api/video/{videoId}?ref={referenceId}
     * - https://dygdigital.com/api/video/{videoId}
     * - https://player.dygdigital.com/api/video/{videoId}
     */
    private suspend fun fetchStreamFromDygApi(
        videoId: String,
        referenceId: String,
        refererUrl: String
    ): String? {
        // Denenecek API endpoint'leri (ilk çalışanı kullan)
        val endpoints = listOf(
            "https://www.startv.com.tr/api/video/$videoId?referenceId=$referenceId",
            "https://www.startv.com.tr/api/video/$videoId",
            "https://player.dygdigital.com/api/video/$videoId?ref=$referenceId",
            "https://dygdigital.com/api/video/$videoId?ref=$referenceId"
        )

        for (endpoint in endpoints) {
            try {
                val response = app.get(
                    endpoint,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to refererUrl,
                        "Origin" to mainUrl,
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text

                if (response.isBlank()) continue

                // JSON yanıtı mı kontrol et
                if (response.trim().startsWith("{")) {
                    val json = JSONObject(response)

                    // Yaygın alan isimlerini dene
                    val possibleKeys = listOf(
                        "videoUrl", "url", "contentUrl", "hlsUrl",
                        "streamUrl", "src", "file", "mediaUrl"
                    )

                    for (key in possibleKeys) {
                        val value = json.optString(key)
                        if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                            return value
                        }
                    }

                    // İç içe objeleri de kontrol et
                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null) {
                        for (key in possibleKeys) {
                            val value = dataObj.optString(key)
                            if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                                return value
                            }
                        }
                    }

                    val videoObj = json.optJSONObject("video")
                    if (videoObj != null) {
                        for (key in possibleKeys) {
                            val value = videoObj.optString(key)
                            if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                                return value
                            }
                        }
                    }

                    // Son çare: response içinde m3u8/smil regex ile ara
                    daionM3u8Regex.find(response)?.value?.let { return it }
                    m3u8Regex.find(response)?.value?.let { return it }
                } else {
                    // Düz metin yanıtı - içinde m3u8/smil var mı?
                    daionM3u8Regex.find(response)?.value?.let { return it }
                    m3u8Regex.find(response)?.value?.let { return it }
                }
            } catch (e: Exception) {
                // Bu endpoint çalışmadı, sonrakini dene
                continue
            }
        }

        return null
    }

    /**
     * Sayfada doğrudan SMIL veya m3u8 linki var mı diye arar (fallback).
     * Loglara göre Star TV bölümleri için linkler şu formatta:
     * https://startv-p3.mncdn.com/smil:tuzlu_kahve_s1b01_dd_smil.smil/playlist.m3u8
     */
    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()

        // 1. mncdn.com SMIL linklerini ara (Star TV'nin gerçek CDN'i)
        val mncdnSmilRegex = Regex("""(https?://startv-p\d+\.mncdn\.com/smil:[^\s"'<>\\]+?\.smil[^\s"'<>\\]*)""")
        mncdnSmilRegex.find(html)?.value?.let { return it }

        // 2. mncdn.com m3u8 linkleri
        val mncdnM3u8Regex = Regex("""(https?://startv-p\d+\.mncdn\.com/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)""")
        mncdnM3u8Regex.find(html)?.value?.let { return it }

        // 3. Daion CDN (canlı yayın için kullanılıyor, ama bölümlerde de olabilir)
        daionM3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains(".ts")) return url
        }

        // 4. Genel m3u8 araması
        m3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi") &&
                !url.contains("startv.com.tr/canli-yayin") &&
                !url.contains(".ts")) {
                return url
            }
        }

        // 5. mp4 fallback
        mp4Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi")) {
                return url
            }
        }

        return null
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            "${mainUrl}$url"
        } else {
            "${mainUrl}/$url"
        }
    }
}
