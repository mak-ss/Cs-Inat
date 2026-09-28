package com.UmayTrade

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class StarTvExtractor {

    private val extractorName = "Star TV"
    private val mainUrl = "https://www.startv.com.tr"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // CANLI YAYIN için (dokunulmadı)
    private val starTvAppId = "a20ac41e-bdc3-4aa1-934d-26b484480ac9"
    private val daionInitUrl = "https://dogus.daioncdn.net/options/init"
    private val daionBaseUrl = "https://dogus.daioncdn.net/startv"

    // DİZİ BÖLÜMLERİ İÇİN YENİ: DYG Video API
    private val dygVideoApiUrl = "https://dygvideo.dygdigital.com/api/video_info"
    private val dygSecretKey = "NtvApiSecret2014*"
    private val dygPublisherId = "1"   // Star TV için (Ntv için de aynı olabilir)

    private val daionM3u8Regex = Regex("""(https?://dogus[a-z-]*\.daioncdn\.net/startv/[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val sidRegex = Regex("""["']?sid["']?\s*[:=]\s*["']?([a-z0-9]+)["']?""")

    // DİZİ için sayfadaki referenceId
    private val referenceIdRegex = Regex(""""referenceId"\s*:\s*"([a-f0-9]+)"""")
    private val videoIdRegex = Regex(""""videoId"\s*:\s*"(\d+)"""")

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
            if (sid.isNullOrBlank()) sid = fetchSidFromInit()

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
                callback(newExtractorLink(
                    source = extractorName,
                    name = "Star TV Canlı ${quality}p",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.headers = mapOf("User-Agent" to userAgent, "Origin" to mainUrl, "Referer" to mainUrl)
                    this.quality = quality
                })
                success = true
            }
            success
        } catch (e: Exception) { false }
    }

    private suspend fun fetchSidFromPage(pageUrl: String): String? {
        return try {
            val doc = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent)).document
            val html = doc.html()
            sidRegex.find(html)?.groupValues?.get(1)?.let { return it }

            val iframeSrc = doc.select("iframe[src*=player], iframe[src*=canli], iframe[src*=live], iframe[src*=daion]")
                .firstOrNull()?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val iframeDoc = app.get(fixUrl(iframeSrc),
                    headers = mapOf("User-Agent" to userAgent, "Referer" to pageUrl)).document
                sidRegex.find(iframeDoc.html())?.groupValues?.get(1)?.let { return it }
            }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun fetchSidFromInit(): String? {
        return try {
            val response = app.get(daionInitUrl, headers = mapOf(
                "User-Agent" to userAgent, "Referer" to mainUrl,
                "Origin" to mainUrl, "Accept" to "application/json, text/plain, */*"
            )).text

            if (response.trim().startsWith("{")) {
                runCatching {
                    val json = JSONObject(response)
                    json.optString("sid").takeIf { it.isNotBlank() }?.let { return it }
                    json.optString("sessionId").takeIf { it.isNotBlank() }?.let { return it }
                    json.optJSONObject("data")?.optString("sid")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
            sidRegex.find(response)?.groupValues?.get(1)?.let { return it }
            if (response.length in 8..32 && response.matches(Regex("[a-z0-9]+"))) return response.trim()
            null
        } catch (e: Exception) { null }
    }

    // ============================================================
    // DİZİ BÖLÜMÜ (YENİ: DYG VIDEO API KULLANARAK)
    // ============================================================
    /**
     * Star TV dizi bölümleri için:
     * 1. Sayfadan referenceId ve videoId çekilir
     * 2. DYG Video API'ye istek atılır (PublisherId + ReferenceId + SecretKey)
     * 3. API, token'lı mncdn.com playlist URL'sini döner
     * 4. Bu URL doğrudan oynatıcıya verilir
     */
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            log("=== extractEpisodeStream START ===")
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val pageHtml = doc.html()

            val referenceId = referenceIdRegex.find(pageHtml)?.groupValues?.get(1)
            val videoId = videoIdRegex.find(pageHtml)?.groupValues?.get(1)
            log("referenceId=$referenceId, videoId=$videoId")

            if (referenceId.isNullOrBlank()) {
                log("FAILED: referenceId not found")
                return false
            }

            // DYG Video API'ye istek at
            val streamUrl = fetchFromDygVideoApi(referenceId, url)
            log("DYG API returned: $streamUrl")

            if (streamUrl.isNullOrBlank()) {
                log("FAILED: no stream URL from DYG API")
                return false
            }

            callback(newExtractorLink(
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
            })
            log("=== SUCCESS: $streamUrl ===")
            true
        } catch (e: Exception) {
            log("extractEpisodeStream EXCEPTION: ${e.message}")
            false
        }
    }

    /**
     * DYG Video API'den token'lı m3u8 URL'sini alır.
     * Endpoint: https://dygvideo.dygdigital.com/api/video_info
     * Parametreler: akamai, PublisherId, ReferenceId, SecretKey
     */
    private suspend fun fetchFromDygVideoApi(referenceId: String, refererUrl: String): String? {
        val apiUrl = "$dygVideoApiUrl?akamai=true&PublisherId=$dygPublisherId&ReferenceId=$referenceId&SecretKey=$dygSecretKey"
        log("Calling DYG API: $apiUrl")

        return try {
            val response = app.get(apiUrl, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to refererUrl,
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
            )).text

            log("DYG API response length: ${response.length}")
            log("DYG API response preview: ${response.take(500)}")

            if (response.isBlank()) return null

            if (response.trim().startsWith("{")) {
                val json = JSONObject(response)
                log("JSON keys: ${json.keys().asSequence().toList()}")

                // Olası alan isimleri
                val keys = listOf("videoUrl", "url", "contentUrl", "hlsUrl", "streamUrl",
                    "src", "file", "mediaUrl", "playlistUrl", "hls", "m3u8")

                for (key in keys) {
                    val value = json.optString(key)
                    if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil"))) {
                        log("Found stream in '$key': $value")
                        return value
                    }
                }

                // Nested objeleri dene
                for (objKey in listOf("data", "video", "flavors", "hls")) {
                    val obj = json.optJSONObject(objKey)
                    if (obj != null) {
                        log("Checking nested object: $objKey")
                        for (key in keys) {
                            val value = obj.optString(key)
                            if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil"))) {
                                log("Found stream in '$objKey.$key': $value")
                                return value
                            }
                        }
                        // flavors.hls gibi iki seviye
                        val hlsObj = obj.optJSONObject("hls")
                        if (hlsObj != null) {
                            for (key in keys) {
                                val value = hlsObj.optString(key)
                                if (value.isNotBlank() && (value.contains(".m3u8") || value.contains(".smil"))) {
                                    log("Found stream in '$objKey.hls.$key': $value")
                                    return value
                                }
                            }
                        }
                    }
                }

                // flavors.hls direkt string olabilir
                val flavorsObj = json.optJSONObject("flavors")
                if (flavorsObj != null) {
                    val hlsValue = flavorsObj.optString("hls")
                    if (hlsValue.isNotBlank() && hlsValue.contains(".m3u8")) {
                        log("Found flavors.hls: $hlsValue")
                        return hlsValue
                    }
                }

                // Regex fallback
                daionM3u8Regex.find(response)?.value?.let { return it }
                m3u8Regex.find(response)?.value?.let { return it }
            }

            // Plain text ise regex dene
            daionM3u8Regex.find(response)?.value?.let { return it }
            m3u8Regex.find(response)?.value?.let { return it }

            null
        } catch (e: Exception) {
            log("DYG API EXCEPTION: ${e.message}")
            null
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("//")) "https:$url"
        else if (url.startsWith("/")) "$mainUrl$url"
        else "$mainUrl/$url"
    }
}
