package com.Blockades

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPalOrijinalPlugin: Plugin() {
    override fun load(context: Context) {
        WebViewVekili.kur(context)
        registerMainAPI(DiziPalOrijinal())
        registerExtractorAPI(DiziPalOrijinalPlayer())
    }
}
