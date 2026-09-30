// CizgiveDizi.kt
package com.Blockades

import com.Blockades.NiceResponse
import com.Blockades.NetworkModule // Ağ modülünüz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class CizgiveDizi {

    /**
     * Dizi verilerini API'den çeker.
     * Loglardaki "CizgiveDizi.kt 33" hatasını çözer.
     */
    suspend fun fetchDiziData(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android)")
            .build()

        try {
            // NetworkModule'deki OkHttpClient'ı kullan
            val response = NetworkModule.okHttpClient.newCall(request).execute()
            
            // NiceResponse sınıfına sararak güvenli okuma yap
            val niceResponse = NiceResponse(response)
            
            if (niceResponse.isSuccessful) {
                // --- HATA ÇÖZÜMÜ: getBodyAsString() artık büyük dosyaları okuyabilir ---
                val body = niceResponse.getBodyAsString()
                niceResponse.close() // Kaynağı kapat
                body
            } else {
                val errorMsg = "Hata: ${niceResponse.code}"
                niceResponse.close()
                errorMsg
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Bağlantı Hatası: ${e.message}"
        }
    }

    /**
     * Gelen JSON verisini ayrıştırır.
     * Loglardaki "CizgiveDizi.kt 14" civarı.
     */
    suspend fun parseDiziJson(jsonString: String): List<String> {
        val diziListesi = mutableListOf<String>()
        try {
            val jsonObject = JSONObject(jsonString)
            val diziler = jsonObject.optJSONArray("diziler") // API'nize göre değiştirin
            
            if (diziler != null) {
                for (i in 0 until diziler.length()) {
                    val dizi = diziler.getJSONObject(i)
                    val isim = dizi.optString("isim")
                    diziListesi.add(isim)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return diziListesi
    }
}
