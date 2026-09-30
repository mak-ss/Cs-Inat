// OkioHelper.kt
package com.Blockades

import okhttp3.Response
import okio.BufferedSource

object OkioHelper {

    /**
     * Büyük metin dosyalarını (5MB üzeri) güvenli bir şekilde okur.
     * Loglardaki "Called .text on a text file with Content-Length > 5000000 bytes" hatasını çözer.
     */
    fun readLargeText(response: Response): String? {
        return try {
            val body = response.body
            if (body != null) {
                // .text() yerine .textLarge() kullanıyoruz (OkHttp 4.12+)
                // Bu metod, 5MB sınırını aşan dosyaları okumaya izin verir.
                body.textLarge() 
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Eğer OkHttp sürümünüz eskiyse ve textLarge() yoksa, bu alternatif metodu kullanın.
     */
    fun readLargeTextAlternative(response: Response): String? {
        return try {
            val source: BufferedSource? = response.body?.source()
            source?.use { 
                // Tüm veriyi oku ve string'e çevir
                it.readUtf8() 
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
