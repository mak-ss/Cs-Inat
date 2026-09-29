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

    // CANLI YAYIN (dokunulmadı)
    private val starTvAppId = "a20ac41e-bdc3-4aa1-934d-26b484480ac9"
    private val daionInitUrl = "https://dogus.daioncdn.net/options/init"
    private val daionBaseUrl = "https://dogus.daioncdn.net/startv"

    // DİZİ BÖLÜMLERİ için DYG Video API (DOĞRULANDI!)
    private val dygVideoApiUrl = "https://dygvideo.dygdigital.com/api/video_info"
    private val dygSecretKey = "NtvApiSecret2014*"
    private val dygPublisherId = "1"

    private val daionM3u8Regex = Regex("""(https?://dogus[a-z-]*\.daioncdn\.net/startv/[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val sidRegex = Regex("""["']?sid["']?\s*[:=]\s*["']?([a-z0-9]+)["']?""")

    // ESKİ (çok katı):
    // private val referenceIdRegex = Regex(""""referenceId"\s*:\s*"([a-f0-9]+)"""")

    // YENİ (esnek — tüm formatları yakalar):
    // "referenceId":"abc..."    → referenceId:"abc..."
    // \"referenceId\":\"abc...\" → escape'li JSON
    // reference_id: "abc..."     → snake_case
    // ReferenceId = "abc..."     → PascalCase, eşittir işareti
    private val referenceIdRegex = Regex(
        """reference[_]?[Ii]d["'\\]*\s*[:=]\s*["'\\]*([a-f0-9]{20,})""",
        RegexOption.IGNORE_CASE
    )

    // videoId için esnek regex (fallback)
    private val videoIdRegex = Regex(
        """video[_]?[Ii]d["'\\]*\s*[:=]\s*["'\\]*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private fun log(msg: String) = Log.e("StarTvDebug", msg)

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
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to mainUrl,
                        "Referer" to mainUrl
                    )
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
        } catch (e: Exception) { null }
    }

    private suspend fun fetchSidFromInit(): String? {
        return try {
            val response = app.get(daionInitUrl, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*"
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
            if (response.length in 8..32 && response.matches(Regex("[a-z0-9]+"))) {
                return response.trim()
            }
            null
        } catch (e: Exception) { null }
    }

    // ============================================================
    // DİZİ BÖLÜMÜ (DYG Video API + esnek regex + debug log)
    // ============================================================
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            log("=== extractEpisodeStream START ===")
            log("Episode URL: $url")

            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
            val pageHtml = doc.html()
            log("Page HTML length: ${pageHtml.length}")

            // -------- AŞAMA 1: referenceId ara --------
            var referenceId = referenceIdRegex.find(pageHtml)?.groupValues?.get(1)
            log("referenceId (regex1)=$referenceId")

            // -------- AŞAMA 2: Bulunamadıysa, context logla ve alternatif dene --------
            if (referenceId.isNullOrBlank()) {
                val refIndex = pageHtml.indexOf("eference", ignoreCase = true)
                if (refIndex >= 0) {
                    val start = maxOf(0, refIndex - 80)
                    val end = minOf(pageHtml.length, refIndex + 250)
                    log("DEBUG context: ${pageHtml.substring(start, end)}")
                } else {
                    log("DEBUG: 'reference' kelimesi sayfada hiç yok")
                }

                // videoId fallback
                val videoId = videoIdRegex.find(pageHtml)?.groupValues?.get(1)
                log("DEBUG videoId=$videoId")

                // __NEXT_DATA__ içinde olabilir mi?
                val nextDataMatch = Regex("""__NEXT_DATA__["\s:=]+(\{.+?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                    .find(pageHtml)
                if (nextDataMatch != null) {
                    log("DEBUG: __NEXT_DATA__ bulundu, içinde referenceId aranıyor...")
                    val nextData = nextDataMatch.groupValues[1]
                    referenceId = referenceIdRegex.find(nextData)?.groupValues?.get(1)
                    log("referenceId (from __NEXT_DATA__)=$referenceId")
                }
            }

            if (referenceId.isNullOrBlank()) {
                log("FAILED: referenceId bulunamadı")
                return false
            }

            // -------- AŞAMA 3: DYG API çağrısı --------
            val streamUrl = fetchFromDygVideoApi(referenceId, url)
            log("DYG API returned: $streamUrl")

            if (streamUrl.isNullOrBlank()) {
                log("FAILED: DYG API boş döndü")
                return false
            }

            // -------- AŞAMA 4: Oynatıcıya ver --------
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

    // ============================================================
    // DYG Video API
    // ============================================================
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
            log("DYG API response preview: ${response.take(300)}")

            if (response.isBlank()) return null

            val json = JSONObject(response)

            // Ana yol: data.flavors.hls
            val dataObj = json.optJSONObject("data")
            if (dataObj != null) {
                val flavorsObj = dataObj.optJSONObject("flavors")
                if (flavorsObj != null) {
                    // 1) flavors.hls
                    val hls = flavorsObj.optString("hls")
                    if (hls.isNotBlank() && hls.contains(".m3u8")) {
                        log("✅ Found flavors.hls: $hls")
                        return hls
                    }

                    // 2) flavors.hds
                    val hds = flavorsObj.optString("hds")
                    if (hds.isNotBlank() && hds.contains(".m3u8")) {
                        log("Found flavors.hds: $hds")
                        return hds
                    }

                    // 3) flavors."0".file_url_1
                    val zeroObj = flavorsObj.optJSONObject("0")
                    if (zeroObj != null) {
                        val fileUrl = zeroObj.optString("file_url_1")
                        if (fileUrl.isNotBlank() && fileUrl.contains(".m3u8")) {
                            log("Found flavors.0.file_url_1: $fileUrl")
                            return fileUrl
                        }
                    }
                }
            }

            // Fallback: tüm response'ta m3u8 ara
            daionM3u8Regex.find(response)?.value?.let { return it }
            m3u8Regex.find(response)?.value?.let { return it }

            log("No stream URL found in response")
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
