// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout

/**
 * BelgeselX'in kendi video endpoint'leri için extractor.
 * new1.php - new5.php endpoint'lerinden video URL'lerini çıkarır.
 *
 * Kullanım: loadLinks içinden çağrılır.
 *   https://belgeselx.com/video/data/new4.php?id=16357&sira=1
 */
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
        Log.d("Kekik_${this.name}", "referer » $referer")

        val headers = mutableMapOf<String, String>()
        referer?.let { headers["Referer"] = it }

        try {
            val response = withTimeout(10_000) {
                app.get(url, headers = headers)
            }
            val html = response.text
            Log.d("Kekik_${this.name}", "Response length » ${html.length}")
            Log.d("Kekik_${this.name}", "Response preview » ${html.take(300)}")

            // Pattern 1: file:"URL", label:"LABEL"
            val fileLabelPattern = Regex(
                """file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
            val matches = fileLabelPattern.findAll(html).toList()

            if (matches.isNotEmpty()) {
                matches.forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    val label = match.groupValues[2].trim()

                    Log.d("Kekik_${this.name}", "Found » $label : $videoUrl")

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
                return
            }

            // Pattern 2: file:"URL" (label'sız)
            val fileOnlyPattern = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val fileOnlyMatches = fileOnlyPattern.findAll(html).toList()

            if (fileOnlyMatches.isNotEmpty()) {
                fileOnlyMatches.forEach { match ->
                    val videoUrl = match.groupValues[1].trim()
                    val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl

                    Log.d("Kekik_${this.name}", "Found (no label) » $finalUrl")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer ?: ""
                            this.headers = headers
                        }
                    )
                }
                return
            }

            // Pattern 3: iframe src
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val iframeMatches = iframePattern.findAll(html).toList()

            if (iframeMatches.isNotEmpty()) {
                iframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1].trim()
                    val finalIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                    Log.d("Kekik_${this.name}", "Iframe found » $finalIframeUrl")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - iframe",
                            url = finalIframeUrl,
                            type = INFER_TYPE
                        ) { this.referer = referer ?: "" }
                    )
                }
                return
            }

            Log.w("Kekik_${this.name}", "No video source found in $url")

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Error: ${e.message}", e)
        }
    }
}
