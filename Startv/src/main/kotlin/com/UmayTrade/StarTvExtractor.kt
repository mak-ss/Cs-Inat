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

    private val daionM3u8Regex = Regex("""(https?://dogus[a-z-]*\.daioncdn\.net/startv/[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val mp4Regex = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")
    private val sidRegex = Regex("""["']?sid["']?\s*[:=]\s*["']?([a-z0-9]+)["']?""")

    private val videoIdRegex = Regex(""""videoId"\s*:\s*"(\d+)"""")
    private val referenceIdRegex = Regex(""""referenceId"\s*:\s*"([a-f0-9]+)"""")

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
            e.printStackTrace()
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
    // DİZİ BÖLÜMÜ (LOG EKLENDİ)
    // ============================================================
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            log("=== extractEpisodeStream START ===")
            log("Episode URL: $url")

            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val pageHtml = doc.html()
            log("Page HTML length: ${pageHtml.length}")

            val videoId = videoIdRegex.find(pageHtml)?.groupValues?.get(1)
            val referenceId = referenceIdRegex.find(pageHtml)?.groupValues?.get(1)
            log("videoId=$videoId, referenceId=$referenceId")

            var streamUrl: String? = null

            if (!videoId.isNullOrBlank() && !referenceId.isNullOrBlank()) {
                log("Calling fetchStreamFromDygApi...")
                streamUrl = fetchStreamFromDygApi(videoId, referenceId, url)
                log("fetchStreamFromDygApi returned: $streamUrl")
            }

            if (streamUrl.isNullOrBlank()) {
                log("Falling back to findStreamUrl...")
                streamUrl = findStreamUrl(doc)
                log("findStreamUrl returned: $streamUrl")
            }

            if (streamUrl.isNullOrBlank()) {
                log("Trying iframe...")
                val iframeSrc = doc.select(
                    "iframe[src*=player], iframe[src*=video], iframe[src*=embed], iframe[src*=dyg]"
                ).firstOrNull()?.attr("src")
                log("iframeSrc=$iframeSrc")

                if (!iframeSrc.isNullOrBlank()) {
                    val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )).document
                    val iframeHtml = iframeDoc.html()
                    log("iframe HTML length: ${iframeHtml.length}")

                    val iframeVideoId = videoIdRegex.find(iframeHtml)?.groupValues?.get(1)
                    val iframeRefId = referenceIdRegex.find(iframeHtml)?.groupValues?.get(1)
                    log("iframe videoId=$iframeVideoId, refId=$iframeRefId")

                    if (!iframeVideoId.isNullOrBlank() && !iframeRefId.isNullOrBlank()) {
                        streamUrl = fetchStreamFromDygApi(iframeVideoId, iframeRefId, url)
                        log("iframe fetchStreamFromDygApi returned: $streamUrl")
                    }
                    if (streamUrl.isNullOrBlank()) {
                        streamUrl = findStreamUrl(iframeDoc)
                        log("iframe findStreamUrl returned: $streamUrl")
                    }
                }
            }

            if (streamUrl.isNullOrBlank()) {
                log("=== FAILED: no stream URL found ===")
                return false
            }

            if (streamUrl.contains("startv.com.tr/dizi/") ||
                streamUrl.contains("startv.com.tr/canli-yayin")) {
                log("Rejected invalid stream URL: $streamUrl")
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
            e.printStackTrace()
            false
        }
    }

    /**
     * DYG Digital player API'sinden video stream linkini alır.
     * Her endpoint denemesini loglar.
     */
    private suspend fun fetchStreamFromDygApi(
        videoId: String,
        referenceId: String,
        refererUrl: String
    ): String? {
        val endpoints = listOf(
            "https://www.startv.com.tr/api/video/$videoId?referenceId=$referenceId",
            "https://www.startv.com.tr/api/video/$videoId",
            "https://player.dygdigital.com/api/video/$videoId?ref=$referenceId",
            "https://dygdigital.com/api/video/$videoId?ref=$referenceId"
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

                log("Response length: ${response.length}")
                log("Response preview: ${response.take(300)}")

                if (response.isBlank()) continue

                if (response.trim().startsWith("{")) {
                    val json = JSONObject(response)
                    val possibleKeys = listOf(
                        "videoUrl", "url", "contentUrl", "hlsUrl",
                        "streamUrl", "src", "file", "mediaUrl"
                    )

                    for (key in possibleKeys) {
                        val value = json.optString(key)
                        if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                            log("Found stream in key '$key': $value")
                            return value
                        }
                    }

                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null) {
                        for (key in possibleKeys) {
                            val value = dataObj.optString(key)
                            if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                                log("Found stream in data.$key: $value")
                                return value
                            }
                        }
                    }

                    val videoObj = json.optJSONObject("video")
                    if (videoObj != null) {
                        for (key in possibleKeys) {
                            val value = videoObj.optString(key)
                            if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil") || value.contains(".mp4"))) {
                                log("Found stream in video.$key: $value")
                                return value
                            }
                        }
                    }

                    daionM3u8Regex.find(response)?.value?.let {
                        log("Found via daionM3u8Regex: $it")
                        return it
                    }
                    m3u8Regex.find(response)?.value?.let {
                        log("Found via m3u8Regex: $it")
                        return it
                    }
                } else {
                    daionM3u8Regex.find(response)?.value?.let {
                        log("Found in plain text via daionM3u8Regex: $it")
                        return it
                    }
                    m3u8Regex.find(response)?.value?.let {
                        log("Found in plain text via m3u8Regex: $it")
                        return it
                    }
                }
            } catch (e: Exception) {
                log("Endpoint FAILED: $endpoint -> ${e.message}")
                continue
            }
        }

        return null
    }

    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()

        val mncdnSmilRegex = Regex("""(https?://startv-p\d+\.mncdn\.com/smil:[^\s"'<>\\]+?\.smil[^\s"'<>\\]*)""")
        mncdnSmilRegex.find(html)?.value?.let {
            log("findStreamUrl mncdnSmil: $it")
            return it
        }

        val mncdnM3u8Regex = Regex("""(https?://startv-p\d+\.mncdn\.com/[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*)""")
        mncdnM3u8Regex.find(html)?.value?.let {
            log("findStreamUrl mncdnM3u8: $it")
            return it
        }

        daionM3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains(".ts")) {
                log("findStreamUrl daion: $url")
                return url
            }
        }

        m3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi") &&
                !url.contains("startv.com.tr/canli-yayin") &&
                !url.contains(".ts")) {
                log("findStreamUrl generic m3u8: $url")
                return url
            }
        }

        mp4Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi")) {
                log("findStreamUrl mp4: $url")
                return url
            }
        }

        log("findStreamUrl: nothing found")
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
