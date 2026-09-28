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
    override var mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Odnoklassniki_DEBUG", "getUrl çağrıldı. url: $url")

        // URL'yi normalize et (//odnoklassniki.ru/... gibi başlıyorsa https: ekle)
        val normalizedUrl = if (url.startsWith("//")) "https:$url" else url
        Log.d("Odnoklassniki_DEBUG", "Normalize edilmiş URL: $normalizedUrl")

        val response = try {
            app.get(normalizedUrl, referer = referer).text
        } catch (e: Exception) {
            Log.e("Odnoklassniki_DEBUG", "HTTP isteği başarısız!", e)
            return
        }
        Log.d("Odnoklassniki_DEBUG", "Yanıt uzunluğu: ${response.length}")

        // Yanıtın video içeren kısmını loglayalım (çok uzun olabilir, sadece ilk 3000 karakter)
        Log.d("Odnoklassniki_DEBUG", "Yanıt başı (ilk 3000 karakter): ${response.take(3000)}")

        // videoRegex - iki farklı sıralama için iki alternatif
        val videoRegex = Regex("""\{\"name\":\"(.*?)\",\"url\":\"(.*?)\"|\{\"url\":\"(.*?)\",\"name\":\"(.*?)\"""")
        val videoMatches = videoRegex.findAll(response).toList()
        Log.d("Odnoklassniki_DEBUG", "Video regex eşleşme sayısı: ${videoMatches.size}")

        if (videoMatches.isEmpty()) {
            // Alternatif regex deneyelim: OK.ru'nun yeni formatı için
            Log.d("Odnoklassniki_DEBUG", "Birincil regex eşleşme bulamadı, alternatif regex deneniyor...")
            val altRegex = Regex(""""url":"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""")
            val altMatches = altRegex.findAll(response).toList()
            Log.d("Odnoklassniki_DEBUG", "Alternatif regex eşleşme sayısı: ${altMatches.size}")

            altMatches.forEachIndexed { index, matchResult ->
                val videoUrl = matchResult.groupValues[1]
                Log.d("Odnoklassniki_DEBUG", "Alternatif Video #$index - URL: $videoUrl")
                if (videoUrl.isNotBlank()) {
                    val linkType = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = videoUrl.replace("\\/", "/"),
                            type = linkType
                        ) {
                            this.referer = referer ?: normalizedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        videoMatches.forEachIndexed { index, matchResult ->
            val qualityName = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[4] }
            val videoUrl = matchResult.groupValues[2].ifEmpty { matchResult.groupValues[3] }
            Log.d("Odnoklassniki_DEBUG", "Video #$index - Kalite: '$qualityName', URL: '$videoUrl'")

            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("m3u8"))) {
                val quality = when {
                    qualityName.contains("1080", true) -> Qualities.P1080.value
                    qualityName.contains("720", true) -> Qualities.P720.value
                    qualityName.contains("480", true) -> Qualities.P480.value
                    qualityName.contains("360", true) -> Qualities.P360.value
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
            }
        }

        // Altyazıları bulmak için regex
        val subtitleRegex = Regex("""\{\"url\":\"(.*?)\",\"lang\":\"(.*?)\"""")
        val subMatches = subtitleRegex.findAll(response).toList()
        Log.d("Odnoklassniki_DEBUG", "Altyazı regex eşleşme sayısı: ${subMatches.size}")
        subMatches.forEach { matchResult ->
            val subUrl = matchResult.groupValues[1]
            val lang = matchResult.groupValues[2]
            Log.d("Odnoklassniki_DEBUG", "Altyazı - Dil: $lang, URL: $subUrl")
            if (subUrl.isNotBlank() && (subUrl.endsWith(".vtt") || subUrl.endsWith(".srt"))) {
                subtitleCallback.invoke(
                    SubtitleFile(lang, subUrl.replace("\\/", "/"))
                )
            }
        }

        Log.d("Odnoklassniki_DEBUG", "getUrl tamamlandı.")
    }
}
