package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MntPlayer12 : ExtractorApi() {
    override var mainUrl = "https://vod.mntplayer.com:8443"
    override var name = "MntPlayer12"
    override val requiresReferer = true

    private val relaxedClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val safeReferer = referer ?: "https://dizipal12.com.tr/"

        // Zaten m3u8 ise doğrudan gönder
        if (url.contains(".m3u8")) {
            pushHls(url, safeReferer, callback)
            return
        }

        // mntplayer oynatıcı sayfasını çek
        val pageText = try {
            app.get(url, referer = safeReferer).text
        } catch (e: Exception) {
            Log.e("MntPlayer12", "app.get HATA » ${e.message}")
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Referer", safeReferer)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()
                val response = relaxedClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()
                body
            } catch (e2: Exception) {
                Log.e("MntPlayer12", "relaxedClient HATA » ${e2.message}")
                ""
            }
        }

        if (pageText.isNotEmpty()) {
            val m3u8Regex = Regex("""(https?://vod\.mntplayer\.com:8443/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
            val matches = m3u8Regex.findAll(pageText).map { it.value }.toList()

            val best = matches.firstOrNull { it.contains("master.m3u8") }
                ?: matches.firstOrNull { it.contains("video.m3u8") }
                ?: matches.firstOrNull()

            if (best != null) {
                pushHls(best, safeReferer, callback)
                return
            }
        }

        Log.e("MntPlayer12", "m3u8 bulunamadı » $url")
    }

    private suspend fun pushHls(m3u8Url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "MntPlayer12 HLS",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
