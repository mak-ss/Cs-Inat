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

class DizipalPlayer2 : ExtractorApi() {
    override val name = "DizipalPlayer2"
    override val mainUrl = "https://dizipal2143.com"
    override val requiresReferer = true

    private val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    private fun fixUrlExtractor(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
    }

    private fun originOf(url: String): String = try {
        val u = java.net.URI(url)
        val portPart = if (u.port != -1) ":${u.port}" else ""
        "${u.scheme}://${u.host}$portPart"
    } catch (_: Exception) {
        mainUrl
    }

    private fun normalize(text: String): String =
        text.replace("\\/", "/").replace("&amp;", "&")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "$mainUrl/"
        try {
            // 1) Doğrudan m3u8 ise direkt gönder
            if (url.contains(".m3u8", ignoreCase = true)) {
                emit(callback, url, pageReferer, originOf(url))
                return
            }

            // 2) Sayfayı çek
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Referer", pageReferer)
                .addHeader("User-Agent", UA)
                .addHeader("Accept", "*/*")
                .build()

            val pageText = try {
                relaxedClient.newCall(request).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                Log.e("DizipalPlayer2", "Fetch HATA » ${e.message}")
                ""
            }

            val normalized = normalize(pageText)

            // 3) Doğrudan m3u8 regex
            val m3u8Regex = Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""")
            var m3u8 = m3u8Regex.find(normalized)?.groupValues?.getOrNull(1)

            // 4) Bulunamazsa iframe/source/data-url/file:
            if (m3u8.isNullOrBlank()) {
                val patterns = listOf(
                    Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                    Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
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
                Log.w("DizipalPlayer2", "Hiçbir m3u8 bulunamadı: $url")
                return
            }

            emit(callback, m3u8, url, originOf(url))
        } catch (e: Exception) {
            Log.e("DizipalPlayer2", "getUrl HATA » ${e.message}", e)
        }
    }

    /** suspend: newExtractorLink suspend olduğu için */
    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        m3u8: String,
        refererUrl: String,
        origin: String
    ) {
        val link = newExtractorLink(
            source = this.name,
            name = this.name,
            url = m3u8,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = refererUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to UA,
                "Origin" to origin,
                "Referer" to refererUrl
            )
        }
        callback.invoke(link)
    }
}
