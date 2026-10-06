package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class PuhuTvExtractor : ExtractorApi() {

    override val name = "PuhuTV"
    override val mainUrl = "https://puhutv.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Doğrudan .m3u8 bağlantısı gelmesi durumu
        if (url.contains(".m3u8")) {
            try {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = url,
                    referer = "$mainUrl/"
                ).forEach(callback)
            } catch (_: Exception) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "PuhuTV HLS",
                        url = url,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
            }
            return
        }

        val slug = extractSlug(url) ?: return
        val apiUrl = "$mainUrl/api/slug/${URLEncoder.encode(slug, "UTF-8")}-izle"

        val infoResponse = try {
            app.get(apiUrl, headers = headers)
        } catch (_: Exception) {
            return
        }

        if (!infoResponse.isSuccessful) return

        val infoJson = try {
            JSONObject(infoResponse.text)
        } catch (_: Exception) {
            return
        }

        val data = infoJson.optJSONObject("data") ?: return

        val videoId = when {
            data.has("id") -> data.optString("id")
            data.optJSONObject("asset")?.has("id") == true ->
                data.optJSONObject("asset")?.optString("id") ?: ""
            else -> ""
        }

        if (videoId.isBlank()) return

        val videosUrl = "$mainUrl/api/assets/$videoId/videos"
        val videosResponse = try {
            app.get(videosUrl, headers = headers)
        } catch (_: Exception) {
            return
        }

        if (!videosResponse.isSuccessful) return

        val videosJson = try {
            JSONObject(videosResponse.text)
        } catch (_: Exception) {
            return
        }

        val videos = videosJson
            .optJSONObject("data")
            ?.optJSONArray("videos")
            ?: videosJson.optJSONArray("videos")
            ?: JSONArray()

        if (videos.length() == 0) return

        val emitted = HashSet<String>()

        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue

            val mediaUrl = firstUrl(
                video,
                "url", "src", "video_url", "videoUrl",
                "play_url", "playUrl"
            ) ?: continue

            if (!emitted.add(mediaUrl)) continue

            val streamType = video.optString("stream_type").lowercase()
            val videoFormat = video.optString("video_format").lowercase()

            val isPlaylist = if (video.has("is_playlist")) {
                video.optBoolean("is_playlist")
            } else {
                mediaUrl.contains(".m3u8", ignoreCase = true)
            }

            val quality = video.optInt("quality", 0)
            val qualityValue = if (quality > 0) quality else Qualities.Unknown.value

            val isHls = mediaUrl.contains(".m3u8", ignoreCase = true) ||
                    mediaUrl.contains("chunklist.m3u8", ignoreCase = true) ||
                    mediaUrl.contains("/hls/", ignoreCase = true) ||
                    streamType == "hls" ||
                    videoFormat == "hls"

            if (isHls || isPlaylist) {
                try {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = mediaUrl,
                        referer = "$mainUrl/"
                    ).forEach(callback)
                } catch (_: Exception) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = if (quality > 0) "PuhuTV ${quality}p" else "PuhuTV HLS",
                            url = mediaUrl,
                            referer = "$mainUrl/",
                            quality = qualityValue,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                }
            } else {
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

    private fun extractSlug(url: String): String? {
        val clean = url.substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')

        val last = clean.substringAfterLast('/')

        if (last.isBlank()) return null

        return when {
            last.endsWith("-izle", ignoreCase = true) ->
                last.removeSuffix("-izle")
            else -> last
        }.takeIf { it.isNotBlank() }
    }

    private fun firstUrl(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = json.optString(key).trim()

            if (value.isNotEmpty() &&
                value != "null" &&
                (value.startsWith("http://") || value.startsWith("https://"))
            ) {
                return value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
            }
        }
        return null
    }

    companion object {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/140.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }
}
