package com.UmayTrade

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document

/**
 * Star TV için özel extractor.
 * Hem canlı yayın hem de dizi bölümleri için m3u8 linklerini çıkarır.
 */
class StarTvExtractor : ExtractorApi() {
    override val name = "Star TV"
    override val mainUrl = "https://www.startv.com.tr"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Daion CDN domaini - Star TV ve diğer Doğuş yayınları burayı kullanıyor
    private val daionCdnRegex = Regex("""(https?://[^\s"'<>]*?daioncdn\.net[^\s"'<>]*?\.m3u8[^\s"'<>]*)""")

    // Genel m3u8 yakalayıcı
    private val m3u8Regex = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""")

    // MP4 yakalayıcı
    private val mp4Regex = Regex("""(https?://[^\s"'<>]+?\.mp4[^\s"'<>]*)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
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
     * Canlı yayın linkini çıkarır.
     */
    private suspend fun extractLiveStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            var streamUrl = findStreamUrl(doc)

            // Iframe kontrolü
            if (streamUrl.isNullOrBlank()) {
                val iframeSrc = doc.selectFirst("iframe[src*=player], iframe[src*=canli], iframe[src*=live]")?.attr("src")
                if (!iframeSrc.isNullOrBlank()) {
                    val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )).document
                    streamUrl = findStreamUrl(iframeDoc)
                }
            }

            // Fallback: Bilinen canlı yayın endpoint'i
            if (streamUrl.isNullOrBlank()) {
                streamUrl = "https://dogus-live.daioncdn.net/startv/startv.m3u8"
            }

            if (!streamUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Star TV Canlı HD",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = this@StarTvExtractor.mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to this@StarTvExtractor.mainUrl
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
     * Dizi bölümü stream URL'sini çıkarır.
     */
    private suspend fun extractEpisodeStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

            var streamUrl = findStreamUrl(doc)

            // Iframe kontrolü
            if (streamUrl.isNullOrBlank()) {
                val iframeSrc = doc.selectFirst("iframe[src*=player], iframe[src*=video], iframe[src*=embed]")?.attr("src")
                if (!iframeSrc.isNullOrBlank()) {
                    val iframeDoc = app.get(fixUrl(iframeSrc), headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )).document
                    streamUrl = findStreamUrl(iframeDoc)
                }
            }

            // JSON-LD'den contentUrl veya embedUrl
            if (streamUrl.isNullOrBlank()) {
                val jsonLd = doc.selectFirst("script[type=application/ld+json]")?.html()
                if (!jsonLd.isNullOrBlank()) {
                    streamUrl = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
                        ?: Regex(""""embedUrl"\s*:\s*"([^"]+)"""").find(jsonLd)?.groupValues?.get(1)
                }
            }

            if (!streamUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "Star TV",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.MP4
                    ) {
                        this.referer = this@StarTvExtractor.mainUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Origin" to this@StarTvExtractor.mainUrl
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
     * Sayfa içeriğinden stream URL'sini bulur.
     * Öncelik: daioncdn -> genel m3u8 -> mp4
     */
    private fun findStreamUrl(doc: Document): String? {
        val html = doc.html()
        daionCdnRegex.find(html)?.value?.let { return it }
        m3u8Regex.find(html)?.value?.let { return it }
        mp4Regex.find(html)?.value?.let { return it }
        return null
    }

    /**
     * Göreceli URL'leri mutlak hale getirir.
     */
    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("/")) {
            "${this.mainUrl}$url"
        } else {
            "${this.mainUrl}/$url"
        }
    }
}
