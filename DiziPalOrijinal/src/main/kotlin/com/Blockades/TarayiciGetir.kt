package com.Blockades

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TarayiciGetir {

    class Yakalayici : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val original = chain.proceed(request)
            val bodyText = try {
                original.peekBody(1024 * 1024).string()
            } catch (e: Exception) {
                ""
            }
            val isChallenge = bodyText.contains("Just a moment", ignoreCase = true) ||
                bodyText.contains("cf-chl", ignoreCase = true) ||
                bodyText.contains("challenge-platform", ignoreCase = true)

            if (!isChallenge) return original

            Log.d("DiziPal", "--> Cloudflare challenge algılandı, WebView ile çözülüyor: ${request.url}")
            val html = cozWebView(request.url.toString(), bodyText)
            if (html.isNullOrEmpty()) return original

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody(original.body?.contentType()))
                .build()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun cozWebView(url: String, fallback: String): String? {
        val ctx = WebViewVekili.getContext() ?: return null
        val latch = CountDownLatch(1)
        var result: String? = null
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "(function(){return document.documentElement.outerHTML;})()"
                        ) { value ->
                            result = value?.trim('"')
                                ?.replace("\\u003C", "<")
                                ?.replace("\\u003E", ">")
                                ?.replace("\\/", "/")
                                ?.replace("\\\"", "\"")
                                ?.replace("\\n", "\n")
                            latch.countDown()
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? = null
                }
                loadUrl(url)
            }
            handler.postDelayed({
                if (latch.count > 0) {
                    wv.evaluateJavascript(
                        "(function(){return document.documentElement.outerHTML;})()"
                    ) { value ->
                        result = value?.trim('"')
                            ?.replace("\\u003C", "<")
                            ?.replace("\\u003E", ">")
                            ?.replace("\\/", "/")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\n", "\n")
                        latch.countDown()
                    }
                }
            }, 8000)
        }

        val ok = latch.await(12, TimeUnit.SECONDS)
        return if (ok && !result.isNullOrEmpty()) result else fallback
    }

    @Suppress("unused")
    private fun bosYanit(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
