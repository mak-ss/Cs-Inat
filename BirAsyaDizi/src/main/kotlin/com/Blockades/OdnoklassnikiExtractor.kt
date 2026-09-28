package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLDecoder

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

        // Video ID'sini çıkar (videoembed/123456 veya video/123456 formatından)
        val videoId = Regex("""(?:videoembed|video|live)/(\d+)""")
            .find(url)?.groupValues?.get(1)
            ?: run {
                Log.e("Odnoklassniki_DEBUG", "HATA: Video ID çıkarılamadı! url=$url")
                return
            }

        Log.d("Odnoklassniki_DEBUG", "Video ID: $videoId")

        // videoPlayerMetadata API'sine istek at
        // Bu API bazen farklı bir domain isteyebilir, bu yüzden deneme yapıyoruz.
        val apiUrls = listOf(
            "https://odnoklassniki.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
            "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
        )

        var response: String? = null
        for (apiUrl in apiUrls) {
            Log.d("Odnoklassniki_DEBUG", "API URL deniyor: $apiUrl")
            try {
                // API bazen mobil bir User-Agent isteyebilir.
                val apiResponse = app.get(
                    apiUrl,
                    referer = referer ?: "https://ok.ru/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                if (apiResponse.contains("videos")) {
                    response = apiResponse
                    Log.d("Odnoklassniki_DEBUG", "Başarılı API yanıtı alındı.")
                    break
                }
            } catch (e: Exception) {
                Log.e("Odnoklassniki_DEBUG", "API isteği başarısız: $apiUrl", e)
            }
        }

        if (response == null) {
            Log.e("Odnoklassniki_DEBUG", "HATA: Hiçbir API'den geçerli yanıt alınamadı!")
            return
        }

        Log.d("Odnoklassniki_DEBUG", "Yanıt uzunluğu: ${response.length}")

        // JSON parse et
        try {
            // API yanıtı callbackFunc(...) içinde olabilir, temizle
            val jsonString = response
                .substringAfter("callbackFunc(", response)
                .substringBeforeLast(")", response)
                .trim()
                .removeSuffix(";")

            val json = JSONObject(jsonString)
            val videos = json.optJSONArray("videos")

            if (videos == null || videos.length() == 0) {
                Log.e("Odnoklassniki_DEBUG", "HATA: JSON içinde video bulunamadı!")
                Log.d("Odnoklassniki_DEBUG", "JSON başı: ${jsonString.take(2000)}")
                return
            }

            Log.d("Odnoklassniki_DEBUG", "Bulunan video sayısı: ${videos.length()}")

            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                var videoUrl = video.optString("url")
                val videoName = video.optString("name", "Unknown")

                if (videoUrl.isBlank()) continue

                // URL'deki HTML entity'leri ve escape karakterlerini temizle
                videoUrl = videoUrl.replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("\\u0026", "&")

                // Kaliteyi belirle
                val quality = when {
                    videoName.contains("1080", true) -> Qualities.P1080.value
                    videoName.contains("720", true) -> Qualities.P720.value
                    videoName.contains("480", true) -> Qualities.P480.value
                    videoName.contains("360", true) -> Qualities.P360.value
                    videoName.contains("240", true) -> Qualities.P240.value
                    videoName.contains("144", true) -> Qualities.P144.value
                    videoName.contains("mobile", true) -> Qualities.P144.value
                    videoName.contains("lowest", true) -> Qualities.P144.value
                    videoName.contains("low", true) -> Qualities.P240.value
                    videoName.contains("sd", true) -> Qualities.P360.value
                    videoName.contains("hd", true) -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }

                // Link tipini belirle
                val linkType = if (videoUrl.contains("m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

                Log.d("Odnoklassniki_DEBUG", "Video bulundu -> Kalite: $videoName ($quality) URL: ${videoUrl.take(100)}...")

                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = linkType
                    ) {
                        this.referer = referer ?: "https://ok.ru/"
                        this.quality = quality
                    }
                )
            }

            // Altyazıları da API'den alabiliriz (genellikle "subtitle" alanı olur)
            val subtitles = json.optJSONArray("subtitle")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.getJSONObject(i)
                    val subUrl = sub.optString("url")
                    val subLang = sub.optString("lang", "Unknown")
                    if (subUrl.isNotBlank()) {
                        subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("Odnoklassniki_DEBUG", "JSON parse hatası!", e)
            Log.d("Odnoklassniki_DEBUG", "Yanıt başı: ${response.take(2000)}")
        }
    }
}
