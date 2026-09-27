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
 * Player, Media Chrome kütüphanesini kullanıyor (standart HTML5 <video> elementini sarmalar).
 * Asıl video kaynağı bir token mekanizmasıyla korunuyor:
 *
 *   1. GET https://videoplays.cfd/api/videos/{videoId}/token?embed_referrer={referrer}
 *   2. Yanıtta "token" ve/veya "playlist_url" alanları dönüyor
 *   3. Playlist URL'si: https://videoplays.cfd/api/videos/{videoId}/master.m3u8?token={token}
 *
 * Sunucu, embed_referrer'in izin verilen bir domain (dizipal1432.com) olduğunu doğruluyor.
 * Doğru başlıklar (Referer, Origin) gönderilmezse "Playback domain is not allowed" hatası dönüyor.
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
            // Video ID yoksa eski yöntemle dene
            tryOldMethod(url, fixedReferer, origin, callback)
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
            tryOldMethod(url, fixedReferer, origin, callback)
            return
        }

        Log.d("VPCFD", "tokenResponse » $tokenResponse")

        // 3. Yanıtı parse et
        var playlistUrl: String? = null
        var token: String? = null

        try {
            val json = JSONObject(tokenResponse)
            // Farklı olası alan adlarını dene
            playlistUrl = listOf("playlist_url", "playlist", "url", "hls_url")
                .firstNotNullOfOrNull { key -> json.optString(key, "").takeIf { it.isNotBlank() } }
            token = listOf("token", "access_token", "playback_token")
                .firstNotNullOfOrNull { key -> json.optString(key, "").takeIf { it.isNotBlank() } }
        } catch (e: Exception) {
            Log.d("VPCFD", "JSON parse hatası » ${e.message}")
        }

        // 4. Playlist URL'sini oluştur
        val finalUrl = playlistUrl
            ?: token?.let { "$mainUrl/api/videos/$videoId/master.m3u8?token=$it" }

        if (finalUrl.isNullOrBlank()) {
            Log.d("VPCFD", "Playlist URL oluşturulamadı, eski yöntem deneniyor")
            tryOldMethod(url, fixedReferer, origin, callback)
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
     * Yedek yöntem: Embed sayfasını çekip içindeki video URL'sini regex ile bul.
     * Media Chrome kullanıldığı için <video src="..."> veya source elementleri olabilir.
     */
    private suspend fun tryOldMethod(
        url: String,
        fixedReferer: String,
        origin: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url,
                referer = fixedReferer,
                headers = mapOf(
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            ).text

            Log.d("VPCFD", "Yedek yöntem: response length » ${response.length}")

            // <video src="..."> veya <source src="..."> ara
            val patterns = listOf(
                Regex("""<video[^>]+src=["']([^"']+)["']"""),
                Regex("""<source[^>]+src=["']([^"']+)["']"""),
                Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)"""),
                Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)"""),
                Regex("""file\s*:\s*["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                val match = pattern.find(response)?.groupValues?.get(1)
                if (!match.isNullOrBlank()) {
                    val cleaned = match
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                        .replace("\\", "")

                    if (cleaned.startsWith("http")) {
                        Log.d("VPCFD", "Yedek yöntem: video URL bulundu » $cleaned")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = cleaned,
                                type = if (cleaned.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("VPCFD", "Yedek yöntem hatası » ${e.message}")
        }
    }

    /**
     * Embed URL'den video ID'sini çıkarır.
     * Örnek: https://videoplays.cfd/player.html?video=2440 → "2440"
     */
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""[?&]video=(\d+)"""),
            Regex("""/videos?/(\d+)"""),
            Regex("""/embed/(\d+)"""),
            Regex("""/player/(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }
}
