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

        val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
        val match = m3u8Regex.find(response)?.groupValues?.get(1)

        if (!match.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = match,
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
            )
        }
    }
}
