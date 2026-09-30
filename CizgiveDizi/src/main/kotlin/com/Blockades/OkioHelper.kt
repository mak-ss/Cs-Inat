// OkioHelper.kt
package com.Blockades

import okhttp3.Response
import okio.BufferedSource

object OkioHelper {

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
