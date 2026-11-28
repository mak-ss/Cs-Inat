package com.Blockades

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Cloudflare / JS challenge aşmak için görünmez WebView sağlar.
 * Context, Plugin.load() içinde set edilir.
 */
object WebViewVekili {
    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    fun kur(context: Context) {
        appContext = context.applicationContext
    }

    fun getContext(): Context? = appContext

    fun yeniWebView(): WebView? {
        val ctx = appContext ?: return null
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            webViewClient = WebViewClient()
        }
    }
}
