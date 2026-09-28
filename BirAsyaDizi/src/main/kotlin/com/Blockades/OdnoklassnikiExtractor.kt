package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class OdnoklassnikiExtractor : ExtractorApi() {
    override var name = "Odnoklassniki"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Odnoklassniki_DEBUG", "getUrl çağrıldı. url: $url")

        // URL'yi normalize et
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replace("http://", "https://")
            url.startsWith("https://") -> url
            else -> "https://$url"
        }
        Log.d("Odnoklassniki_DEBUG", "Normalize edilmiş URL: $normalizedUrl")

        val response = try {
            app.get(normalizedUrl, referer = referer).text
        } catch (e: Exception) {
            Log.e("Odnoklassniki_DEBUG", "HTTP isteği başarısız!", e)
            return
        }
        Log.d("Odnoklassniki_DEBUG", "Yanıt uzunluğu: ${response.length}")
        // Log.d("Odnoklassniki_DEBUG", "Yanıt başı (ilk 3000 karakter): ${response.take(3000)}")

        // ---- VİDEO AYIKLAMA ----
        // Farklı formatları yakalamak için birden fazla regex deniyoruz
        val videoRegexes = listOf(
            // Format 1: {"name":"HD","url":"https://..."}
            Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)""""),
            // Format 2: {"url":"https://...","name":"HD"}
            Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"([^"]+)""""),
            // Format 3: Basit URL yakalama (mp4/m3u8 ile biten)
            Regex(""""(https?://[^"]+\.(?:mp4|m3u8)[^"]*)""""),
            // Format 4: videoUrl veya video_url değişkenleri
            Regex("""(?:videoUrl|video_url|src)\s*[:=]\s*['"](https?://[^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""")
        )

        var foundVideos = 0
        for ((index, regex) in videoRegexes.withIndex()) {
            val matches = regex.findAll(response).toList()
            Log.d("Odnoklassniki_DEBUG", "Regex #$index eşleşme sayısı: ${matches.size}")

            matches.forEach { matchResult ->
                val groups = matchResult.groupValues
                // Regex #2'de URL 1., isim 2. grupta; diğerlerinde tam tersi
                val videoUrl: String
                val qualityName: String

                if (index == 1 && groups.size >= 3) { // Format 2
                    videoUrl = groups[1]
                    qualityName = groups[2]
                } else if (index == 2 && groups.size >= 2) { // Format 3
                    videoUrl = groups[1]
                    qualityName = ""
                } else if (index == 3 && groups.size >= 2) { // Format 4
                    videoUrl = groups[1]
                    qualityName = ""
                } else if (groups.size >= 3) { // Format 1
                    qualityName = groups[1]
                    videoUrl = groups[2]
                } else {
                    return@forEach
                }

                Log.d("Odnoklassniki_DEBUG", "Video bulundu -> Kalite: '$qualityName' URL: '$videoUrl'")

                if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("m3u8"))) {
                    val quality = when {
                        qualityName.contains("1080", true) -> Qualities.P1080.value
                        qualityName.contains("720", true) -> Qualities.P720.value
                        qualityName.contains("480", true) -> Qualities.P480.value
                        qualityName.contains("360", true) -> Qualities.P360.value
                        qualityName.contains("240", true) -> Qualities.P240.value
                        qualityName.contains("144", true) -> Qualities.P144.value
                        else -> Qualities.Unknown.value
                    }
                    val linkType = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    Log.d("Odnoklassniki_DEBUG", "Callback'e video gönderiliyor: ${videoUrl.replace("\\/", "/")} - Kalite: $quality")
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = videoUrl.replace("\\/", "/"),
                            type = linkType
                        ) {
                            this.referer = referer ?: normalizedUrl
                            this.quality = quality
                        }
                    )
                    foundVideos++
                }
            }
            // Eğer bir regex video bulduysa diğerlerini denemeye gerek yok
            if (foundVideos > 0) break
        }

        if (foundVideos == 0) {
            Log.e("Odnoklassniki_DEBUG", "HATA: Hiçbir video URL'si ayıklanamadı!")
        }

        // ---- ALTYAZI AYIKLAMA ----
        val subRegex = Regex(""""url"\s*:\s*"([^"]+\.(?:vtt|srt))"[^}]*?"lang"\s*:\s*"([^"]+)"""")
        val subMatches = subRegex.findAll(response).toList()
        Log.d("Odnoklassniki_DEBUG", "Altyazı eşleşme sayısı: ${subMatches.size}")
        subMatches.forEach { matchResult ->
            val subUrl = matchResult.groupValues[1]
            val lang = matchResult.groupValues[2]
            Log.d("Odnoklassniki_DEBUG", "Altyazı bulundu -> Dil: $lang URL: $subUrl")
            subtitleCallback.invoke(
                SubtitleFile(lang, subUrl.replace("\\/", "/"))
            )
        }

        Log.d("Odnoklassniki_DEBUG", "getUrl tamamlandı. Toplam video: $foundVideos")
    }
}
