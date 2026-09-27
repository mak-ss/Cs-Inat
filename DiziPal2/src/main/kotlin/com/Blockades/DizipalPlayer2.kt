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

        // 1) Bölüm sayfasını çek (session cookie'leri topla)
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
            Log.e("DPPLAYER2", "data-cfg YOK")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 3) CSRF token (meta tag'den)
        val csrfToken = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""
        Log.d("DPPLAYER2", "csrf » $csrfToken")

        // 4) POST /ajax-player-config
        // ÖNEMLİ: referer=bölüm URL'si olmalı, cookie'ler otomatik gönderiliyor
        val response = try {
            app.post(
                "$mainUrl/ajax-player-config",
                data = mapOf("cfg" to cfg),
                referer = url,     // ← bölüm URL'si referer
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            ).text
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "POST hatası » ${e.message}", e)
            return
        }

        Log.d("DPPLAYER2", "RAW RESPONSE » $response")

        // 5) Parse
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt BAŞARISIZ")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.d("DPPLAYER2", ">>> videoType » $videoType")

        // 6) iframe
        if (videoType == "iframe") {
            val iframeUrl = Regex("""src=["']([^"']+)["']""")
                .find(videoUrl)?.groupValues?.get(1)
                ?: videoUrl.takeIf { it.startsWith("http") }
            if (!iframeUrl.isNullOrBlank()) {
                try {
                    if (loadExtractor(fixUrl(iframeUrl), url, subtitleCallback, callback)) return
                } catch (e: Exception) {
                    Log.e("DPPLAYER2", "loadExtractor hatası » ${e.message}")
                }
            }
            return
        }

        // 7) Direkt link
        val isM3u8 = videoType == "m3u8" || videoUrl.contains(".m3u8", true)
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

    private fun parseAndDecrypt(response: String): Pair<String, String>? {
        try {
            val json = JSONObject(response)

            // Önce "message" varsa logla
            if (json.has("message")) {
                Log.e("DPPLAYER2", "SUNUCU MESAJI » ${json.optString("message")}")
            }

            // enc alanını bul
            var enc = json.optJSONObject("enc")
            if (enc == null) {
                enc = json.optJSONObject("config")?.optJSONObject("enc")
            }

            // Direkt URL fallback
            if (enc == null) {
                val config = json.optJSONObject("config")
                val directV = config?.optString("v", "")?.takeIf { it.isNotBlank() }
                if (directV != null) return directV to (config.optString("t", "m3u8"))

                val directUrl = json.optString("url", "").takeIf { it.isNotBlank() }
                if (directUrl != null) return directUrl to detectType(directUrl)

                Log.e("DPPLAYER2", "enc YOK. keys=${json.keys().asSequence().toList()}")
                return null
            }

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            if (k1B64.isEmpty() || k2B64.isEmpty() ||
                ivB64.isEmpty() || ctB64.isEmpty()) {
                Log.e("DPPLAYER2", "enc alanlarından biri BOŞ")
                return null
            }

            val k1 = decodeBase64(k1B64) ?: return null
            val k2 = decodeBase64(k2B64) ?: return null
            val iv = decodeBase64(ivB64) ?: return null
            val ct = decodeBase64(ctB64) ?: return null

            val keyLen = minOf(k1.size, k2.size)
            val key = ByteArray(keyLen)
            for (i in 0 until keyLen) {
                key[i] = (k1[i].toInt() xor k2[i].toInt()).toByte()
            }

            val decrypted = aesCbcDecrypt(key, iv, ct)
            if (decrypted.isNullOrBlank()) {
                Log.e("DPPLAYER2", "AES decrypt BOŞ")
                return null
            }

            Log.d("DPPLAYER2", "DECRYPTED » $decrypted")

            // JSON içerik
            if (decrypted.trim().startsWith("{")) {
                try {
                    val inner = JSONObject(decrypted.trim())
                    val u = inner.optString("url", "")
                        .takeIf { it.isNotBlank() }
                        ?: inner.optString("v", "").takeIf { it.isNotBlank() }
                        ?: inner.optString("file", "").takeIf { it.isNotBlank() }
                    if (u != null) return u to detectType(u)
                } catch (_: Exception) {}
            }

            if (decrypted.contains("<iframe")) return decrypted to "iframe"

            Regex("""(https?://[^\s"'\\<>]+\.m3u8[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let { return it to "m3u8" }
            Regex("""(https?://[^\s"'\\<>]+\.mp4[^\s"'\\<>]*)""")
                .find(decrypted)?.groupValues?.get(1)?.let { return it to "mp4" }
            Regex("""(https?://[^\s"'\\<>]+)""")
                .find(decrypted)?.groupValues?.get(1)?.let { return it to detectType(it) }

            return null
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "parseAndDecrypt HATA » ${e.message}", e)
            return null
        }
    }

    private fun detectType(url: String): String = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mp4", true) -> "mp4"
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
