package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.delay
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

        val cookieMap: Map<String, String> = pageResp.cookies
        Log.d("DPPLAYER2", "COOKIES » ${cookieMap.keys}")

        // 2. cfg
        val cfg = document.selectFirst("#videoContainer")
            ?.attr("data-cfg")?.takeIf { it.isNotBlank() }
        if (cfg.isNullOrBlank()) {
            Log.e("DPPLAYER2", "cfg YOK")
            return
        }
        Log.d("DPPLAYER2", "cfg » $cfg")

        // 3. RETRY MEKANİZMASI — 3 deneme
        var response: String? = null
        for (attempt in 1..3) {
            Log.d("DPPLAYER2", "DENEME #$attempt")

            response = try {
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
                Log.e("DPPLAYER2", "POST hata » ${e.message}")
                null
            }

            Log.d("DPPLAYER2", "DENEME #$attempt RESPONSE » ${response?.take(300)}")

            // Başarılı mı?
            if (response != null && !response.contains("\"message\"")) {
                Log.d("DPPLAYER2", "DENEME #$attempt BAŞARILI")
                break
            }

            // Hata varsa bekle ve tekrar dene
            if (attempt < 3) {
                Log.d("DPPLAYER2", "2 saniye bekleyip tekrar deneniyor...")
                delay(2000)
            }
        }

        if (response == null) {
            Log.e("DPPLAYER2", "TÜM DENEMELER BAŞARISIZ")
            return
        }

        // 4. Decrypt
        val result = parseAndDecrypt(response)
        if (result == null) {
            Log.e("DPPLAYER2", "Decrypt BAŞARISIZ")
            return
        }

        val (videoUrl, videoType) = result
        Log.d("DPPLAYER2", ">>> videoUrl » $videoUrl")
        Log.d("DPPLAYER2", ">>> videoType » $videoType")

        // 5. Doğru yönlendirme — iframe ise loadExtractor
        if (videoType == "iframe" || videoUrl.contains("iframe", true)) {
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

        // 6. m3u8/mp4 ise direkt gönder
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
                    return directV to t
                }
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

    // ✅ DÜZELTİLDİ — .html ise "iframe" döndür
    private fun detectType(url: String): String = when {
        url.contains(".m3u8", true) -> "m3u8"
        url.contains(".mp4", true) -> "mp4"
        url.contains("<iframe", true) -> "iframe"
        url.contains("/embed", true) -> "iframe"    // ← YENİ
        url.endsWith(".html") -> "iframe"            // ← YENİ
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
