package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * SprintCDN / owphbf24.com HLS Extractor
 *
 * Ornek:
 *   https://edge1-frankfurt-sprintcdn.owphbf24.com/hls2/08/12166/4r9s807sm6w6_x/index-v1-a1.m3u8?t=...&s=...&e=...
 */
class SprintCDN : ExtractorApi() {
    override val name = "SprintCDN"
    override val mainUrl = "https://owphbf24.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: mainUrl
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0",
            "Referer" to ref,
            "Origin" to "https://animeyt.cc",
            "Accept" to "*/*"
        )

        val response = try {
            app.get(url, headers = headers, referer = ref)
        } catch (e: Exception) {
            return
        }

        val body = response.text
        val isMaster = body.trimStart().startsWith("#EXTM3U") &&
                body.contains("#EXT-X-STREAM-INF")

        if (!isMaster) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ref
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return
        }

        val baseUrl = url.substringBeforeLast("/") + "/"
        val lines = body.lines()
        var pendingName: String? = null

        lines.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)
                val nm = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                pendingName = nm ?: res?.let { "${it}p" }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val variant = if (line.startsWith("http")) line else baseUrl + line
                val q = parseQuality(pendingName)
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = variant,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = q
                        this.headers = headers
                    }
                )
                pendingName = null
            }
        }
    }

    private fun parseQuality(label: String?): Int {
        val d = label?.let { Regex("""(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return Qualities.Unknown.value
        return when {
            d >= 2160 -> Qualities.P2160.value
            d >= 1440 -> Qualities.P1440.value
            d >= 1080 -> Qualities.P1080.value
            d >= 720 -> Qualities.P720.value
            d >= 480 -> Qualities.P480.value
            d >= 360 -> Qualities.P360.value
            d >= 240 -> Qualities.P240.value
            else -> d
        }
    }
}
