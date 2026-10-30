package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

/** MixPlayHD embed'leri (Pilavyer türevi). */
class SuperFilmGeldiMixPlayHd : PilavyerTaban() {
    override val name = "MixPlayHD"
    override val mainUrl = "https://mixplayhd.com"   // gerçek alan adıyla değiştirin
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
