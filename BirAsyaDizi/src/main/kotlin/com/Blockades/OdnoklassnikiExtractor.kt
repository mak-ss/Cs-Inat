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

class OdnoklassnikiExtractor : ExtractorApi() {
    override var name = "Odnoklassniki"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = true

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,tr;q=0.8"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "en-US,en;q=0.9,tr;q=0.8",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Odnoklassniki_DEBUG", "getUrl çağrıldı. url: $url")

        // Video ID'sini çıkar
        val videoId = Regex("""(?:videoembed|video|live)/(\d+)""")
            .find(url)?.groupValues?.get(1)
            ?: run {
                Log.e("Odnoklassniki_DEBUG", "HATA: Video ID çıkarılamadı! url=$url")
                return
            }

        Log.d("Odnoklassniki_DEBUG", "Video ID: $videoId")

        val embedReferer = "https://ok.ru/videoembed/$videoId"

        // ---- 1. ADIM: videoembed sayfasını çek ve data-options içinden JSON çıkar ----
        val embedPage = try {
            app.get(embedReferer, referer = "https://ok.ru/", headers = browserHeaders).text
        } catch (e: Exception) {
            Log.e("Odnoklassniki_DEBUG", "videoembed sayfası çekilemedi!", e)
            ""
        }

        Log.d("Odnoklassniki_DEBUG", "videoembed sayfa uzunluğu: ${embedPage.length}")

        var jsonString: String? = null

        // data-options attribute'unu bul (OKVideo modülü)
        val dataOptionsRegex = Regex("""data-options="([^"]+)"""")
        val dataOptionsMatch = dataOptionsRegex.find(embedPage)
        if (dataOptionsMatch != null) {
            val raw = dataOptionsMatch.groupValues[1]
            // HTML entity'lerini çöz
            val decoded = raw
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            Log.d("Odnoklassniki_DEBUG", "data-options bulundu (ilk 300 karakter): ${decoded.take(300)}")
            jsonString = decoded
        } else {
            Log.d("Odnoklassniki_DEBUG", "data-options bulunamadı, alternatif regex deniyor...")
            // Bazen data-options tek tırnak ile olabilir
            val altRegex = Regex("""data-options='([^']+)'""")
            val altMatch = altRegex.find(embedPage)
            if (altMatch != null) {
                jsonString = altMatch.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
            }
        }

        // data-options bulunamadıysa, embed sayfası içinde video metadata JSON'u arayalım
        if (jsonString == null) {
            Log.d("Odnoklassniki_DEBUG", "data-options yok, sayfa içinde video metadata aranıyor...")
            // Bazı sayfalarda video metadata doğrudan bir JS değişkeninde olur
            val metadataRegex = Regex(""""videos"\s*:\s*(\[[^\]]+\])""")
            val metadataMatch = metadataRegex.find(embedPage)
            if (metadataMatch != null) {
                jsonString = """{"videos":${metadataMatch.groupValues[1]}}"""
                Log.d("Odnoklassniki_DEBUG", "Sayfa içinde videos dizisi bulundu.")
            }
        }

        // ---- 2. ADIM: JSON bulunamadıysa, API'ye fallback yap ----
        if (jsonString == null) {
            Log.d("Odnoklassniki_DEBUG", "data-options'ta bulunamadı, API deneniyor...")
            val apiUrls = listOf(
                "https://odnoklassniki.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
            )

            for (apiUrl in apiUrls) {
                try {
                    val apiResponse = app.get(
                        apiUrl,
                        referer = embedReferer,
                        headers = apiHeaders
                    ).text

                    Log.d("Odnoklassniki_DEBUG", "API yanıtı (ilk 200 karakter): ${apiResponse.take(200)}")

                    // JSON mu yoksa HTML mi kontrol et
                    if (apiResponse.trimStart().startsWith("{") || apiResponse.contains("callbackFunc(")) {
                        // JSONP temizliği
                        jsonString = apiResponse
                            .substringAfter("callbackFunc(", apiResponse)
                            .substringBeforeLast(")", apiResponse)
                            .trim()
                            .removeSuffix(";")

                        if (!jsonString.trimStart().startsWith("{")) {
                            jsonString = apiResponse
                        }
                        Log.d("Odnoklassniki_DEBUG", "API'den JSON alındı.")
                        break
                    } else {
                        Log.w("Odnoklassniki_DEBUG", "API HTML döndü, atlanıyor: $apiUrl")
                    }
                } catch (e: Exception) {
                    Log.e("Odnoklassniki_DEBUG", "API isteği başarısız: $apiUrl", e)
                }
            }
        }

        if (jsonString == null) {
            Log.e("Odnoklassniki_DEBUG", "HATA: Hiçbir kaynaktan video JSON'u alınamadı!")
            return
        }

        // ---- 3. ADIM: JSON'u parse et ----
        try {
            val json = JSONObject(jsonString)
            val videos = json.optJSONArray("videos")

            if (videos == null || videos.length() == 0) {
                Log.e("Odnoklassniki_DEBUG", "HATA: JSON içinde video bulunamadı!")
                Log.d("Odnoklassniki_DEBUG", "JSON başı: ${jsonString.take(1500)}")
                return
            }

            Log.d("Odnoklassniki_DEBUG", "Bulunan video sayısı: ${videos.length()}")

            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                var videoUrl = video.optString("url")
                val videoName = video.optString("name", "Unknown")

                if (videoUrl.isBlank()) continue

                // Escape karakterlerini temizle
                videoUrl = videoUrl
                    .replace("\\/", "/")
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

                val linkType = if (videoUrl.contains("m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }

                Log.d("Odnoklassniki_DEBUG", "Video -> Kalite: $videoName ($quality) URL: ${videoUrl.take(120)}...")

                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = linkType
                    ) {
                        this.referer = embedReferer
                        this.quality = quality
                        this.headers = browserHeaders
                    }
                )
            }

            // Altyazılar
            val subtitles = json.optJSONArray("subtitle") ?: json.optJSONArray("subtitles")
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
            Log.d("Odnoklassniki_DEBUG", "JSON başı: ${jsonString.take(2000)}")
        }
    }
}
