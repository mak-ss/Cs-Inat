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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedReferer = referer ?: "$mainUrl/"
        Log.d("DPPLAYER2", "===== BAŞLANGIÇ =====")
        Log.d("DPPLAYER2", "url » $url")

        // 1) Bölüm sayfasını çek
        val document = try {
            app.get(url, referer = fixedReferer).document
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Sayfa hatası » ${e.message}", e)
            return
        }

        // 2) cfg'yi al
        val videoContainer = document.selectFirst("#videoContainer")
        if (videoContainer == null) {
            Log.e("DPPLAYER2", "#videoContainer YOK")
            return
        }

        val cfg = videoContainer.attr("data-cfg").takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "data-cfg YOK. HTML: ${videoContainer.outerHtml().take(300)}")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 3) POST ajax-player-config
        val response = try {
            app.post(
                "$mainUrl/ajax-player-config",
                data = mapOf("cfg" to cfg),
                referer = url,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "POST hatası » ${e.message}", e)
            return
        }

        Log.d("DPPLAYER2", "RAW RESPONSE (tam) » $response")

        // 4) Decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt başarısız!")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.d("DPPLAYER2", ">>> videoType » $videoType")

        // 5) iframe ise loadExtractor
        if (videoType == "iframe") {
            val iframeUrl = Regex("""src=["']([^"']+)["']""")
                .find(videoUrl)?.groupValues?.get(1)
                ?: videoUrl.takeIf { it.startsWith("http") }

            if (!iframeUrl.isNullOrBlank()) {
                val fixed = fixUrl(iframeUrl)
                Log.d("DPPLAYER2", "iframe » $fixed")
                try {
                    if (loadExtractor(fixed, url, subtitleCallback, callback)) {
                        Log.d("DPPLAYER2", "loadExtractor BAŞARILI")
                        return
                    }
                } catch (e: Exception) {
                    Log.e("DPPLAYER2", "loadExtractor hatası » ${e.message}")
                }
            }
            return
        }

        // 6) Direkt link
        val isM3u8 = videoType == "m3u8" ||
                     videoUrl.contains(".m3u8", ignoreCase = true)

        Log.d("DPPLAYER2", "ExtractorLink gönderiliyor (m3u8=$isM3u8)")
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
        Log.d("DPPLAYER2", "===== BİTİŞ =====")
    }

    /**
     * Sunucu yanıtını parse eder ve AES-CBC ile decrypt eder.
     *
     * Beklenen (main.js'ten):
     *   { "enc": { "k1": "...", "k2": "...", "iv": "...", "c": "..." } }
     *   veya
     *   { "success": true, "enc": { ... } }
     */
    private fun parseAndDecrypt(response: String): Pair<String, String>? {
        try {
            Log.d("DPPLAYER2", "parseAndDecrypt başladı")
            val json = JSONObject(response)

            // ⚠️ success kontrolü YAPMIYORUZ (main.js de yapmıyor)
            // Sadece "enc" var mı diye bakıyoruz

            // A) "enc" kök seviyede mi?
            var enc = json.optJSONObject("enc")

            // B) "config.enc" içinde mi?
            if (enc == null) {
                enc = json.optJSONObject("config")?.optJSONObject("enc")
                if (enc != null) Log.d("DPPLAYER2", "enc config içinde bulundu")
            }

            // C) Direkt video URL (decrypt edilmiş halde)
            if (enc == null) {
                val config = json.optJSONObject("config")
                val directV = config?.optString("v", "")?.takeIf { it.isNotBlank() }
                if (directV != null) {
                    Log.d("DPPLAYER2", "Direkt config.v bulundu")
                    val t = config.optString("t", "m3u8")
                    return directV to t
                }
                // Bazı durumlarda "url" alanı olabilir
                val directUrl = json.optString("url", "").takeIf { it.isNotBlank() }
                if (directUrl != null) {
                    Log.d("DPPLAYER2", "Direkt url alanı bulundu")
                    return directUrl to detectType(directUrl)
                }
                Log.e("DPPLAYER2", "enc alanı YOK. JSON keys: ${json.keys().asSequence().toList()}")
                return null
            }

            // enc içeriğini al
            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            Log.d("DPPLAYER2", "enc keys » ${enc.keys().asSequence().toList()}")
            Log.d("DPPLAYER2", "k1.length=${k1B64.length} k2.length=${k2B64.length} iv.length=${ivB64.length} c.length=${ctB64.length}")

            if (k1B64.isEmpty() || k2B64.isEmpty() ||
                ivB64.isEmpty() || ctB64.isEmpty()
            ) {
                Log.e("DPPLAYER2", "enc alanlarından biri BOŞ!")
                return null
            }

            // Base64 decode
            val k1 = decodeBase64(k1B64)
            val k2 = decodeBase64(k2B64)
            val iv = decodeBase64(ivB64)
            val ct = decodeBase64(ctB64)

            if (k1 == null || k2 == null || iv == null || ct == null) {
                Log.e("DPPLAYER2", "Base64 decode FAIL")
                return null
            }

            Log.d("DPPLAYER2", "decoded: k1=${k1.size}b k2=${k2.size}b iv=${iv.size}b ct=${ct.size}b")

            // key = k1 XOR k2
            val keyLen = minOf(k1.size, k2.size)
            val key = ByteArray(keyLen)
            for (i in 0 until keyLen) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            // AES-CBC decrypt
            val decrypted = aesCbcDecrypt(key, iv, ct)
            if (decrypted.isNullOrBlank()) {
                Log.e("DPPLAYER2", "AES decrypt BOŞ")
                return null
            }

            Log.d("DPPLAYER2", "DECRYPTED PLAIN » $decrypted")

            // Decrypted içerikten video URL çıkar
            // 1) JSON ise
            if (decrypted.trim().startsWith("{")) {
                try {
                    val innerJson = JSONObject(decrypted.trim())
                    val innerUrl = innerJson.optString("url", "")
                        .takeIf { it.isNotBlank() }
                        ?: innerJson.optString("v", "").takeIf { it.isNotBlank() }
                        ?: innerJson.optString("file", "").takeIf { it.isNotBlank() }
                    if (innerUrl != null) {
                        val t = innerJson.optString("type", "").takeIf { it.isNotBlank() }
                            ?: detectType(innerUrl)
                        Log.d("DPPLAYER2", "JSON içinden URL çıkarıldı")
                        return innerUrl to t
                    }
                } catch (_: Exception) {}
            }

            // 2) iframe HTML
            if (decrypted.contains("<iframe")) {
                Log.d("DPPLAYER2", "iframe HTML bulundu")
                return decrypted to "iframe"
            }

            // 3) m3u8 URL
            Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    return it to "m3u8"
                }

            // 4) mp4 URL
            Regex("""(https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    return it to "mp4"
                }

            // 5) Herhangi bir http URL (uzantısız olabilir)
            Regex("""(https?://[^\s"'\\<>]+)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    Log.d("DPPLAYER2", "Genel URL yakalandı » $it")
                    return it to detectType(it)
                }

            Log.e("DPPLAYER2", "Decrypted içerikten URL çıkarılamadı!")
            return null
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "parseAndDecrypt EXCEPTION » ${e.message}", e)
            return null
        }
    }

    private fun detectType(url: String): String {
        return when {
            url.contains(".m3u8", true) -> "m3u8"
            url.contains(".mp4", true) -> "mp4"
            url.contains("iframe", true) -> "iframe"
            else -> "m3u8"  // varsayılan
        }
    }

    private fun decodeBase64(input: String): ByteArray? {
        return try {
            val padded = when (input.length % 4) {
                2 -> "$input=="
                3 -> "$input="
                0 -> input
                else -> input
            }
            val normalized = padded.replace('-', '+').replace('_', '/')

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Base64.getDecoder().decode(normalized)
            } else {
                android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Base64 fail » ${e.message} | input=${input.take(40)}")
            null
        }
    }

    private fun aesCbcDecrypt(
        key: ByteArray, iv: ByteArray, ciphertext: ByteArray
    ): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "AES fail » ${e.message}")
            null
        }
    }
}
