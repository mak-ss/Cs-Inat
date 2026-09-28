package com.UmayTrade

import android.util.Log
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

    private val starTvAppId = "a20ac41e-bdc3-4aa1-934d-26b484480ac9"
    private val daionInitUrl = "https://dogus.daioncdn.net/options/init"
    private val daionBaseUrl = "https://dogus.daioncdn.net/startv"

    // mncdn.com tabanlı SMIL CDN base URL'si (loglardan çıkarıldı)
    private val mncdnBaseUrl = "https://startv-p3.mncdn.com/smil:"

    private val daionM3u8Regex = Regex("""(https?://dogus[a-z-]*\.daioncdn\.net/startv/[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val mp4Regex = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")
    private val sidRegex = Regex("""["']?sid["']?\s*[:=]\s*["']?([a-z0-9]+)["']?""")

    // DİZİ için: sayfadaki videoId, referenceId ve filename'i yakala
    private val videoIdRegex = Regex(""""videoId"\s*:\s*"(\d+)"""")
    private val referenceIdRegex = Regex(""""referenceId"\s*:\s*"([a-f0-9]+)"""")
    // filename örneği: "filename":"tuzlu_kahve_s1b01_dd.mp4"
    private val filenameRegex = Regex(""""filename"\s*:\s*"([^"]+\.mp4)"""")

    // Doğrudan sayfada SMIL linki varsa (bazı sayfalarda olabilir)
    private val mncdnSmilRegex = Regex("""(https?://startv-p\d+\.mncdn\.com/smil:[^\s"'<>\\]+?\.smil[^\s"'<>\\]*)""")

    private fun log(msg: String) = Log.d("StarTvDebug", msg)

    suspend fun getUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            log("getUrl called: $url")
            if (url.contains("canli-yayin")) {
                extractLiveStream(url, callback)
            } else {
                extractEpisodeStream(url, callback)
            }
        } catch (e: Exception) {
            log("getUrl EXCEPTION: ${e.message}")
            false
        }
    }

    // ============================================================
    // CANLI YAYIN (DEĞİŞTİRİLMEDİ)
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
                        name = "Star TV Canlı ${quality}p",
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
    // DİZİ BÖLÜMÜ (YENİ YÖNTEM - filename'den SMIL URL üretimi)
    // ============================================================
    /**
     * Star TV dizi bölümleri şu şekilde çalışır:
     *
     * 1. Bölüm sayfasında JSON içinde "filename":"tuzlu_kahve_s1b01_dd.mp4" gömülüdür.
     * 2. Bu dosya adından SMIL URL'si türetilir:
     *    - Uzantı (.mp4) atılır
     *    - Sonuna "_smil.smil" eklenir
     *    - mncdn base URL'si ile birleştirilir
     * 3. Sonuç: https://startv-p3.mncdn.com/smil:tuzlu_kahve_s1b01_dd_smil.smil/playlist.m3u8
     *
     * Bu SMIL URL'si doğrudan M3U8 olarak oynatılabilir.
     */
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            log("=== extractEpisodeStream START ===")
            log("Episode URL: $url")

            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val pageHtml = doc.html()
            log("Page HTML length: ${pageHtml.length}")

            // 1. Sayfadan filename'i çek (en güvenilir yöntem)
            val filename = filenameRegex.find(pageHtml)?.groupValues?.get(1)
            log("filename=$filename")

            var streamUrl: String? = null

            // 2. filename'den SMIL URL'si üret
            if (!filename.isNullOrBlank()) {
                val smilUrl = buildSmilUrl(filename)
                log("Generated SMIL URL: $smilUrl")
                streamUrl = smilUrl
            }

            // 3. Sayfada zaten gömülü bir SMIL linki varsa onu kullan (fallback)
            if (streamUrl.isNullOrBlank()) {
                val embeddedSmil = mncdnSmilRegex.find(pageHtml)?.value
                log("Embedded SMIL: $embeddedSmil")
                if (!embeddedSmil.isNullOrBlank()) {
                    streamUrl = embeddedSmil
                }
            }

            // 4. Hala bulunamadıysa sayfadan videoId/referenceId ile API dene (son çare)
            if (streamUrl.isNullOrBlank()) {
                val videoId = videoIdRegex.find(pageHtml)?.groupValues?.get(1)
                val referenceId = referenceIdRegex.find(pageHtml)?.groupValues?.get(1)
                log("Fallback IDs: videoId=$videoId, refId=$referenceId")

                if (!videoId.isNullOrBlank() && !referenceId.isNullOrBlank()) {
                    streamUrl = fetchStreamFromDygApi(videoId, referenceId, url)
                    log("API returned: $streamUrl")
                }
            }

            if (streamUrl.isNullOrBlank()) {
                log("=== FAILED: no stream URL ===")
                return false
            }

            log("=== SUCCESS: streamUrl=$streamUrl ===")
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
            log("extractEpisodeStream EXCEPTION: ${e.message}")
            false
        }
    }

    /**
     * filename'den mncdn SMIL URL'sini üretir.
     *
     * Örnek:
     *   Input:  "tuzlu_kahve_s1b01_dd.mp4"
     *   Output: "https://startv-p3.mncdn.com/smil:tuzlu_kahve_s1b01_dd_smil.smil/playlist.m3u8"
     *
     * Eğer filename zaten "_smil.smil" içeriyorsa sadece base URL ekler.
     */
    private fun buildSmilUrl(filename: String): String {
        // Uzantıyı at
        val baseName = filename.substringBeforeLast(".")
        // _smil.smil uzantısını ekle
        val smilName = "${baseName}_smil.smil"
        return "$mncdnBaseUrl$smilName/playlist.m3u8"
    }

    /**
     * DYG Digital player API'sinden video stream linkini alır (fallback).
     */
    private suspend fun fetchStreamFromDygApi(
        videoId: String,
        referenceId: String,
        refererUrl: String
    ): String? {
        val endpoints = listOf(
            "https://www.startv.com.tr/api/video/$videoId?referenceId=$referenceId",
            "https://www.startv.com.tr/api/video/$videoId"
        )

        for (endpoint in endpoints) {
            log("Trying endpoint: $endpoint")
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
                if (response.trim().startsWith("{")) {
                    val json = JSONObject(response)
                    for (key in listOf("videoUrl", "url", "contentUrl", "hlsUrl", "streamUrl")) {
                        val value = json.optString(key)
                        if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil"))) {
                            return value
                        }
                    }
                    mncdnSmilRegex.find(response)?.value?.let { return it }
                    m3u8Regex.find(response)?.value?.let { return it }
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()

        mncdnSmilRegex.find(html)?.value?.let { return it }

        val mncdnM3u8Regex = Regex("""(https?://startv-p\d+\.mncdn\.com/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)""")
        mncdnM3u8Regex.find(html)?.value?.let { return it }

        daionM3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains(".ts")) return url
        }

        m3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi") &&
                !url.contains("startv.com.tr/canli-yayin") &&
                !url.contains(".ts")) {
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
