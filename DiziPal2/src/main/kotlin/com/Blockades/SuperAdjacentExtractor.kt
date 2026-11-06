package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SuperAdjacentExtractor : ExtractorApi() {
    override val name = "SuperAdjacent"
    override val mainUrl = "https://superadjacentsoddenly.xyz"
    override val requiresReferer = true

    // Referer/Origin olarak her zaman Dizipal ana alan adı kullanılır
    private val DIZIPAL_REFERER = "https://dizipal2147.com/"
    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = DIZIPAL_REFERER

        try {
            // 1) URL zaten m3u8 ise doğrudan gönder
            if (url.contains(".m3u8", ignoreCase = true)) {
                emitLink(callback, url, pageReferer)
                return
            }

            // 2) Embed sayfasını çek, içindeki m3u8'i çıkar
            val pageText = app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Origin" to pageReferer,
                    "Referer" to pageReferer
                )
            ).text

            val normalized = pageText.replace("\\/", "/").replace("&amp;", "&")

            val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+?\.m3u8(?:\?[^\s"'\\<>]*)?)""")
            val matches = m3u8Regex.findAll(normalized).map { it.groupValues[1] }.toList()

            if (matches.isEmpty()) {
                Log.w("SuperAdjacent", "Embed sayfasında m3u8 bulunamadı: $url")
                return
            }

            // Öncelik: imzasız index-*.m3u8 > imzasız diğer > imzalı master.m3u8
            val preferred = matches.sortedByDescending { link ->
                when {
                    !link.contains("?") && link.contains("index") -> 2
                    !link.contains("?") -> 1
                    else -> 0
                }
            }.first()

            emitLink(callback, preferred, pageReferer)

        } catch (e: Exception) {
            Log.e("SuperAdjacent", "Hata: ${e.message}", e)
        }
    }

    // suspend olmalı çünkü newExtractorLink suspend
    private suspend fun emitLink(
        callback: (ExtractorLink) -> Unit,
        m3u8Url: String,
        referer: String
    ) {
        val link = newExtractorLink(
            source = this.name,
            name = this.name,
            url = m3u8Url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Origin" to referer,
                "Referer" to referer
            )
        }
        callback.invoke(link)
        Log.d("SuperAdjacent", "Link gönderildi: $m3u8Url | Referer: $referer")
    }
}
