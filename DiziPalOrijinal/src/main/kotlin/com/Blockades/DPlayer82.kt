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
        val realReferer = referer ?: "https://dizipal1591.com/"

        // 1) iframe.php sayfasını al
        val page = app.get(
            url,
            referer = realReferer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).text

        // 2) window.openPlayer('PLAYLIST', 'REQHASH', ...) içinden playList'i çek
        // playList base64 string — tek tırnak içinde
        val playListRegex = Regex("""openPlayer\(\s*'([^']+)'\s*,\s*'([^']*)'""")
        val match = playListRegex.find(page) ?: return
        val playList = match.groupValues[1]

        // 3) source2.php?v=<playList> isteği at
        // Bu isteğin de aynı domain üzerinden gitmesi gerekiyor (yani four.dplayer82.site/source2.php)
        val source2Url = "$mainUrl/source2.php?v=$playList"
        val jsonText = try {
            app.get(
                source2Url,
                referer = url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
            ).text
        } catch (e: Exception) {
            return
        }

        val json = try { JSONObject(jsonText) } catch (e: Exception) { return }

        // 4) playlist[] içinden m3u8'leri al
        val playlistArray = json.optJSONArray("playlist") ?: return
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            val sources = item.optJSONArray("sources") ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.getJSONObject(j)
                val file = src.optString("file").takeIf { it.isNotBlank() } ?: continue
                // m.php → master.m3u8 dönüşümü (site kendi JS'inde yapıyor)
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
                            "Origin" to "https://four.dplayer82.site"
                        )
                    }
                )
            }
        }

        // 5) Altyazıları da ekle (subtitles[] dizisinden)
        // openPlayer'ın son parametresi subtitles[] — ama bunu regex ile çekmek zor,
        // onun yerine source2.php yanıtında subtitles da varsa al:
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
