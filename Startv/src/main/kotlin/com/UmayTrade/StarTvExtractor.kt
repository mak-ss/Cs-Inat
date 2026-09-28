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

    suspend fun getUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (url.contains("canli-yayin")) {
                return extractLiveStream(url, callback)
            }
            extractEpisodeStream(url, callback)
        } catch (e: Exception) {
            false
        }
    }

    // ============================================================
    // CANLI YAYIN
    // ============================================================
    private suspend fun extractLiveStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // 1. sid'yi sayfadan veya init endpoint'inden al
            var sid = fetchSidFromPage(url)
            if (sid.isNullOrBlank()) {
                sid = fetchSidFromInit()
            }

            // 2. sid'ye göre 720p ve 480p URL'lerini oluştur
            val qualityUrls = mutableListOf<Pair<Int, String>>()
            
            if (!sid.isNullOrBlank()) {
                qualityUrls.add(720 to "$daionBaseUrl/startv_720p.m3u8?&sid=$sid&app=$starTvAppId&ce=3")
                qualityUrls.add(480 to "$daionBaseUrl/startv_480p.m3u8?&sid=$sid&app=$starTvAppId&ce=3")
            }
            
            // 3. sid alınamazsa fallback: sid'siz 720p dene
            if (qualityUrls.isEmpty()) {
                qualityUrls.add(720 to "$daionBaseUrl/startv_720p.m3u8?&app=$starTvAppId&ce=3")
                qualityUrls.add(480 to "$daionBaseUrl/startv_480p.m3u8?&app=$starTvAppId&ce=3")
            }

            // 4. Her kalite için link callback'i yap
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

    /**
     * Sayfa HTML'inden sid değerini alır.
     * Sayfada "sid=xxx" veya "sid":"xxx" formatında olabilir.
     */
    private suspend fun fetchSidFromPage(pageUrl: String): String? {
        return try {
            val doc = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent)).document
            val html = doc.html()
            
            // Direkt sayfada ara
            sidRegex.find(html)?.groupValues?.get(1)?.let { return it }

            // Iframe içinde ara
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

    /**
     * Daion CDN'in /options/init endpoint'inden sid alır.
     * Bu endpoint GET ile sid döner (startvcanli.txt loglarına göre).
     */
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

            // JSON response ise parse et
            if (response.trim().startsWith("{")) {
                runCatching {
                    val json = JSONObject(response)
                    json.optString("sid").takeIf { it.isNotBlank() }?.let { return it }
                    json.optString("sessionId").takeIf { it.isNotBlank() }?.let { return it }
                    json.optJSONObject("data")?.optString("sid")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }

            // Regex ile ara
            sidRegex.find(response)?.groupValues?.get(1)?.let { return it }

            // Bazı durumlarda direkt string döner
            if (response.length in 8..32 && response.matches(Regex("[a-z0-9]+"))) {
                return response.trim()
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    // DİZİ BÖLÜMÜ
    // ============================================================
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            var streamUrl: String? = null

            // 1. Daion CDN'den m3u8 ara
            streamUrl = extractDaionFromScripts(doc)

            // 2. Iframe varsa içeriğini çek
            if (streamUrl.isNullOrBlank()) {
                val iframeSrc = doc.select(
                    "iframe[src*=player], iframe[src*=video], iframe[src*=embed], iframe[src*=daion]"
                ).firstOrNull()?.attr("src")

                if (!iframeSrc.isNullOrBlank()) {
                    val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )).document
                    streamUrl = extractDaionFromScripts(iframeDoc) ?: findStreamUrl(iframeDoc)
                }
            }

            // 3. Genel arama
            if (streamUrl.isNullOrBlank()) {
                streamUrl = findStreamUrl(doc)
            }

            if (!streamUrl.isNullOrBlank()) {
                // Sayfa URL'sini stream olarak göndermeyi engelle!
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
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to mainUrl
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Script'lerden Daion CDN m3u8 URL'sini bulur.
     */
    private fun extractDaionFromScripts(doc: Document): String? {
        val allScripts = buildString {
            doc.select("script").forEach { script ->
                append(script.html())
                append("\n")
            }
        }

        // 1. 720p öncelikli
        daionM3u8Regex.findAll(allScripts).map { it.value }
            .filter { !it.contains(".ts") && it.contains("720p") }
            .firstOrNull()?.let { return it }

        // 2. Herhangi bir daion m3u8
        daionM3u8Regex.findAll(allScripts).map { it.value }
            .filter { !it.contains(".ts") }
            .firstOrNull()?.let { return it }

        // 3. Master playlist
        Regex("""(https?://dogus\.daioncdn\.net/startv/startv\.m3u8[^\s"'<>]*)""")
            .find(allScripts)?.value?.let { return it }

        return null
    }

    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()

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
