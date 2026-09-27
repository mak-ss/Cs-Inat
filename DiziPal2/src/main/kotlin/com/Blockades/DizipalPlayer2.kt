package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DizipalPlayer2 : ExtractorApi() {
    override var name = "DizipalPlayer2"
    override var mainUrl = "https://dizipal2134.com"
    override val requiresReferer = true

    init {
        Log.e("DPPLAYER2", "========================================")
        Log.e("DPPLAYER2", "EXTRACTOR YÜKLENDİ! (2026-09-28 03:00)")
        Log.e("DPPLAYER2", "========================================")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e("DPPLAYER2", "===== BAŞLANGIÇ =====")
        Log.e("DPPLAYER2", "url » $url")

        // 1. Bölüm sayfasını çek + cookie topla
        val pageResp = try {
            app.get(url, referer = "$mainUrl/")
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Sayfa hatası » ${e.message}")
            return
        }
        val document = pageResp.document

        // 2. Cookie Map'ini al
        val cookieMap: Map<String, String> = pageResp.cookies
        Log.e("DPPLAYER2", "COOKIES KEYS » ${cookieMap.keys}")
        cookieMap.forEach { (k, v) ->
            Log.e("DPPLAYER2", "  → $k = ${v.take(80)}")
        }

        // 3. cfg
        val cfg = document.selectFirst("#videoContainer")
            ?.attr("data-cfg")?.takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "cfg YOK")
            return
        }
        Log.e("DPPLAYER2", "cfg » $cfg")

        // 4. POST /ajax-player-config — TÜM cookie'lerle
        val response = try {
            app.post(
                "$mainUrl/ajax-player-config",
                data = mapOf("cfg" to cfg),
                referer = url,
                cookies = cookieMap,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            ).text
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "POST hatası » ${e.message}")
            return
        }

        Log.e("DPPLAYER2", "RESPONSE » ${response.take(500)}")

        // 5. Decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt BAŞARISIZ")
            return
        }

        val (videoUrl, videoType) = result
        Log.e("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.e("DPPLAYER2", ">>> videoType » $videoType")

        // 6. iframe → loadExtractor
        if (videoType == "iframe" || videoUrl.contains("<iframe", true)) {
            val iframeUrl = Regex("""src=["']([^"']+)["']""")
                .find(videoUrl)?.groupValues?.get(1)
                ?: videoUrl.takeIf { it.startsWith("http") }

            if (!iframeUrl.isNullOrBlank()) {
                val fixed = fixUrl(iframeUrl)
                Log.e("DPPLAYER2", "iframe » $fixed")
                try {
                    if (loadExtractor(fixed, url, subtitleCallback, callback)) {
                        Log.e("DPPLAYER2", "loadExtractor BAŞARILI")
                        return
                    }
                    Log.e("DPPLAYER2", "loadExtractor FALSE döndü")
                } catch (e: Exception) {
                    Log.e("DPPLAYER2", "loadExtractor hata » ${e.message}")
                }
            }
            return
        }

        // 7. m3u8/mp4 → direkt gönder
        val isM3u8 = videoUrl.contains(".m3u8", true)
        Log.e("DPPLAYER2", "ExtractorLink gönderiliyor (m3u8=$isM3u8)")
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "User-Agent" to USER_AGENT
                )
            }
        )
        Log.e("DPPLAYER2", "===== BİTİŞ =====")
    }

    private fun parseAndDecrypt(response: String): Pair<String, String>? {
        try {
            val json = JSONObject(response)

            if (json.has("message")) {
                Log.e("DPPLAYER2", "SUNUCU MESAJI » ${json.optString("message")}")
            }

            var enc = json.optJSONObject("enc")
            if (enc == null) enc = json.optJSONObject("config")?.optJSONObject("enc")

            if (enc == null) {
                val config = json.optJSONObject("config")
                val directV = config?.optString("v", "")?.takeIf { it.isNotBlank() }
                if (directV != null) {
                    val t = config.optString("t", "").takeIf { it.isNotBlank() }
                        ?: detectType(directV)
                    Log.e("DPPLAYER2", "Direkt config.v bulundu, tip=$t")
                    return directV to t
                }
                val directUrl = json.optString("url", "").takeIf { it.isNotBlank() }
                if (directUrl != null) return directUrl to detectType(directUrl)
                Log.e("DPPLAYER2", "enc YOK. keys=${json.keys().asSequence().toList()}")
                return null
            }

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            Log.e("DPPLAYER2", "enc: k1=${k1B64.length} k2=${k2B64.length} iv=${ivB64.length} c=${ctB64.length}")

            if (k1B64.isEmpty() || k2B64.isEmpty() ||
                ivB64.isEmpty() || ctB64.isEmpty()) {
                Log.e("DPPLAYER2", "enc alanlarından biri BOŞ")
                return null
            }

            val k1 = decodeBase64(k1B64) ?: return null
            val k2 = decodeBase64(k2B64) ?: return null
            val iv = decodeBase64(ivB64) ?: return null
            val ct = decodeBase64(ctB64) ?: return null

            Log.e("DPPLAYER2", "decoded: k1=${k1.size}b k2=${k2.size}b iv=${iv.size}b ct=${ct.size}b")

            // key = k1 XOR k2
            val keyLen = minOf(k1.size, k2.size)
            val key = ByteArray(keyLen)
            for (i in 0 until keyLen) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            val decrypted = aesCbcDecrypt(key, iv, ct) ?: return null
            Log.e("DPPLAYER2", "DECRYPTED » $decrypted")

            // JSON içerik mi?
            if (decrypted.trim().startsWith("{")) {
                try {
                    val inner = JSONObject(decrypted.trim())
                    val u = inner.optString("url", "").takeIf { it.isNotBlank() }
                        ?: inner.optString("v", "").takeIf { it.isNotBlank() }
                        ?: inner.optString("file", "").takeIf { it.isNotBlank() }
                    if (u != null) {
                        val t = inner.optString("type", "").takeIf { it.isNotBlank() }
                            ?: detectType(u)
                        Log.e("DPPLAYER2", "JSON içinden URL » $u (tip=$t)")
                        return u to t
                    }
                } catch (_: Exception) {}
            }

            // iframe HTML
            if (decrypted.contains("<iframe")) {
                Log.e("DPPLAYER2", "iframe HTML bulundu")
                return decrypted to "iframe"
            }

            // m3u8
            Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    Log.e("DPPLAYER2", "m3u8 URL: $it")
                    return it to "m3u8"
                }

            // mp4
            Regex("""(https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    Log.e("DPPLAYER2", "mp4 URL: $it")
                    return it to "mp4"
                }

            // Genel URL
            Regex("""(https?://[^\s"'\\<>]+)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    Log.e("DPPLAYER2", "Genel URL: $it")
                    return it to detectType(it)
                }

            Log.e("DPPLAYER2", "Decrypted içerikten URL çıkarılamadı")
            return null
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "parseAndDecrypt HATA » ${e.message}")
            return null
        }
    }

    /**
     * URL'yi analiz edip tip döndürür.
     */
    private fun detectType(url: String): String = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mp4", true) -> "mp4"
        url.contains("<iframe", true) -> "iframe"
        url.contains("/embed", true) -> "iframe"
        url.contains("/player", true) -> "iframe"
        url.endsWith(".html") -> "iframe"
        url.contains("iframe", true) -> "iframe"
        else -> "m3u8"
    }

    private fun decodeBase64(input: String): ByteArray? = try {
        val padded = when (input.length % 4) {
            2 -> "$input=="
            3 -> "$input="
            else -> input
        }
        val normalized = padded.replace('-', '+').replace('_', '/')
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getDecoder().decode(normalized)
        } else {
            android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
        }
    } catch (e: Exception) {
        Log.e("DPPLAYER2", "Base64 fail » ${e.message}")
        null
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("DPPLAYER2", "AES fail » ${e.message}")
        null
    }
}
