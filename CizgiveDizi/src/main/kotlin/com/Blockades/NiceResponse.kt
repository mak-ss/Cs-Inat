// NiceResponse.kt
package com.Blockades

import okhttp3.Response
import okio.BufferedSource

class NiceResponse(private val response: Response) {

    val isSuccessful: Boolean
        get() = response.isSuccessful

    val code: Int
        get() = response.code

    /**
     * Yanıt gövdesini güvenli bir şekilde String olarak döndürür.
     * 5MB üzeri dosyalar için source().readUtf8() kullanılır.
     */
    fun getBodyAsString(): String? {
        return if (isSuccessful) {
            val body = response.body
            if (body != null) {
                val source: BufferedSource = body.source()
                source.readUtf8()
            } else {
                null
            }
        } else {
            "HTTP Hatası: $code - ${response.message}"
        }
    }

    fun close() {
        response.close()
    }
}
