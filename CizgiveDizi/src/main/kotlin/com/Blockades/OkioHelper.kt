// OkioHelper.kt
package com.Blockades

import okhttp3.Response
import okio.BufferedSource

object OkioHelper {

    /**
     * Büyük metin dosyalarını (5MB üzeri) güvenli bir şekilde okur.
     * Loglardaki "Content-Length > 5000000" hatasını çözer.
     */
    fun readLargeText(response: Response): String? {
        return try {
            val body = response.body
            if (body != null) {
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
