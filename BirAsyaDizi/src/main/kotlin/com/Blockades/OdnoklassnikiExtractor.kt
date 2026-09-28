package com.Blockades

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
        val response = app.get(url, referer = referer).text

        // Video URL'sini içeren JavaScript bloğunu bulmak için regex.
        // Örnek: 'videos':[{'url':'...mp4','name':'...','quality':'...'}]
        val videoRegex = Regex("""\{\"name\":\"(.*?)\",\"url\":\"(.*?)\"|\{\"url\":\"(.*?)\",\"name\":\"(.*?)\"""")

        videoRegex.findAll(response).forEach { matchResult ->
            val qualityName = matchResult.groupValues[1].ifEmpty { matchResult.groupValues[4] }
            val videoUrl = matchResult.groupValues[2].ifEmpty { matchResult.groupValues[3] }

            if (videoUrl.isNotBlank() && (videoUrl.contains(".mp4") || videoUrl.contains("m3u8"))) {
                val quality = when {
                    qualityName.contains("1080", true) -> Qualities.P1080.value
                    qualityName.contains("720", true) -> Qualities.P720.value
                    qualityName.contains("480", true) -> Qualities.P480.value
                    qualityName.contains("360", true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                val linkType = if (videoUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl.replace("\\/", "/"),
                        type = linkType
                    ) {
                        // referer null olabilir, bu yüzden güvenli bir şekilde atıyoruz
                        this.referer = referer ?: ""
                        this.quality = quality
                    }
                )
            }
        }

        // Altyazıları bulmak için regex (eğer varsa)
        // Örnek: 'subtitles':[{'url':'...', 'lang':'...'}]
        val subtitleRegex = Regex("""\{\"url\":\"(.*?)\",\"lang\":\"(.*?)\"""")
        subtitleRegex.findAll(response).forEach { matchResult ->
            val subUrl = matchResult.groupValues[1]
            val lang = matchResult.groupValues[2]
            if (subUrl.isNotBlank() && (subUrl.endsWith(".vtt") || subUrl.endsWith(".srt"))) {
                subtitleCallback.invoke(
                    SubtitleFile(lang, subUrl.replace("\\/", "/"))
                )
            }
        }
    }
}
