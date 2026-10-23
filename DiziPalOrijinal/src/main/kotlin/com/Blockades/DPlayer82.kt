package com.Blockades.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities

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

        // TODO: Tarayıcıda F12 ile sayfa kaynağını incele, m3u8'in gerçek konumunu bul
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        val m3u8Url = m3u8Regex.find(response)?.groupValues?.get(1)

        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = realReferer,
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE,
                    headers = mapOf("Referer" to realReferer)
                )
            )
        }
    }
}
