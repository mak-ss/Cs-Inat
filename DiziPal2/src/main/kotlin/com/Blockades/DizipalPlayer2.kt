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
 * DiziPal2'nin kendi player'ı için extractor.
 */
class DizipalPlayer2 : ExtractorApi() {
    override var name = "DizipalPlayer2"
    override var mainUrl = "https://dizipal737.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedReferer = referer ?: "$mainUrl/"
        Log.d("DPPLAYER2", "url » $url")
        Log.d("DPPLAYER2", "referer » $fixedReferer")

        val document = try {
            app.get(url, referer = fixedReferer).document
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "Sayfa yükleme hatası » ${e.message}")
            return
        }

        val videoContainer = document.selectFirst("#videoContainer")
        val cfg = videoContainer?.attr("data-cfg")?.takeIf { it.isNotBlank() }

        if (cfg.isNullOrBlank()) {
            Log.d("DPPLAYER2", "data-cfg bulunamadı")
            return
        }
        Log.d("DPPLAYER2", "cfg » ${cfg.take(100)}...")

        val token = getAjaxToken(fixedReferer)
        Log.d("DPPLAYER2", "token » $token")

        val apiUrl = "$mainUrl/ajax-player-config"
        val response = try {
            app.post(
                apiUrl,
                data = mapOf(
                    "cfg" to cfg,
                    "csrf_token" to token
                ),
                referer = url,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "POST hatası » ${e.message}")
            return
        }

        Log.d("DPPLAYER2", "response » ${response.take(500)}")

        val videoUrl = parseAndDecrypt(response)
        if (videoUrl.isNullOrBlank()) {
            Log.d("DPPLAYER2", "Video URL çözülemedi")
            return
        }

        Log.d("DPPLAYER2", "videoUrl » $videoUrl")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    private suspend fun getAjaxToken(referer: String): String {
        return try {
            val resp = app.get(
                "$mainUrl/ajax-token",
                referer = referer,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                )
            ).text
            val json = JSONObject(resp)
            json.optString("t", "")
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "Token hatası » ${e.message}")
            ""
        }
    }

    private fun parseAndDecrypt(response: String): String? {
        return try {
            val json = JSONObject(response)

            if (!json.optBoolean("success", false)) {
                Log.d("DPPLAYER2", "success=false")
                return null
            }

            val config = json.optJSONObject("config") ?: return null

            val directUrl = config.optString("v", "").takeIf { it.isNotBlank() }
            if (directUrl != null) {
                if (directUrl.startsWith("http") && (directUrl.contains(".m3u8") || directUrl.contains(".mp4"))) {
                    Log.d("DPPLAYER2", "Direkt URL bulundu: $directUrl")
                    return directUrl
                }
                val m3u8InHtml = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
                    .find(directUrl)?.groupValues?.get(1)
                if (m3u8InHtml != null) {
                    Log.d("DPPLAYER2", "HTML içinden m3u8 bulundu: $m3u8InHtml")
                    return m3u8InHtml
                }
            }

            val enc = config.optJSONObject("enc") ?: return null

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            if (k1B64.isEmpty() || k2B64.isEmpty() || ivB64.isEmpty() || ctB64.isEmpty()) {
                Log.d("DPPLAYER2", "enc alanları eksik")
                return null
            }

            val k1 = decodeBase64(k1B64)
            val k2 = decodeBase64(k2B64)
            val iv = decodeBase64(ivB64)
            val ct = decodeBase64(ctB64)

            if (k1 == null || k2 == null || iv == null || ct == null) {
                Log.d("DPPLAYER2", "Base64 decode hatası")
                return null
            }

            val key = ByteArray(minOf(k1.size, k2.size))
            for (i in key.indices) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            val decrypted = aesCbcDecrypt(key, iv, ct)
            if (decrypted.isNullOrBlank()) {
                Log.d("DPPLAYER2", "AES decrypt başarısız")
                return null
            }

            Log.d("DPPLAYER2", "Decrypted » ${decrypted.take(200)}")

            val m3u8 = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
                .find(decrypted)?.groupValues?.get(1)
            if (m3u8 != null) return m3u8

            val mp4 = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""")
                .find(decrypted)?.groupValues?.get(1)
            if (mp4 != null) return mp4

            if (decrypted.startsWith("http")) return decrypted.trim()

            null
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "parseAndDecrypt hatası » ${e.message}")
            null
        }
    }

    private fun decodeBase64(input: String): ByteArray? {
        return try {
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
            Log.d("DPPLAYER2", "Base64 hatası » ${e.message}")
            null
        }
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d("DPPLAYER2", "AES hatası » ${e.message}")
            null
        }
    }
}