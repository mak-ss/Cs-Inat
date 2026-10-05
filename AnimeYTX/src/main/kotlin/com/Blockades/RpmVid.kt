package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * RpmVid / YTPlay HLS Extractor
 *
 * Ornek:
 *   https://ytplay.rpmvid.com/hlsmod/.../index-f1-v1-a1.m3u8?v=...
 */
class RpmVid : ExtractorApi() {
    override val name = "RpmVid"
    override val mainUrl = "https://ytplay.rpmvid.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) ->
