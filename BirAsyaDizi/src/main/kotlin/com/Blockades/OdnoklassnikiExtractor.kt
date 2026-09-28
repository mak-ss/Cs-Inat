package com.Blockades

import org.json.JSONException
import org.json.JSONObject

class OdnoklassnikiExtractor {

    fun getUrl(responseBody: String): String? {
        val trimmedBody = responseBody.trim()

        // Sunucudan JSON yerine HTML (404/500, Cloudflare, Captcha vb.) dönüp dönmediğini kontrol et
        if (trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) || 
            trimmedBody.startsWith("<html", ignoreCase = true)
        ) {
            println("HATA: Sunucudan JSON yerine HTML yanıtı döndü (Captcha, Bot Koruması veya 404/500 Sayfası).")
            return null
        }

        return try {
            val jsonObject = JSONObject(trimmedBody)
            
            // Beklenen URL anahtarının varlığını kontrol et
            if (jsonObject.has("url")) {
                jsonObject.getString("url")
            } else if (jsonObject.has("play")) { // Alternatif JSON alanı kontrolü
                jsonObject.getString("play")
            } else {
                println("HATA: JSON yanıtı içinde geçerli bir URL anahtarı bulunamadı.")
                null
            }
        } catch (e: JSONException) {
            println("JSON Parse Hatası: ${e.message}")
            null
        }
    }
}
