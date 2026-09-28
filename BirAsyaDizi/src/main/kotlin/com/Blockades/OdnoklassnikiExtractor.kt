package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONException
import org.json.JSONObject

class OdnoklassnikiExtractor {

    @JvmOverloads
    suspend fun getUrl(
        responseBody: String,
        url: String? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: ((ExtractorLink) -> Unit)? = null
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

            extractedUrl
        } catch (e: JSONException) {
            println("JSON Parse Hatası: ${e.message}")
            null
        }
    }
}
