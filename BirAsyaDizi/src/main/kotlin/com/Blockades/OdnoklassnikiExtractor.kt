package com.Blockades
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLDecoder

class OdnoklassnikiExtractor : ExtractorApi() {
    override var name = "Odnoklassniki"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = "https://ok.ru/").text

        val optionsRegex = Regex("""data-options="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        val rawOptions = optionsRegex.find(response)?.groupValues?.get(1) ?: return

        val decoded = rawOptions
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")

        val json = try {
            JSONObject(URLDecoder.decode(decoded, "UTF-8"))
        } catch (e: Exception) {
            return
        }

        val videos = json.optJSONArray("videos") ?: return

        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val videoUrl = video.optString("url").takeIf { it.isNotBlank() } ?: continue
            val videoName = video.optString("name").lowercase()

            val quality = when {
                videoName.contains("1080") -> Qualities.P1080.value
                videoName.contains("720")  -> Qualities.P720.value
                videoName.contains("480")  -> Qualities.P480.value
                videoName.contains("360")  -> Qualities.P360.value
                videoName.contains("240")  -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }

            // 使用 newExtractorLink 替代旧构造函数
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://ok.ru/"
                    this.quality = quality
                }
            )
        }
    }
}
