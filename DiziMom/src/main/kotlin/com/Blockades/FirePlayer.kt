package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class FirePlayer : ExtractorApi() {
    override var name = "FirePlayer"
    override var mainUrl = "https://fireplayer.net"
    override val requiresReferer = true

    companion object {
        fun isFirePlayer(url: String): Boolean {
            return url.contains("fireplayer", ignoreCase = true) ||
                   url.contains("fplayer", ignoreCase = true) ||
                   url.contains("fireplay", ignoreCase = true)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // FirePlayer iframe içinde "data-id" veya "data-hash" taşır
        // Genelde /player/index.php?data=XXX&do=getVideo POST isteği ile video linki döner
        val dataId = document.selectFirst("[data-id]")?.attr("data-id")
            ?: document.selectFirst("[data-hash]")?.attr("data-hash")
            ?: url.substringAfter("data=", "").substringBefore("&").ifBlank { null }

        if (dataId != null) {
            val apiUrl = "$mainUrl/player/index.php?data=$dataId&do=getVideo"
            val response = app.post(
                apiUrl,
                headers = mapOf(
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
                )
            ).text

            // JSON içinden videoUrl / securedLink al
            val videoRegex = Regex(""""videoUrl"\s*:\s*"([^"]+)"""")
            val securedRegex = Regex(""""securedLink"\s*:\s*"([^"]+)"""")

            val videoUrl = videoRegex.find(response)?.groupValues?.get(1)
                ?: securedRegex.find(response)?.groupValues?.get(1)

            if (!videoUrl.isNullOrBlank()) {
                val fixedUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fixedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer ?: ""
                    }
                )
                return
            }
        }

        // Fallback: script içinden m3u8 ara
        val scripts = document.select("script").mapNotNull { it.data() }.joinToString("\n")
        val regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        regex.findAll(scripts).forEach { match ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer ?: ""
                }
            )
        }
    }
}
