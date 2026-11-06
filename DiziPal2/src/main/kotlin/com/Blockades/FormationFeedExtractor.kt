package com.Blockades

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class FormationFeedExtractor : ExtractorApi() {
    override var name = "FormationFeed"
    override var mainUrl = "https://formationfeed.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = try {
            app.get(
                url,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
            ).text
        } catch (e: Exception) {
            return
        }

        val normalized = response.replace("\\/", "/").replace("&amp;", "&")

        // 1. Önce HTML'de doğrudan m3u8 olup olmadığını dene (orijinal yöntem)
        var m3u8Url = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
            .find(normalized)?.groupValues?.get(1)

        // 2. Bulunamazsa, <script> içinden oynatıcı yapılandırmasını çıkarmayı dene
        // JW Player'ın tipik yapılandırma biçimi: sources: [{ file: "..." }]
        if (m3u8Url.isNullOrBlank()) {
            val scriptContents = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(normalized)
                .map { it.groupValues[1] }
                .joinToString("\n")

            // file: "..." veya file: '...' biçimini eşleştir, değerin m3u8 içermesi gerekir
            m3u8Url = Regex("""file\s*:\s*["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(scriptContents)?.groupValues?.get(1)
                ?.replace("\\/", "/")
        }

        if (!m3u8Url.isNullOrBlank()) {
            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to mainUrl,
                    "Referer" to url
                )
            }
            callback.invoke(link)
        } else {
            // Anahtar: m3u8 bulunamadığında günlüğe kaydet, sayfa yapısını analiz etmek için
            // Logcat'te "FormationFeed: m3u8 bulunamadı, HTML uzunluğu: xxx" arayın
            // Ardından response.take(3000) içeriğini çıkararak script içinde başka ipucu olup olmadığına bakın
        }
    }
}
