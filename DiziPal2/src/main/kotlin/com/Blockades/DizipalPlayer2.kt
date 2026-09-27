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

        // 1. Bölüm sayfasını çek
        val pageResp = try {
            app.get(url, referer = fixedReferer)
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Sayfa hatası » ${e.message}")
            return
        }
        val document = pageResp.document

        // 2. Cookie'lerden _ct token'ını ara (cookieMap kullan — Pair DEĞİL)
        val cookieMap = pageResp.cookieMap
        Log.d("DPPLAYER2", "COOKIES » $cookieMap")

        var token = ""
        cookieMap["_ct"]?.takeIf { it.isNotBlank() }?.let { ct ->
            token = ct
            Log.d("DPPLAYER2", "Token _ct cookie'sinden alındı » $token")
        }

        // 3. cfg al
        val cfg = document.selectFirst("#videoContainer")
            ?.attr("data-cfg")?.takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "data-cfg YOK")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 4. Cookie'den token gelmediyse endpoint'i dene
        if (token.isBlank()) {
            token = getAjaxToken(url)
            Log.d("DPPLAYER2", "Token endpoint'ten » $token")
        }

        // 5. Hâlâ yoksa meta[name='csrf-token'] dene
        if (token.isBlank()) {
            token = document.selectFirst("meta[name='csrf-token']")?.attr("content") ?: ""
            Log.d("DPPLAYER2", "Token meta csrf-token'dan » $token")
        }

        if (token.isBlank()) {
            Log.e("DPPLAYER2", "Token HİÇBİR YERDEN alınamadı!")
            return
        }

        // 6. POST /ajax-player-config
        val response = try {
            app.post(
                "$mainUrl/ajax-player-config",
                data = mapOf(
                    "cfg" to cfg,
                    "token" to token,
                    "csrf_token" to token,
                    "_token" to token
                ),
                referer = url,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                )
            ).text
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "POST hatası » ${e.message}")
            return
        }

        Log.d("DPPLAYER2", "RAW RESPONSE » ${response.take(600)}")

        // 7. Decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt BAŞARISIZ")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.d("DPPLAYER2", ">>> videoType » $videoType")

        // 8. iframe
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

        // 9. Direkt link
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
    }

    private suspend fun getAjaxToken(referer: String): String {
        return try {
            // Önce ana sayfayı ziyaret et (cookie toplamak için)
            try {
                app.get(mainUrl, referer = null)
            } catch (_: Exception) {}

            val resp = try {
                app.get(
                    "$mainUrl/ajax-token",
                    referer = referer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*"
                    )
                )
            } catch (e: Exception) {
                Log.e("DPPLAYER2", "ajax-token isteği hatası » ${e.message}")
                return ""
            }

            Log.d("DPPLAYER2", "ajax-token STATUS » ${resp.code}")
            Log.d("DPPLAYER2", "ajax-token YANIT » ${resp.text}")

            // Cookie'den kontrol et (cookieMap kullan)
            resp.cookieMap["_ct"]?.takeIf { it.isNotBlank() }?.let { ct ->
                Log.d("DPPLAYER2", "Cookie _ct bulundu: $ct")
                return ct
            }

            val json = JSONObject(resp.text)
            json.optString("t", "").takeIf { it.isNotBlank() }
                ?: json.optString("token", "").takeIf { it.isNotBlank() }
                ?: ""
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "getAjaxToken genel hata » ${e.message}")
            ""
        }
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
                if (directV != null) return directV to config.optString("t", "m3u8")
                val directUrl = json.optString("url", "").takeIf { it.isNotBlank() }
                if (directUrl != null) return directUrl to detectType(directUrl)
                return null
            }

            val k1B64 = enc.optString("k1", "")
            val k2B64 = enc.optString("k2", "")
            val ivB64 = enc.optString("iv", "")
            val ctB64 = enc.optString("c", "")

            if (k1B64.isEmpty() || k2B64.isEmpty() ||
                ivB64.isEmpty() || ctB64.isEmpty()) return null

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
            Log.d("DPPLAYER2", "DECRYPTED » $decrypted")

            if (decrypted.trim().startsWith("{")) {
                try {
                    val inner = JSONObject(decrypted.trim())
                    val u = inner.optString("url", "").takeIf { it.isNotBlank() }
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
            Log.e("DPPLAYER2", "parseAndDecrypt HATA » ${e.message}")
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
    } catch (e: Exception) { null }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("DPPLAYER2", "AES fail » ${e.message}")
        null
    }
}
