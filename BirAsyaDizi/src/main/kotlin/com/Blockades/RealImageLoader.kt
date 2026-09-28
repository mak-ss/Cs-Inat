package com.Blockades

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

class RealImageLoader(private val context: Context) {

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun fetchImageStream(url: String): InputStream? {
        return try {
            val request = Request.Builder()
                .url(url)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()
            } else {
                null
            }
        } catch (e: Exception) {
            println("Görsel yükleme hatası: ${e.message}")
            null
        }
    }
}
