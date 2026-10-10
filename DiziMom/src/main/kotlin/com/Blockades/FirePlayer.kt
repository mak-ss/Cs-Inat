package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class FirePlayer : ExtractorApi() {
    override var name = "FirePlayer"
    override var mainUrl = "https://fireplayer.net"
    override val requiresReferer = true

    companion object {
        fun isFirePlayer(url: String): Boolean {
            return url.contains("fireplayer", ignoreCase = true) ||
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
        // Extract the actual video source from the FirePlayer page
        // This depends on the player's structure. Common approach:
        val script = document.selectFirst("script:containsData(sources)")?.data()
            ?: document.selectFirst("script:containsData(file)")?.data()
            ?: return

        val regex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
        regex.findAll(script).forEach { match ->
            val videoUrl = match.groupValues[1]
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) {
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                    } else {
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                    }
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer ?: ""
                }
            )
        }
    }
}
