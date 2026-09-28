package com.Blockades

import org.json.JSONException
import org.json.JSONObject

// Altyazı modeli veya arayüzü gereksinimi için dummy/generic sınıf tanımı
data class SubtitleFile(val url: String, val lang: String = "")

class OdnoklassnikiExtractor {

    fun getUrl(
        responseBody: String,
        headers: Map<String, String>? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: ((String) -> Unit)? = null
    ): String? {
        val trimmedBody = responseBody.trim()

        // Sunucudan JSON yerine HTML (404/500, Cloudflare, Captcha vb.) dönüp dönmediğini kontrol et
        if (trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) || 
            trimmedBody.startsWith("<html", ignoreCase = true)
        ) {
            println("HATA: Sunucudan JSON yerine HTML yanıtı döndü.")
            return null
        }

        return try {
            val jsonObject = JSONObject(trimmedBody)
            var extractedUrl: String? = null

            if (jsonObject.has("url")) {
                extractedUrl = jsonObject.getString("url")
            } else if (jsonObject.has("play")) {
                extractedUrl = jsonObject.getString("play")
            }

            // Eğer varsa callback üzerinden sonucu bildir
            extractedUrl?.let { url ->
                callback?.invoke(url)
            }

            extractedUrl
        } catch (e: JSONException) {
            println("JSON Parse Hatası: ${e.message}")
            null
        }
    }

    // Farklı parametre sıralamaları veya aşırı yüklenmiş (overloaded) çağrılar için esnek getUrl metodu
    fun getUrl(
        responseBody: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        return getUrl(responseBody, null, subtitleCallback, null)
    }

    fun getUrl(
        responseBody: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (String) -> Unit
    ): String? {
        return getUrl(responseBody, null, subtitleCallback, callback)
    }

    fun getUrl(
        responseBody: String,
        headers: Map<String, String>?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        return getUrl(responseBody, headers, subtitleCallback, null)
    }
}
