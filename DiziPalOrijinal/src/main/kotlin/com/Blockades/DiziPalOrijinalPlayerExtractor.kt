package com.Blockades

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink

class DiziPalOrijinalPlayer : ExtractorApi() {
    override var name = "DiziPalOrijinalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                 "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val defaultHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to (referer ?: "https://dizipal1595.com/"),
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

        Log.d("DiziPal", "--> Extractor getUrl çağrıldı: ${url.take(100)}")

        val response = EngelAsici.http.get(
            url,
            referer = referer,
            headers = defaultHeaders,
            interceptor = TarayiciGetir.Yakalayici()
        ).text

        Log.d("DiziPal", "--> Extractor HTML uzunluğu: ${response.length}")

        val domainRegex = """https?://[^/]+""".toRegex()
        val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"
        val subUrls = mutableSetOf<String>()

        // ============ 1. NP_CONFIG ============
        val npConfigMatch = Regex(
            """window\.NP_CONFIG\s*=\s*(\{.*?\});\s*window\.""",
            RegexOption.DOT_MATCHES_ALL
        ).find(response) ?: Regex(
            """window\.NP_CONFIG\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL
        ).find(response)

        Log.d("DiziPal", "--> NP_CONFIG bulundu mu: ${npConfigMatch != null}")

        if (npConfigMatch != null) {
            try {
                val jsonStr = npConfigMatch.groupValues[1]
                val npJson = org.json.JSONObject(jsonStr)

                // --- Gate token ---
                val gateObj = npJson.optJSONObject("gate")
                val gateN = gateObj?.optString("n")
                val gateB = gateObj?.optInt("b", 12) ?: 12

                val token = if (!gateN.isNullOrEmpty()) {
                    calculateGateToken(response, gateN, gateB)
                } else ""

                Log.d("DiziPal", "--> Gate token: '$token' (n=$gateN, b=$gateB)")

                // --- Altyazılar ---
                val subtitlesArr = npJson.optJSONArray("subtitles")
                if (subtitlesArr != null) {
                    for (i in 0 until subtitlesArr.length()) {
                        val subObj = subtitlesArr.optJSONObject(i) ?: continue
                        var subUrl = subObj.optString("url")
                        val subLabel = subObj.optString("label", "Altyazı")
                        if (subUrl.isEmpty()) continue

                        if (token.isNotEmpty()) {
                            val sep = if (subUrl.contains("?")) "&" else "?"
                            subUrl += "${sep}p=$token"
                        }
                        if (subUrl !in subUrls) {
                            subUrls.add(subUrl)
                            subtitleCallback.invoke(
                                newSubtitleFile(lang = subLabel, url = fixUrl(subUrl)) {
                                    this.headers = mapOf(
                                        "Origin" to domain,
                                        "Referer" to url,
                                        "User-Agent" to ua
                                    )
                                }
                            )
                        }
                    }
                }

                // --- Video kaynakları ---
                val sourcesArr = npJson.optJSONArray("sources")
                if (sourcesArr != null) {
                    for (i in 0 until sourcesArr.length()) {
                        val srcObj = sourcesArr.optJSONObject(i) ?: continue
                        var masterUrl = srcObj.optString("master")
                        val title = srcObj.optString("title", "DPlayer")
                        if (masterUrl.isEmpty()) continue

                        if (masterUrl.startsWith("//")) masterUrl = "https:$masterUrl"
                        else if (!masterUrl.startsWith("http")) masterUrl = "$domain/$masterUrl"

                        if (token.isNotEmpty()) {
                            val sep = if (masterUrl.contains("?")) "&" else "?"
                            masterUrl += "${sep}p=$token"
                        }

                        Log.d("DiziPal", "--> Master URL (token ile): ${masterUrl.take(150)}")

                        val finalUrl = resolveMasterUrl(masterUrl, url, defaultHeaders)
                        Log.d("DiziPal", "--> Final video URL: ${finalUrl.take(150)}")

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name ($title)",
                                url = finalUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.headers = mapOf(
                                    "Origin" to domain,
                                    "Referer" to url,
                                    "User-Agent" to ua
                                )
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> NP_CONFIG parse hatası: ${e.message}")
            }
        }

        // ============ 2. Eski openPlayer ============
        val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()

        Regex(""""file":"((?:\\\\\"|[^"])+)","label":"((?:\\\\\"|[^"])+)"""").findAll(response).forEach {
            val (subUrlExt, subLangExt) = it.destructured
            val subUrl = subUrlExt.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
            val subLang = subLangExt
                .replace("\\u0131", "ı").replace("\\u0130", "İ")
                .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
                .replace("\\u011f", "ğ").replace("\\u015f", "ş")

            if (subUrl in subUrls) return@forEach
            subUrls.add(subUrl)

            subtitleCallback.invoke(
                newSubtitleFile(lang = subLang, url = fixUrl(subUrl)) {
                    headers = mapOf("Referer" to url, "User-Agent" to ua)
                }
            )
        }

        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)
        if (playlistId != null) {
            val apiUrl = "$domain/source2.php?v=$playlistId"
            val apiResponse = EngelAsici.http.get(
                apiUrl,
                referer = url,
                headers = defaultHeaders,
                interceptor = TarayiciGetir.Yakalayici()
            ).text

            try {
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                fileRegex.findAll(apiResponse).forEach { matchResult ->
                    var fileUrl = matchResult.groupValues[1].replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                    else if (!fileUrl.startsWith("http")) fileUrl = "https://$fileUrl"

                    if (fileUrl.contains("m.php")) fileUrl = fileUrl.replace("m.php", "master.m3u8")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DPlayer (Auto)",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = mapOf(
                                "Origin" to domain,
                                "Referer" to url,
                                "User-Agent" to ua
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }

    private suspend fun resolveMasterUrl(
        masterUrl: String,
        pageUrl: String,
        headers: Map<String, String>
    ): String {
        return try {
            val resp = EngelAsici.http.get(
                masterUrl,
                referer = pageUrl,
                headers = headers,
                allowRedirects = true
            )
            val body = resp.text.trim()

            when {
                body.startsWith("#EXTM3U") -> masterUrl
                body.startsWith("http") -> body.lines().first().trim()
                body.startsWith("{") -> {
                    try {
                        val j = org.json.JSONObject(body)
                        val u = j.optString("url").ifEmpty { j.optString("file") }
                        if (u.isNotEmpty()) u else masterUrl
                    } catch (_: Exception) { masterUrl }
                }
                else -> {
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(body)?.value ?: masterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPal", "--> resolveMasterUrl hatası: ${e.message}")
            masterUrl
        }
    }

    private fun calculateGateToken(html: String, gateN: String, gateB: Int): String {
        return try {
            val gateMatch = Regex(
                """window\.NP_GATE\s*=\s*function\s*\(([^)]+)\)\s*\{(.*?)\};""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)

            if (gateMatch == null) {
                Log.e("DiziPal", "--> NP_GATE fonksiyonu HTML'de bulunamadı!")
                return ""
            }

            val params = gateMatch.groupValues[1].split(",").map { it.trim() }
            val pNonce = params.getOrNull(0) ?: "_fd"
            val pHash  = params.getOrNull(1) ?: "_idp"
            val body   = gateMatch.groupValues[2]

            val saltMatch = Regex(
                """var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)"""
            ).find(body)
            val saltVar = saltMatch?.groupValues?.get(1) ?: ""
            val saltVal = saltMatch?.groupValues?.get(2)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull()?.toChar() }
                ?.joinToString("") ?: ""

            val radixMatch = Regex(
                """var\s+(\w+)\s*=\s*\w+\.toString\((\d+)\)"""
            ).find(body)
            val nonceVar = radixMatch?.groupValues?.get(1) ?: ""
            val radix = radixMatch?.groupValues?.get(2)?.toIntOrNull() ?: 36

            val callMatch = Regex(
                Regex.escape(pHash) + """\s*\(([^)]+)\)\s*\[(\d+)\]"""
            ).find(body)
            val exprStr = callMatch?.groupValues?.get(1)
                ?: "$nonceVar+'|'+$saltVar+'|'+$pNonce"
            val wordIdx = callMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

            val shiftMatch = Regex("""\]\s*>>>\s*(\d+)\s*\)\s*===\s*0""").find(body)
            val maskMatch  = Regex("""\]\s*&\s*(\d+)\s*\)\s*===\s*0""").find(body)
            val shift = shiftMatch?.groupValues?.get(1)?.toIntOrNull() ?: (32 - gateB)
            val mask  = maskMatch?.groupValues?.get(1)?.toIntOrNull()

            val parts = exprStr.split("+").map { it.trim() }
            val preBuilder = StringBuilder()
            val sufBuilder = StringBuilder()
            var seenNonce = false

            for (p in parts) {
                if (p == nonceVar && nonceVar.isNotEmpty()) {
                    seenNonce = true
                    continue
                }
                val resolved = when {
                    p == pNonce -> gateN
                    p == saltVar && saltVar.isNotEmpty() -> saltVal
                    (p.startsWith("'") && p.endsWith("'")) ||
                    (p.startsWith("\"") && p.endsWith("\"")) -> p.substring(1, p.length - 1)
                    else -> ""
                }
                if (!seenNonce) preBuilder.append(resolved) else sufBuilder.append(resolved)
            }

            val prefix = preBuilder.toString()
            val suffix = sufBuilder.toString()
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
            val byteOffset = wordIdx * 4

            Log.d(
                "DiziPal",
                "GATE DEBUG: prefix='$prefix' suffix='$suffix' wordIdx=$wordIdx " +
                "shift=$shift mask=$mask radix=$radix salt='$saltVal' gateN='$gateN'"
            )

            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (i in 0 until 50_000_000) {
                val nonce = i.toString(radix)
                md.reset()
                md.update(prefixBytes)
                md.update(nonce.toByteArray(Charsets.UTF_8))
                md.update(suffixBytes)
                val digest = md.digest()

                if (byteOffset + 3 >= digest.size) continue

                val word = ((digest[byteOffset].toInt() and 0xFF) shl 24) or
                           ((digest[byteOffset + 1].toInt() and 0xFF) shl 16) or
                           ((digest[byteOffset + 2].toInt() and 0xFF) shl 8) or
                           (digest[byteOffset + 3].toInt() and 0xFF)

                val matched = if (mask != null) (word and mask) == 0
                              else (word ushr shift) == 0

                if (matched) {
                    Log.d("DiziPal", "--> Gate token bulundu: nonce='$nonce' (i=$i)")
                    return nonce
                }
            }
            Log.e("DiziPal", "--> Gate token 50M denemede bulunamadı!")
            ""
        } catch (e: Exception) {
            Log.e("DiziPal", "--> Gate token hesaplama hatası: ${e.message}")
            ""
        }
    }
}
