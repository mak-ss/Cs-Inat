package com.UmayTrade

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

class StarTvExtractor {

    private val extractorName = "Star TV"
    private val mainUrl = "https://www.startv.com.tr"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // SADECE gerçek m3u8 linklerini yakala (sayfa URL'si değil!)
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")
    private val mp4Regex = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")

    // Sayfa içindeki JSON'da video objesini bul
    private val videoJsonRegex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
    private val filenameRegex = Regex(""""filename"\s*:\s*"([^"]+)"""")

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

    /**
     * Canlı yayın - Star TV canlı yayını iframe içinde geliyor.
     */
    private suspend fun extractLiveStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            // 1. Sayfadaki iframe'leri kontrol et (DÜZELTİLDİ)
            val iframeSrc = doc.select(
                "iframe[src*=player], iframe[src*=canli], iframe[src*=live], iframe[src*=daion], iframe[src*=dogus]"
            ).firstOrNull()?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val fullIframeUrl = fixUrl(iframeSrc)

                val iframeDoc = app.get(fullIframeUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to url
                )).document

                var streamUrl = findStreamUrl(iframeDoc)

                // Iframe içinde başka bir iframe varsa
                if (streamUrl.isNullOrBlank()) {
                    val innerIframe = iframeDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!innerIframe.isNullOrBlank()) {
                        val innerDoc = app.get(fixUrl(innerIframe), headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to fullIframeUrl
                        )).document
                        streamUrl = findStreamUrl(innerDoc)
                    }
                }

                // Iframe içindeki script'lerde m3u8 ara
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = extractFromScripts(iframeDoc)
                }

                if (!streamUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = extractorName,
                            name = "Star TV Canlı HD",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Origin" to mainUrl
                            )
                            this.quality = Qualities.P720.value
                        }
                    )
                    return true
                }
            }

            // 2. Doğrudan sayfa içeriğinde m3u8/mp4 ara
            val directStreamUrl = findStreamUrl(doc)
            if (!directStreamUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = extractorName,
                        name = "Star TV Canlı HD",
                        url = directStreamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to mainUrl
                        )
                        this.quality = Qualities.P720.value
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
     * Dizi bölümü - Sayfadaki JSON'dan video bilgisini bulup stream URL'sini çıkarır.
     */
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            var streamUrl: String? = null

            // 1. Sayfadaki script'lerde video URL'si ara
            streamUrl = extractFromScripts(doc)

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
                    streamUrl = extractFromScripts(iframeDoc) ?: findStreamUrl(iframeDoc)
                }
            }

            // 3. Son çare: sayfadaki tüm linklerde m3u8 ara
            if (streamUrl.isNullOrBlank()) {
                streamUrl = findStreamUrl(doc)
            }

            if (!streamUrl.isNullOrBlank()) {
                // KRİTİK: Sayfa URL'sini stream olarak göndermeyi engelle!
                if (streamUrl.contains("startv.com.tr/dizi/")) {
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
     * Sayfadaki tüm script tag'lerini tarayıp video URL'sini bulur.
     */
    private fun extractFromScripts(doc: Document): String? {
        val allScripts = buildString {
            doc.select("script").forEach { script ->
                append(script.html())
                append("\n")
            }
        }

        // 1. contentUrl
        videoJsonRegex.find(allScripts)?.groupValues?.get(1)?.let { url ->
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                return url
            }
        }

        // 2. filename
        filenameRegex.find(allScripts)?.groupValues?.get(1)?.let { filename ->
            if (filename.contains(".mp4") && filename.startsWith("http")) {
                return filename
            }
        }

        // 3. Genel m3u8 araması
        val m3u8Matches = m3u8Regex.findAll(allScripts).map { it.value }
            .filter { it.contains("daioncdn") || it.contains("dogus") || it.contains("startv") }
            .toList()
        if (m3u8Matches.isNotEmpty()) {
            return m3u8Matches.first()
        }

        // 4. Genel mp4 araması
        val mp4Matches = mp4Regex.findAll(allScripts).map { it.value }
            .filter { !it.contains("startv.com.tr/dizi") }
            .toList()
        if (mp4Matches.isNotEmpty()) {
            return mp4Matches.first()
        }

        return null
    }

    /**
     * Sayfa HTML'inde stream URL'si arar (script dışı).
     */
    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()

        // Daion CDN öncelikli
        Regex("""(https?://[^\s"'<>]*?daioncdn\.net[^\s"'<>]*?\.m3u8[^\s"'<>]*)""")
            .find(html)?.value?.let { return it }

        // Genel m3u8 (sayfa URL'si hariç)
        m3u8Regex.find(html)?.value?.let { url ->
            if (!url.contains("startv.com.tr/dizi")) {
                return url
            }
        }

        // MP4 (sayfa URL'si hariç)
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
