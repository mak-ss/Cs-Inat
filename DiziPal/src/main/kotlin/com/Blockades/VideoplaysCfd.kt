package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject

/**
 * videoplays.cfd için özel extractor.
 *
 * Bu sağlayıcı, video oynatmak için bir token mekanizması kullanıyor.
 * Token, şu API çağrısıyla alınıyor:
 *   GET https://videoplays.cfd/api/videos/{videoId}/token?embed_referrer={referrer}
 *
 * Sunucu, embed_referrer'in izin verilen bir domain (dizipal1432.com) olduğunu
 * doğruluyor. Doğru başlıklar (Referer, Origin) gönderilmezse
 * "Playback domain is not allowed" hatası dönüyor.
 *
 * Token alındıktan sonra playlist URL'si şu formatta oluyor:
 *   https://videoplays.cfd/api/videos/{videoId}/master.m3u8?token={token}
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
        // Referer'ı Dizipal ana sitesi olarak ayarla
        val fixedReferer = referer
            ?.takeIf { it.isNotBlank() && it.contains("dizipal") }
            ?: "https://dizipal1432.com/"
        val origin = "https://dizipal1432.com"

        // 1. Video ID'sini embed URL'den çıkar
        val videoId = extractVideoId(url)
        if (videoId.isNullOrBlank()) {
            Log.d("VPCFD", "Video ID bulunamadı: $url")
            return
        }
        Log.d("VPCFD", "videoId » $videoId")

        // 2. Token API'sini çağır
        val tokenUrl = "$mainUrl/api/videos/$videoId/token?embed_referrer=${fixedReferer}"
        Log.d("VPCFD", "tokenUrl » $tokenUrl")

        val tokenResponse = try {
            app.get(
                tokenUrl,
                referer = fixedReferer,
                headers = mapOf(
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            ).text
        } catch (e: Exception) {
            Log.d("VPCFD", "Token isteği hatası » ${e.message}")
            return
        }

        Log.d("VPCFD", "tokenResponse » $tokenResponse")

        // 3. Yanıtı parse et
        var playlistUrl: String? = null
        var token: String? = null

        try {
            val json = JSONObject(tokenResponse)
            playlistUrl = json.optString("playlist_url", "").takeIf { it.isNotBlank() }
            token = json.optString("token", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d("VPCFD", "JSON parse hatası » ${e.message}")
        }

        // 4. Playlist URL'sini oluştur
        val finalUrl = playlistUrl
            ?: token?.let { "$mainUrl/api/videos/$videoId/master.m3u8?token=$it" }

        if (finalUrl.isNullOrBlank()) {
            Log.d("VPCFD", "Playlist URL oluşturulamadı")
            return
        }

        Log.d("VPCFD", "finalUrl » $finalUrl")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = fixedReferer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to origin,
                    "Referer" to fixedReferer,
                    "User-Agent" to USER_AGENT
                )
            }
        )
    }

    /**
     * Embed URL'den video ID'sini çıkarır.
     * Örnek: https://videoplays.cfd/player.html?video=2440 → "2440"
     */
    private fun extractVideoId(url: String): String? {
        // ?video=2440 veya /player.html?video=2440 formatı
        val patterns = listOf(
            Regex("""[?&]video=(\d+)"""),
            Regex("""/videos?/(\d+)"""),
            Regex("""/embed/(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }
}
