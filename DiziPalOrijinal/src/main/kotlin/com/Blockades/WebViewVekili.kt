package com.Blockades

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewVekili {
    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    fun kur(context: Context) {
        appContext = context.applicationContext
    }

    fun getContext(): Context? = appContext

    @SuppressLint("SetJavaScriptEnabled")
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
