package com.Blockades.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DPlayer82 : ExtractorApi() {
    override var name = "DPlayer82"
    override var mainUrl = "https://four.dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: "https://dizipal1591.com/"

        val response = app.get(
            url,
            referer = realReferer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).text

        // TODO: m3u8 linkinin gerçek konumunu tarayıcıda F12 ile doğrula
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val m3u8Url = m3u8Regex.find(response)?.groupValues?.get(1)

        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8 // m3u8 olduğu için M3U8 tipi daha doğru
                ) {
                    this.referer = realReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Referer" to realReferer)
                }
            )
        }
    }
}
