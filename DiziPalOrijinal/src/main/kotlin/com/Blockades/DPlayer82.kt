package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class DPlayer82 : ExtractorApi() {
    override var name = "DPlayer82"
    override var mainUrl = "https://four.dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: "https://dizipal1592.com/"

        // 403'ü aşmak için tam tarayıcı başlıkları
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to realReferer,
            "Origin" to "https://four.dplayer82.site",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1"
        )

        val page = try {
            app.get(url, headers = headers, referer = realReferer).text
        } catch (e: Exception) {
            // Referer'sız ikinci deneme (bazı sunucular referer'ı reddeder)
            try {
                app.get(url, headers = headers - "Referer").text
            } catch (e2: Exception) {
                return
            }
        }

        // window.openPlayer('PLAYLIST', 'REQHASH', ...) içinden playList'i çek
        val playListRegex = Regex("""openPlayer\(\s*'([^']+)'\s*,\s*'([^']*)'""")
        val match = playListRegex.find(page) ?: return
        val playList = match.groupValues[1]

        val source2Url = "$mainUrl/source2.php?v=$playList"
        val jsonText = try {
            app.get(
                source2Url,
                headers = headers + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = url
            ).text
        } catch (e: Exception) {
            return
        }

        val json = try { JSONObject(jsonText) } catch (e: Exception) { return }

        val playlistArray = json.optJSONArray("playlist") ?: return
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            val sources = item.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.getJSONObject(j)
                val file = src.optString("file").takeIf { it.isNotBlank() } ?: continue
                val m3u8 = file.replace("m.php", "master.m3u8")
                val title = src.optString("title").takeIf { it.isNotBlank() } ?: name

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - $title",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = realReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to realReferer,
                            "Origin" to "https://four.dplayer82.site",
                            "User-Agent" to headers["User-Agent"]!!
                        )
                    }
                )
            }
        }

        val subsArray = json.optJSONArray("subtitles")
        if (subsArray != null) {
            for (i in 0 until subsArray.length()) {
                val s = subsArray.getJSONObject(i)
                val file = s.optString("file").takeIf { it.isNotBlank() } ?: continue
                val label = s.optString("label").takeIf { it.isNotBlank() } ?: "Sub"
                subtitleCallback.invoke(SubtitleFile(label, file))
            }
        }
    }
}
