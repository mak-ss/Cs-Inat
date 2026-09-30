// NiceResponse.kt
package com.Blockades

import com.Blockades.OkioHelper
import okhttp3.Response

class NiceResponse(private val response: Response) {

    val isSuccessful: Boolean
        get() = response.isSuccessful

    val code: Int
        get() = response.code

    fun getBodyAsString(): String? {
        return if (isSuccessful) {
            OkioHelper.readLargeText(response)
        } else {
            "HTTP Hatası: $code - ${response.message}"
        }
    }

    fun close() {
        response.close()
    }
}
