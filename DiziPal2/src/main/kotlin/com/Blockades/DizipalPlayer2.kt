package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        Log.d("DPPLAYER2", "===== BAŞLANGIÇ =====")
        Log.d("DPPLAYER2", "url » $url")

        // 1. Bölüm/Film sayfasını çek ve oturum çerezlerini sakla
        val pageResp = try {
            app.get(url, referer = "$mainUrl/")
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Sayfa çekme hatası » ${e.message}")
            return
        }
        val document = pageResp.document
        val cookieMap: Map<String, String> = pageResp.cookies

        // 2. data-cfg parametresini al
        val cfg = document.selectFirst("#videoContainer")
            ?.attr("data-cfg")?.takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "cfg özniteliği bulunamadı")
            return
        }

        // 3. POST /ajax-player-config isteği
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
            Log.e("DPPLAYER2", "POST isteği başarısız » ${e.message}")
            return
        }

        // 4. Yanıtı Çöz (AES CBC Decrypt)
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decryption işlemi başarısız")
            return
        }

        val (videoUrl, videoType) = result

        // 5. Iframe/Embed İşleme
        if (videoType == "iframe" || videoUrl.contains("<iframe", true) || videoUrl.contains(".html", true)) {
            val iframeUrl = Regex("""src=["']([^"']+)["']""")
                .find(videoUrl)?.groupValues?.get(1)
                ?: videoUrl.takeIf { it.startsWith("http") }

            if (!iframeUrl.isNullOrBlank()) {
                val fixedUrl = fixUrl(iframeUrl)
                Log.d("DPPLAYER2", "Iframe adresi işleniyor » $fixedUrl")

                // Öncelik 1: Cloudstream dahili extractor'ları ile dene
                val extracted = try {
                    loadExtractor(fixedUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    false
                }

                // Öncelik 2: Tanımlı değilse doğrudan FormationFeedExtractor'a yönlendir
                if (!extracted) {
                    Log.d("DPPLAYER2", "Dahili extractor bulunamadı, özel Extractor çağrılıyor")
                    FormationFeedExtractor().getUrl(fixedUrl, url, subtitleCallback, callback)
                }
            }
            return
        }

        // 6. Doğrudan Video Adresi Dönmüşse (m3u8 veya mp4)
        val isM3u8 = videoUrl.contains(".m3u8", true)
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

    private fun parseAndDecrypt(response: String): Pair<String, String>? {
        try {
            val json = JSONObject(response)

            var enc = json.optJSONObject("enc")
            if (enc == null) enc = json.optJSONObject("config")?.optJSONObject("enc")

            if (enc == null) {
                val config = json.optJSONObject("config")
                val directV = config?.optString("v", "")?.takeIf { it.isNotBlank() }
                if (directV != null) {
                    val t = config.optString("t", "").takeIf { it.isNotBlank() } ?: "m3u8"
                    return directV to t
                }
                val directUrl = json.optString("url", "").takeIf { it.isNotBlank() }
                if (directUrl != null) return directUrl to "m3u8"
                return null
            }

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            if (k1B64.isEmpty() || k2B64.isEmpty() || ivB64.isEmpty() || ctB64.isEmpty()) return null

            val k1 = decodeBase64(k1B64) ?: return null
            val k2 = decodeBase64(k2B64) ?: return null
            val iv = decodeBase64(ivB64) ?: return null
            val ct = decodeBase64(ctB64) ?: return null

            val keyLen = minOf(k1.size, k2.size)
            val key = ByteArray(keyLen)
            for (i in 0 until keyLen) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            val decrypted = aesCbcDecrypt(key, iv, ct) ?: return null

            if (decrypted.trim().startsWith("{")) {
                try {
                    val inner = JSONObject(decrypted.trim())
                    val u = inner.optString("url", "").takeIf { it.isNotBlank() }
                        ?: inner.optString("v", "").takeIf { it.isNotBlank() }
                        ?: inner.optString("file", "").takeIf { it.isNotBlank() }
                    if (u != null) {
                        val t = inner.optString("type", "").takeIf { it.isNotBlank() } ?: "iframe"
                        return u to t
                    }
                } catch (_: Exception) {}
            }

            if (decrypted.contains("<iframe")) return decrypted to "iframe"

            Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\>]*)""").find(decrypted)?.groupValues?.get(1)?.let {
                return it to "m3u8"
            }

            Regex("""(https?://[^\s"'\\<>]+\.mp4[^\s"'\\>]*)""").find(decrypted)?.groupValues?.get(1)?.let {
                return it to "mp4"
            }

            Regex("""(https?://[^\s"'\\<>]+)""").find(decrypted)?.groupValues?.get(1)?.let {
                return it to "iframe"
            }

            return null
        } catch (e: Exception) {
            return null
        }
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
        null
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
