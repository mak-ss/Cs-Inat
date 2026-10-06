package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject

// Next.js veri yapıları
data class ExtractorPageData(val props: ExtractorProps)
data class ExtractorProps(val pageProps: ExtractorPageProps)
data class ExtractorPageProps(val movieAssets: ExtractorMovieAssets?)
data class ExtractorMovieAssets(val `data`: ExtractorMovieAssetData?)
data class ExtractorMovieAssetData(val video_id: String?)


class PuhuTvExtractor : ExtractorApi() {

    override val name = "PuhuTV"
    override val mainUrl = "https://puhutv.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Adım: Bölüm sayfasından __NEXT_DATA__'yı çekip video_id'yi al.
        val document = app.get(url, headers = headers).document
        val nextDataJson = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw ErrorLoadingException("Bölüm sayfası verisi (__NEXT_DATA__) bulunamadı.")

        val pageData = parseJson<ExtractorPageData>(nextDataJson)
        val videoId = pageData.props.pageProps.movieAssets?.data?.video_id
            ?: throw ErrorLoadingException("Video ID bulunamadı.")

        // 2. Adım: video_id ile video API'sini çağır.
        val videosApiUrl = "$mainUrl/api/assets/$videoId/videos"
        val videosResponse = app.get(videosApiUrl, headers = headers).text

        val jsonResponse = JSONObject(videosResponse)
        val videos = jsonResponse.optJSONObject("data")?.optJSONArray("videos")
            ?: throw ErrorLoadingException("Video listesi API'den alınamadı.")

        if (videos.length() == 0) return

        // 3. Adım: Gelen video linklerini işle ve callback'e gönder.
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue

            val mediaUrl = video.optString("url").trim()
            if (mediaUrl.isEmpty() || !mediaUrl.startsWith("http")) continue

            val quality = video.optInt("quality", 0)
            val qualityValue = if (quality > 0) quality else Qualities.Unknown.value
            
            // PuhuTV genellikle HLS (m3u8) kullanır.
            val isHls = mediaUrl.contains(".m3u8", ignoreCase = true)

            if (isHls) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = mediaUrl,
                    referer = "$mainUrl/",
                    headers = headers
                ).forEach(callback)
            } else {
                // Eğer HLS değilse direkt video linki olarak ekle
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = if (quality > 0) "PuhuTV ${quality}p" else "PuhuTV",
                        url = mediaUrl,
                        referer = "$mainUrl/",
                        quality = qualityValue,
                        type = ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }
        }
    }

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }
}
