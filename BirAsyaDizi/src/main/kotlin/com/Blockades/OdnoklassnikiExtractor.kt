package com.Blockades

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONException
import org.json.JSONObject

class OdnoklassnikiExtractor {

    suspend fun getUrl(
        responseBody: String,
        url: String? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: ((ExtractorLink) -> Unit)? = null
    ): String? {
        val trimmedBody = responseBody.trim()

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

    // Overloaded metodlar (BirAsyaDizi.kt içindeki farklı parametre sıralamaları için)
    suspend fun getUrl(
        responseBody: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): String? {
        return getUrl(responseBody, url as String?, subtitleCallback, callback)
    }

    suspend fun getUrl(
        responseBody: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): String? {
        return getUrl(responseBody, null, subtitleCallback, callback)
    }
}
