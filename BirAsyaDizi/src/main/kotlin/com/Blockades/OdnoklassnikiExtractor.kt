package com.Blockades

import org.json.JSONException
import org.json.JSONObject

class OdnoklassnikiExtractor {

    fun getUrl(responseBody: String): String? {
        // Yanıtın HTML veya geçersiz bir format olup olmadığını kontrol et
        val trimmedBody = responseBody.trim()
        if (trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) || trimmedBody.startsWith("<html", ignoreCase = true)) {
            println("HATA: Sunucudan JSON yerine HTML yanıtı döndü (Captcha, Bot Koruması veya 404/500 Sayfası).")
            return null
        }

        return try {
            val jsonObject = JSONObject(trimmedBody)
            // JSON verisinden URL ayıklama mantığınız
            if (jsonObject.has("url")) {
                jsonObject.getString("url")
            } else {
                null
            }
        } catch (e: JSONException) {
            println("JSON Parse Hatası: ${e.message}")
            null
        }
    }
}
