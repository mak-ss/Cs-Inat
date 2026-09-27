package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * videoplays.cfd için özel extractor.
 *
 * Bu sağlayıcı "Playback domain is not allowed" hatası veriyor çünkü
 * sadece belirli alan adlarından (dizipal1432.com) gelen isteklere izin veriyor.
 * Bu extractor, doğru Referer ve Origin başlıklarını göndererek bu kontrolü aşar
 * ve embed sayfası içindeki m3u8/mp4 linkini çıkarır.
 */
class VideoplaysCfd : ExtractorApi() {
    override var name = "VideoplaysCfd"
    override var mainUrl = "https://videoplays.cfd"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedReferer = referer ?: "https://dizipal1432.com/"
        val origin = "https://dizipal1432.com"

        val response = try {
            app.get(
                url,
                referer = fixedReferer,
                headers = mapOf(
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            ).text
        } catch (e: Exception) {
            Log.d("VPCFD", "İstek hatası » ${e.message}")
            return
        }

        Log.d("VPCFD", "response length » ${response.length}")

        // 1. Doğrudan m3u8/mp4 araması
        val directUrl = Regex("""(https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""")
            .find(response)?.groupValues?.get(1)

        if (!directUrl.isNullOrBlank()) {
            Log.d("VPCFD", "Doğrudan video URL bulundu » $directUrl")
            sendLink(directUrl, url, callback)
            return
        }

        // 2. JavaScript içinde gizlenmiş kaynak araması (jwplayer/plyr setup)
        val jsPatterns = listOf(
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""source\s*:\s*["']([^"']+)["']"""),
            Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
            Regex("""(https?:\\?/\\?/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""")
        )

        for (pattern in jsPatterns) {
            val match = pattern.find(response)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) {
                val cleaned = match
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\", "")

                if (cleaned.contains(".m3u8") || cleaned.contains(".mp4")) {
                    Log.d("VPCFD", "JS içinden video URL bulundu » $cleaned")
                    sendLink(cleaned, url, callback)
                    return
                }
            }
        }

        // 3. P.A.C.K.E.R. ile obfuscate edilmiş JS'i unpack et
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[rd]\)[\s\S]*?</script>""")
        val packedMatch = packedRegex.find(response)
        if (packedMatch != null) {
            Log.d("VPCFD", "Packed JS bulundu, unpack ediliyor...")
            val unpacked = JsUnpacker(packedMatch.value).unpack()
            if (unpacked != null) {
                val unpackedUrl = Regex("""(https?://[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*)""")
                    .find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""file\s*:\s*["']([^"']+)["']""")
                        .find(unpacked)?.groupValues?.get(1)

                if (!unpackedUrl.isNullOrBlank()) {
                    val cleaned = unpackedUrl
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("\\", "")

                    Log.d("VPCFD", "Unpack sonrası video URL bulundu » $cleaned")
                    sendLink(cleaned, url, callback)
                    return
                }
            }
        }

        // 4. Token hatası varsa log'a yaz
        if (response.contains("Playback domain is not allowed")) {
            Log.d("VPCFD", "Token hatası: Playback domain is not allowed")
        }
    }

    private suspend fun sendLink(
        videoUrl: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
