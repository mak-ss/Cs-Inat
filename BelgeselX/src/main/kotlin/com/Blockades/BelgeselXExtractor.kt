// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout

open class BelgeselXExtractor : ExtractorApi() {
    override val name = "BelgeselX"
    override val mainUrl = "https://belgeselx.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Kekik_${this.name}", "url » $url")
        val headers = mutableMapOf<String, String>()
        referer?.let { headers["Referer"] = it }

        try {
            val response = withTimeout(10_000) {
                app.get(url, headers = headers)
            }
            val html = response.text
            Log.d("Kekik_${this.name}", "Response length » ${html.length}")

            val fileLabelPattern = Regex(
                """file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )

            fileLabelPattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1].trim()
                val label = match.groupValues[2].trim()
                val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

                val quality = when {
                    label.contains("1080", true) || label.contains("FULL", true) -> Qualities.P1080.value
                    label.contains("720", true)  || label.contains("HD", true)   -> Qualities.P720.value
                    label.contains("480", true)  || label.contains("SD", true)   -> Qualities.P480.value
                    label.contains("360", true)  -> Qualities.P360.value
                    label.contains("240", true)  -> Qualities.P240.value
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - $label",
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = referer ?: ""
                        this.quality = quality
                        this.headers = headers
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Error: ${e.message}", e)
        }
    }
}
