package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup

class DPlayer82 : ExtractorApi() {
    override var name = "DPlayer82"
    override var mainUrl = "https://sn.dplayer82.site"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                Log.d("kraptor_DPlayer82", "!!cloudflare geldi!!")
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: "https://dizipal1592.com/"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to realReferer,
            "Origin" to mainUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1"
        )

        Log.d("kraptor_DPlayer82", "getUrl: $url")

        val page = try {
            app.get(url, interceptor = interceptor, headers = headers, referer = realReferer).text
        } catch (e: Exception) {
            Log.e("kraptor_DPlayer82", "sayfa hatası: ${e.message}")
            return
        }

        Log.d("kraptor_DPlayer82", "sayfa uzunluk: ${page.length}, openPlayer var: ${page.contains("openPlayer")}")

        // 1) openPlayer('playList', 'reqHash', ...) → base64 playList
        val playListRegex = Regex("""openPlayer\(\s*'([^']+)'\s*,\s*'([^']*)'""")
        val match = playListRegex.find(page)
        if (match != null) {
            val playList = match.groupValues[1]

            val source2Url = "$mainUrl/source2.php?v=$playList"
            Log.d("kraptor_DPlayer82", "source2: $source2Url")

            val jsonText = try {
                app.get(
                    source2Url,
                    interceptor = interceptor,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    ),
                    referer = url
                ).text
            } catch (e: Exception) {
                Log.e("kraptor_DPlayer82", "source2 hatası: ${e.message}")
                return
            }

            val json = try { JSONObject(jsonText) } catch (e: Exception) {
                Log.e("kraptor_DPlayer82", "json parse hatası: ${e.message}")
                return
            }

            val playlistArray = json.optJSONArray("playlist")
            if (playlistArray != null) {
                for (i in 0 until playlistArray.length()) {
                    val item = playlistArray.getJSONObject(i)
                    val sources = item.optJSONArray("sources") ?: continue
                    for (j in 0 until sources.length()) {
                        val src = sources.getJSONObject(j)
                        val file = src.optString("file").takeIf { it.isNotBlank() } ?: continue
                        val m3u8 = file.replace("m.php", "master.m3u8")
                        val title = src.optString("title").takeIf { it.isNotBlank() } ?: name

                        Log.d("kraptor_DPlayer82", "m3u8: $m3u8 ($title)")

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
                                    "Origin" to mainUrl,
                                    "User-Agent" to headers["User-Agent"]!!
                                )
                            }
                        )
                    }
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
            return
        }

        // 2) openPlayer yoksa doğrudan m3u8 ara
        val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        m3u8Regex.find(page)?.let { m ->
            val m3u8 = m.groupValues[1]
            Log.d("kraptor_DPlayer82", "m3u8 direkt: $m3u8")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = realReferer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
