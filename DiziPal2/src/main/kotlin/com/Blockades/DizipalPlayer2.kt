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
        Log.d("DPPLAYER2", "===== BAŞLANGIÇ =====")

        // 1. Bölüm sayfası
        val pageResp = try {
            app.get(url, referer = "$mainUrl/")
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "Sayfa hatası » ${e.message}")
            return
        }
        val document = pageResp.document
        
        // TÜM cookie'leri logla
        val pageCookies = pageResp.cookies
        Log.d("DPPLAYER2", "SAYFA COOKIES » $pageCookies")
        pageCookies.forEach { (k, v) ->
            Log.d("DPPLAYER2", "  → $k = ${v.take(50)}")
        }

        // 2. cfg
        val cfg = document.selectFirst("#videoContainer")
            ?.attr("data-cfg")?.takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "cfg YOK")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 3. CSRF token'ı meta'dan al
        val csrfToken = document.selectFirst("meta[name='csrf-token']")
            ?.attr("content")?.takeIf { it.isNotBlank() } ?: ""
        Log.d("DPPLAYER2", "CSRF TOKEN » $csrfToken")

        // 4. XSRF-TOKEN cookie'sini al (Laravel)
        val xsrfCookie = pageCookies["XSRF-TOKEN"] ?: ""
        Log.d("DPPLAYER2", "XSRF-TOKEN » ${xsrfCookie.take(50)}")

        // 5. _ct cookie'sini al
        val ctCookie = pageCookies["_ct"] ?: ""
        Log.d("DPPLAYER2", "CT COOKIE » $ctCookie")

        // 6. Token sırası: _ct → ajax-token → csrf
        var token = ctCookie
        if (token.isBlank()) {
            Log.d("DPPLAYER2", "_ct yok, /ajax-token deneniyor...")
            val tokenResp = try {
                app.get("$mainUrl/ajax-token",
                    referer = url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*"
                    )
                )
            } catch (e: Exception) {
                Log.e("DPPLAYER2", "ajax-token hata » ${e.message}")
                null
            }
            
            if (tokenResp != null) {
                Log.d("DPPLAYER2", "ajax-token STATUS » ${tokenResp.code}")
                Log.d("DPPLAYER2", "ajax-token BODY » ${tokenResp.text}")
                Log.d("DPPLAYER2", "ajax-token COOKIES » ${tokenResp.cookies}")
                
                token = tokenResp.cookies["_ct"]?.takeIf { it.isNotBlank() } ?: ""
                
                if (token.isBlank()) {
                    try {
                        val json = JSONObject(tokenResp.text)
                        token = json.optString("t", "").takeIf { it.isNotBlank() }
                            ?: json.optString("token", "").takeIf { it.isNotBlank() }
                            ?: ""
                    } catch (_: Exception) {}
                }
            }
        }

        if (token.isBlank()) token = csrfToken

        Log.d("DPPLAYER2", "FINAL TOKEN » ${token.take(80)}")

        // 7. Cookie Map'i hazırla — TÜM cookie'leri gönder
        val cookiesMap = mutableMapOf<String, String>()
        pageCookies.forEach { (k, v) -> 
            if (v.isNotBlank()) cookiesMap[k] = v
        }
        // Ek cookie'ler
        if (ctCookie.isNotBlank()) cookiesMap["_ct"] = ctCookie
        if (xsrfCookie.isNotBlank()) cookiesMap["XSRF-TOKEN"] = xsrfCookie
        
        Log.d("DPPLAYER2", "GÖNDERİLEN COOKIES » ${cookiesMap.keys}")

        // 8. POST /ajax-player-config — TÜM header'lar
        val response = try {
            app.post(
                "$mainUrl/ajax-player-config",
                data = mapOf(
                    "cfg" to cfg,
                    "_token" to token,        // Laravel'in beklediği isim
                    "csrf_token" to token,    // Alternatif
                    "token" to token          // Alternatif
                ),
                referer = url,
                cookies = cookiesMap,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-CSRF-TOKEN" to csrfToken,        // Laravel header
                    "X-XSRF-TOKEN" to xsrfCookie        // Laravel XSRF header
                )
            ).text
        } catch (e: Exception) {
            Log.e("DPPLAYER2", "POST hata » ${e.message}")
            return
        }

        Log.d("DPPLAYER2", "RAW RESPONSE » ${response.take(600)}")

        // 9. Decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt BAŞARISIZ")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.d("DPPLAYER2", ">>> videoType » $videoType")

        if (videoType == "iframe") {
            val iframeUrl = Regex("""src=["']([^"']+)["']""")
                .find(videoUrl)?.groupValues?.get(1)
                ?: videoUrl.takeIf { it.startsWith("http") }
            if (!iframeUrl.isNullOrBlank()) {
                try {
                    if (loadExtractor(fixUrl(iframeUrl), url, subtitleCallback, callback)) return
                } catch (e: Exception) {
                    Log.e("DPPLAYER2", "loadExtractor hata » ${e.message}")
                }
            }
            return
        }

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
