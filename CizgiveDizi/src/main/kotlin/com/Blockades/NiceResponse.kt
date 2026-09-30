// NiceResponse.kt
package com.Blockades

import com.Blockades.OkioHelper
import okhttp3.Response

class NiceResponse(private val response: Response) {

    val isSuccessful: Boolean
        get() = response.isSuccessful

    val code: Int
        get() = response.code

    /**
     * Yanıt gövdesini güvenli bir şekilde String olarak döndürür.
     * Loglardaki 41. satır hatasını (Content-Length > 5000000) çözer.
     */
    fun getBodyAsString(): String? {
        return if (isSuccessful) {
            // OkioHelper üzerinden büyük dosya okuma yapıyoruz
            OkioHelper.readLargeText(response)
        } else {
            // Hata durumunda (404, 429 vb.) hata mesajını döndür
            "HTTP Hatası: $code - ${response.message}"
        }
    }

    /**
     * Yanıtı JSON objesi olarak ayrıştırmak için ham string verisini alır.
     * Loglardaki 64. satır civarı.
     */
    fun getJsonString(): String? {
        return getBodyAsString()
    }

    /**
     * Yanıtı kapatır (Bellek sızıntısını önler).
     */
    fun close() {
        response.close()
    }
}
