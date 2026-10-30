package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

class SuperFilmGeldiMixTiger : PilavyerTaban() {
    override val name = "MixTiger"
    override val mainUrl = "https://mixtiger.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        coz(url, referer ?: "$mainUrl/", subtitleCallback, callback)
    }
}
