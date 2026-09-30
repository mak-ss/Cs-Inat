// OkioHelper.kt
package com.Blockades // Paket adınızı kontrol edin

import okhttp3.Response
import okio.BufferedSource

object OkioHelper {

    /**
     * Büyük metin dosyalarını (5MB üzeri) güvenli bir şekilde okur.
     */
    fun readLargeText(response: Response): String? {
        return try {
            val body = response.body
            if (body != null) {
                // .text() yerine .source().readUtf8() kullanıyoruz.
                // Bu metod, tüm veriyi okur ve string'e çevirir.
                val source: BufferedSource = body.source()
                source.readUtf8()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
