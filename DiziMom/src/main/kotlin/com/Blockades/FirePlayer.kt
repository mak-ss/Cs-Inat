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
                   url.contains("fireplay", ignoreCase = true) ||
                   url.contains("fplayer", ignoreCase = true)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // 1) iframe içindeki gerçek player linkini bul
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        val targetUrl = if (!iframeSrc.isNullOrBlank()) {
            if (iframeSrc.startsWith("//")) "https:$iframeSrc"
            else if (iframeSrc.startsWith("/")) {
                val base = url.substringBefore("/", "").let { "" } // mainUrl kullan
                "$mainUrl$iframeSrc"
            } else iframeSrc
        } else url

        val pageDoc = if (targetUrl != url) {
            app.get(targetUrl, referer = referer).document
        } else document

        // 2) Script içinden m3u8 / mp4 linklerini çek
        val scripts = pageDoc.select("script").mapNotNull { it.data() }.joinToString("\n")

        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""source\s*:\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+)["']""")
        )

        val found = mutableSetOf<String>()
        patterns.forEach { regex ->
            regex.findAll(scripts).forEach { match ->
                found.add(match.groupValues[1])
            }
        }

        found.forEach { videoUrl ->
            val fixedUrl = when {
                videoUrl.startsWith("//") -> "https:$videoUrl"
                else -> videoUrl
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixedUrl,
                    type = if (fixedUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer ?: ""
                }
            )
        }
    }
}
