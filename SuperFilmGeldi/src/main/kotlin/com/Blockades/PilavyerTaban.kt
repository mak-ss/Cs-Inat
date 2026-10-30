package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pilavyer/benzeri embed'lerde ortak akış:
 *  1. embed sayfasını çek
 *  2. window.__PLAYER__ = {...} JSON'unu ayıkla
 *  3. stream (m3u8) + subs + audios
 *  4. cdn=0 yedek bağlantısını da ver
 */
abstract class PilavyerTaban : ExtractorApi() {

    override val requiresReferer = true

    /** Embed URL'yi verilen referer ile çözer. */
    protected suspend fun coz(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val govde = app.get(url, headers = mapOf("Referer" to referer)).text
        val json = Regex(
            """window\.__PLAYER__\s*=\s*(\{.*?\});\s*</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(govde)?.groupValues?.get(1) ?: return false

        val ayar = JSONObject(json)
        val akis = ayar.optString("stream").ifBlank { return false }
        val taban = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: ""

        val altyazilar = ayar.optJSONArray("subs") ?: JSONArray()
        for (i in 0 until altyazilar.length()) {
            val a = altyazilar.optJSONObject(i) ?: continue
            val src = a.optString("src").ifBlank { null } ?: continue
            subtitleCallback.invoke(
                newSubtitleFile(
                    a.optString("label").ifBlank { a.optString("lang") },
                    src
                )
            )
        }

        val sesler = ayar.optJSONArray("audios")
        val sesAdi = if (sesler != null && sesler.length() > 1) " (Dublaj + Orijinal)"
        else sesler?.optJSONObject(0)?.optString("label")?.ifBlank { null }?.let { " ($it)" } ?: ""

        callback.invoke(
            newExtractorLink(name, "$name$sesAdi", akis, ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to taban)
            }
        )

        if (!akis.contains("cdn=0")) {
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name Yedek$sesAdi",
                    akis + (if (akis.contains("?")) "&" else "?") + "cdn=0",
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to taban)
                }
            )
        }
        return true
    }
}
