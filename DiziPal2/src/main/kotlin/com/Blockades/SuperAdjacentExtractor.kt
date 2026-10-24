package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SuperAdjacentExtractor : ExtractorApi() {
    override val name = "SuperAdjacent"
    override val mainUrl = "https://superadjacentsoddenly.xyz"
    override val requiresReferer = true

    // Tarayıcı kimliği (User-Agent)
    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Dizipal sayfasını Referer olarak ayarla (hotlink korumasını aşmak için kritik)
        val pageReferer = referer ?: "https://dizipal2143.com/"

        try {
            // Eğer link zaten bir m3u8 ise, doğrudan başlıklarla birlikte gönder
            if (url.contains(".m3u8", ignoreCase = true)) {
                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    // Bu başlıklar 403 hatasını çözer
                    this.referer = pageReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Origin" to "https://dizipal2143.com", // Sunucunun beklediği Origin
                        "Referer" to pageReferer
                    )
                }
                callback.invoke(link)
                Log.d("SuperAdjacent", "Link başarıyla gönderildi: $url")
                return
            }

            // Eğer link bir embed sayfası ise (m3u8 değilse), sayfayı çekip içinden m3u8 ara
            val pageText = app.get(
                url,
                referer = pageReferer,
                headers = mapOf("User-Agent" to UA, "Origin" to "https://dizipal2143.com")
            ).text

            val normalized = pageText.replace("\\/", "/").replace("&amp;", "&")
            val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
            val m3u8 = m3u8Regex.find(normalized)?.groupValues?.getOrNull(1)

            if (m3u8 != null) {
                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Origin" to "https://dizipal2143.com",
                        "Referer" to pageReferer
                    )
                }
                callback.invoke(link)
            } else {
                Log.w("SuperAdjacent", "Embed sayfasında m3u8 bulunamadı: $url")
            }

        } catch (e: Exception) {
            Log.e("SuperAdjacent", "Hata oluştu: ${e.message}", e)
        }
    }
}
