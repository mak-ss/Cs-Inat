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

    /** SSL sertifika doğrulamasını gevşeten OkHttpClient */
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

    /** ExtractorApi içinde fixUrlNull olmadığı için basit URL düzeltme */
    private fun fixUrlExtractor(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/")  -> "$mainUrl$url"
            else                 -> "$mainUrl/$url"
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1) Doğrudan m3u8 linki ise direkt callback'e gönder
            if (url.contains(".m3u8") || url.contains("superadjacentsoddenly")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "DizipalPlayer2 HLS",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // 2) Sayfa üzerinden m3u8 çıkarmayı dene (SSL gevşetilmiş client ile)
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Referer", referer ?: "$mainUrl/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = relaxedClient.newCall(request).execute()
            val pageText = response.body?.string() ?: ""
            response.close()

            // m3u8 regex
            val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(pageText)?.value
            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "DizipalPlayer2 HLS",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // 3) iframe / source / data-url / file: gibi alternatifleri de dene
            val patterns = listOf(
                Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(pageText)?.groupValues?.getOrNull(1)
                if (!match.isNullOrBlank()) {
                    val finalUrl = fixUrlExtractor(match)
                    if (finalUrl.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "DizipalPlayer2 HLS",
                                url = finalUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = referer ?: "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        return
                    }
                }
            }

            Log.w("DizipalPlayer2", "Hiçbir m3u8 linki bulunamadı: $url")
        } catch (e: Exception) {
            Log.e("DizipalPlayer2", "getUrl HATA » ${e.message}", e)
        }
    }
}
