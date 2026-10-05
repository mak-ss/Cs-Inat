package com.Blockades

import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * SprintCDN / owphbf24.com HLS extractor
 * Ornek:
 *   https://edge1-frankfurt-sprintcdn.owphbf24.com/hls2/08/12166/4r9s807sm6w6_x/index-v1-a1.m3u8?t=...&s=...&e=...
 *
 * Bu servis genelde query string icindeki token ile dogrudan m3u8 dondurur.
 * Ek decrypt gerekmiyorsa link oldugu gibi kullanilir.
 */
class SprintCDN : ExtractorApi() {
    override val name = "SprintCDN"
    override val mainUrl = "https://owphbf24.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val masterUrl = url
        val ref = referer ?: mainUrl

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:155.0) Gecko/20100101 Firefox/155.0",
            "Referer" to ref,
            "Origin" to "https://animeyt.cc",
            "Accept" to "*/*"
        )

        // Once master playlisti cek
        val response = try {
            app.get(masterUrl, headers = headers, referer = ref)
        } catch (e: Exception) {
            return
        }

        val body = response.text
        val contentType = response.headers["Content-Type"] ?: ""

        // Master playlist mi yoksa direkt segment mi?
        val isMasterPlaylist = body.trimStart().startsWith("#EXTM3U")

        if (!isMasterPlaylist) {
            // m3u8 degil; yine de link olarak ver
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ref
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return
        }

        // Master playlist icindeki variantlari ayikla
        val baseUrl = masterUrl.substringBeforeLast("/") + "/"
        val lines = body.lines()
        var pendingName: String? = null

        lines.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // RESOLUTION=1920x1080 veya NAME="1080p" gibi bilgileri al
                val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(line)
                val nameMatch = Regex("""NAME="([^"]+)"""").find(line)
                pendingName = nameMatch?.groupValues?.get(1)
                    ?: resMatch?.let { "${it.groupValues[2]}p" }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val variantUrl = if (line.startsWith("http")) line
                else baseUrl + line

                val quality = parseQuality(pendingName)

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = variantUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = ref
                        this.quality = quality
                        this.headers = headers
                    }
                )
                pendingName = null
            }
        }

        // Eger hicbir varyant bulunamadiysa master'i direkt ver
        if (lines.none { it.isNotBlank() && !it.startsWith("#EXTM3U") && !it.startsWith("#") }) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ref
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }

    private fun parseQuality(label: String?): Int {
        if (label.isNullOrBlank()) return Qualities.Unknown.value
        val digits = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return when {
            digits >= 2160 -> Qualities.P2160.value
            digits >= 1440 -> Qualities.P1440.value
            digits >= 1080 -> Qualities.P1080.value
            digits >= 720 -> Qualities.P720.value
            digits >= 480 -> Qualities.P480.value
            digits >= 360 -> Qualities.P360.value
            digits >= 240 -> Qualities.P240.value
            else -> digits
        }
    }
}
