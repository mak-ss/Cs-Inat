package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SuperAdjacentExtractor : ExtractorApi() {
    override val name = "SuperAdjacent"
    override val mainUrl = "https://superadjacentsoddenly.xyz"
    override val requiresReferer = true

    private val UA = "Mozilla/5.0 (Linux; Android 10; AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
            .followRedirects(true)
            .build()
    }

    private fun normalize(text: String): String =
        text.replace("\\/", "/").replace("&amp;", "&")

    private fun originOf(url: String): String = try {
        val u = java.net.URI(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) { mainUrl }

    private fun fixUrlExtractor(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://dizipal2143.com/"

        try {
            // 1) Doğrudan m3u8
            if (url.contains(".m3u8", ignoreCase = true)) {
                emit(callback, url, pageReferer, originOf(url))
                return
            }

            // 2) Embed sayfasını çek
            val req = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Referer", pageReferer)
                .addHeader("User-Agent", UA)
                .addHeader("Accept", "*/*")
                .addHeader("Origin", "https://dizipal2143.com")
                .build()

            val pageText = try {
                relaxedClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            } catch (e: Exception) {
                Log.e("SuperAdjacent", "fetch HATA » ${e.message}")
                ""
            }

            val normalized = normalize(pageText)

            // 3) m3u8 regex
            val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
            var m3u8 = m3u8Regex.find(normalized)?.groupValues?.getOrNull(1)

            // 4) Alternatif kalıplar
            if (m3u8.isNullOrBlank()) {
                val patterns = listOf(
                    Regex("""<source[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""data-url=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""["'](https?://[^"']+/hls2/[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                )
                for (p in patterns) {
                    val m = p.find(normalized)?.groupValues?.getOrNull(1) ?: continue
                    val fixed = fixUrlExtractor(m)
                    if (fixed.contains(".m3u8", ignoreCase = true)) {
                        m3u8 = fixed
                        break
                    }
                }
            }

            if (m3u8.isNullOrBlank()) {
                Log.w("SuperAdjacent", "m3u8 bulunamadı: $url")
                return
            }

            emit(callback, m3u8, url, originOf(url))
        } catch (e: Exception) {
            Log.e("SuperAdjacent", "getUrl HATA » ${e.message}", e)
        }
    }

    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        m3u8: String,
        referer: String,
        origin: String
    ) {
        val link = newExtractorLink(
            source = this.name,
            name = this.name,
            url = m3u8,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Origin" to "https://dizipal2143.com",
                "Referer" to referer
            )
        }
        callback.invoke(link)
    }
}
