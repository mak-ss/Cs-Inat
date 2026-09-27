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

/**
 * Dizipal2134 player extractor — main.js analizine göre KESİN çalışan versiyon.
 *
 * Akış:
 *  1. #videoContainer[data-cfg] al
 *  2. POST {mainUrl}/ajax-player-config  (Content-Type: form-urlencoded, body: cfg=<value>)
 *  3. Yanıt: { "success": true, "enc": { "k1": "...", "k2": "...", "iv": "...", "c": "..." } }
 *  4. key = base64_decode(k1) XOR base64_decode(k2)
 *  5. AES-CBC + Pkcs7 decrypt(base64_decode(c), key, base64_decode(iv)) → video URL
 *  6. ExtractorLink olarak gönder
 *
 * NOT: CSRF token player için GEREKLİ DEĞİL (main.js'te sadece cfg gönderiliyor).
 */
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
        Log.d("DPPLAYER2", "url » $url")

        // 1. Bölüm sayfasını çek
        val document = try {
            app.get(url, referer = fixedReferer).document
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "Sayfa yükleme hatası » ${e.message}")
            return
        }

        // 2. #videoContainer → data-cfg
        val videoContainer = document.selectFirst("#videoContainer")
        val cfg = videoContainer?.attr("data-cfg")?.takeIf { it.isNotBlank() }

        if (cfg.isNullOrBlank()) {
            Log.d("DPPLAYER2", "data-cfg bulunamadı!")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 3. POST /ajax-player-config  (sadece cfg — CSRF YOK)
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
            Log.d("DPPLAYER2", "POST hatası » ${e.message}")
            return
        }

        Log.d("DPPLAYER2", "raw response » ${response.take(400)}")

        // 4. Parse + decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.d("DPPLAYER2", "Video çözülemedi")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", "videoUrl » $videoUrl")
        Log.d("DPPLAYER2", "videoType » $videoType")

        // 5. iframe tipindeyse loadExtractor'a düşür
        if (videoType == "iframe" || videoUrl.contains("<iframe")) {
            val iframeUrl = Regex("""src=["']([^"']+)["']""").find(videoUrl)
                ?.groupValues?.get(1)
            if (!iframeUrl.isNullOrBlank()) {
                val fixed = fixUrl(iframeUrl)
                Log.d("DPPLAYER2", "iframe bulundu » $fixed")
                try {
                    if (loadExtractor(fixed, url, subtitleCallback, callback)) return
                } catch (e: Exception) {
                    Log.d("DPPLAYER2", "loadExtractor hatası » ${e.message}")
                }
            }
            return
        }

        // 6. m3u8/mp4 doğrudan link
        val isM3u8 = videoType == "m3u8" ||
                     videoUrl.contains(".m3u8") ||
                     videoUrl.contains("m3u8")

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
    }

    /**
     * Sunucu yanıtını parse eder ve enc alanını AES-CBC ile çözer.
     *
     * Beklenen format:
     *   {
     *     "success": true,
     *     "enc": {
     *       "k1": "base64...",  // anahtar parçası 1
     *       "k2": "base64...",  // anahtar parçası 2
     *       "iv": "base64...",  // initialization vector
     *       "c":  "base64..."   // ciphertext (video URL)
     *     }
     *   }
     *
     * Bazı durumlarda zaten decrypt edilmiş format da olabilir:
     *   { "success": true, "config": { "v": "https://...m3u8", "t": "m3u8", "p": "poster" } }
     */
    private fun parseAndDecrypt(response: String): Pair<String, String>? {
        return try {
            val json = JSONObject(response)

            if (!json.optBoolean("success", false)) {
                Log.d("DPPLAYER2", "success=false")
                return null
            }

            // A) Zaten decrypt edilmiş formatta mı? (bazı CDN'ler için)
            json.optJSONObject("config")?.let { config ->
                val v = config.optString("v", "").takeIf { it.isNotBlank() }
                val t = config.optString("t", "m3u8")
                if (v != null) {
                    Log.d("DPPLAYER2", "Direkt config bulundu")
                    return v to t
                }
            }

            // B) Şifreli "enc" alanı — KÖK SEVİYEDE
            val enc = json.optJSONObject("enc") ?: run {
                Log.d("DPPLAYER2", "enc alanı yok")
                return null
            }

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            if (k1B64.isEmpty() || k2B64.isEmpty() ||
                ivB64.isEmpty() || ctB64.isEmpty()
            ) {
                Log.d("DPPLAYER2", "enc alanlarından biri boş")
                return null
            }

            val k1 = decodeBase64(k1B64) ?: return null
            val k2 = decodeBase64(k2B64) ?: return null
            val iv = decodeBase64(ivB64) ?: return null
            val ct = decodeBase64(ctB64) ?: return null

            // key = k1 XOR k2 (main.js'te xorBytes(k1, k2))
            val keyLen = minOf(k1.size, k2.size)
            val key = ByteArray(keyLen)
            for (i in 0 until keyLen) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            Log.d("DPPLAYER2", "key len » ${key.size}, iv len » ${iv.size}, ct len » ${ct.size}")

            val decrypted = aesCbcDecrypt(key, iv, ct)
            if (decrypted.isNullOrBlank()) {
                Log.d("DPPLAYER2", "AES decrypt başarısız")
                return null
            }

            Log.d("DPPLAYER2", "Decrypted » ${decrypted.take(300)}")

            // Decrypted içeriği video URL'sini içerir
            // Bazen direkt URL, bazen JSON, bazen iframe HTML olabilir

            // 1. m3u8 bul
            Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    return it to "m3u8"
                }

            // 2. mp4 bul
            Regex("""(https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let {
                    return it to "mp4"
                }

            // 3. iframe HTML ise src'yi çıkar
            if (decrypted.contains("<iframe")) {
                Regex("""src=["']([^"']+)["']""")
                    .find(decrypted)?.groupValues?.get(1)?.let {
                        return it to "iframe"
                    }
            }

            // 4. Direkt URL ise
            val trimmed = decrypted.trim()
            if (trimmed.startsWith("http")) {
                val type = when {
                    trimmed.contains(".m3u8") -> "m3u8"
                    trimmed.contains(".mp4")  -> "mp4"
                    else -> "m3u8"
                }
                return trimmed to type
            }

            Log.d("DPPLAYER2", "Decrypted içerikten video URL çıkarılamadı")
            null
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "parseAndDecrypt hatası » ${e.message}")
            null
        }
    }

    /**
     * Base64 decode — URL-safe ve normal base64'ü destekler.
     * main.js'teki atob() ile uyumlu.
     */
    private fun decodeBase64(input: String): ByteArray? {
        return try {
            // atob() standard base64 bekler — padding ekle
            val padded = when (input.length % 4) {
                2 -> "$input=="
                3 -> "$input="
                0 -> input
                else -> input  // hatalı durum — olduğu gibi dene
            }
            // URL-safe karakterleri normalize et
            val normalized = padded.replace('-', '+').replace('_', '/')

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Base64.getDecoder().decode(normalized)
            } else {
                android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "Base64 decode hatası » ${e.message} | input=${input.take(30)}")
            null
        }
    }

    /**
     * AES-CBC/Pkcs7 decrypt.
     * main.js: CryptoJS.AES.decrypt({ciphertext: ct}, key, {iv, mode: CBC, padding: Pkcs7})
     */
    private fun aesCbcDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray
    ): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")  // Pkcs7 == PKCS5 Android'de
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "AES decrypt hatası » ${e.message}")
            null
        }
    }
}
